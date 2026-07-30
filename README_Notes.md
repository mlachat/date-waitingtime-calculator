Ohne Batch fällt das Hauptargument gegen Shutdown weg — kein `JobExecution` bleibt im Status `STARTED` hängen. Damit ist die Eskalationskette **Pause → Shutdown** sauber machbar: Oracle-RAC-Failover (typisch 20–40 s) übersteht der Service transparent, ein echter Ausfall führt zum Neustart durch die Orchestrierung.

Die drei Teile hängen über ein gemeinsames Interface zusammen, damit der Watchdog nichts über MQ oder Rabbit wissen muss.

```java
public interface ConsumerControl {
    void pause();
    void resume();
    String name();
}
```

---

# Teil 1 — Oracle: Erkennung und Eskalation

## Treiber-Timeouts (der wichtigste Teil)

Bei Oracle ist das kritischer als bei PostgreSQL: Ohne `ReadTimeout` blockiert ein Check gegen einen per Firewall geschluckten Listener bis zum TCP-Timeout des Betriebssystems — also potenziell Minuten. Dein Watchdog hängt dann fest, statt zu reagieren.

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@//scan.example.com:1521/SVC
    hikari:
      connection-timeout: 4000
      validation-timeout: 2500
      keepalive-time: 30000
      max-lifetime: 900000
      initialization-fail-timeout: -1     # App startet auch ohne DB
      data-source-properties:
        oracle.net.CONNECT_TIMEOUT: 3000          # TCP + Handshake
        oracle.net.OUTBOUND_CONNECT_TIMEOUT: 3000 # RAC: pro Adresse in der Liste
        oracle.jdbc.ReadTimeout: 5000             # Socket-Read, hängende Queries
        oracle.net.disableOob: true               # bei Firewalls, die OOB verwerfen
```

`OUTBOUND_CONNECT_TIMEOUT` gilt pro Adresse in einer `ADDRESS_LIST` — bei drei SCAN-Adressen mit Failover kann die Gesamtdauer also das Dreifache betragen. Das bei `connection-timeout` einkalkulieren.

## Der Health-Check

Nutze den `DataSourceHealthIndicator`, den Spring Boot ohnehin registriert — kein eigener Check, und der Zustand landet gleichzeitig in Actuator und Readiness-Probe:

```yaml
spring:
  datasource:
    hikari:
      connection-test-query: SELECT 1 FROM DUAL   # nur falls isValid() Probleme macht
management:
  endpoint:
    health:
      group:
        readiness:
          include: db
        liveness:
          include: ping        # NICHT von der DB abhängig machen
  health:
    db:
      enabled: true
```

`SELECT 1 FROM DUAL` ist das Standard-Statement für Oracle, aber `Connection.isValid(timeout)` ist besser: Der Oracle-Treiber setzt das auf einen nativen Ping um und hält den Timeout garantiert ein. Spring Boot nimmt automatisch `isValid()`, wenn keine `connection-test-query` gesetzt ist — lass sie also idealerweise weg.

**Oracle-Spezifikum, das `SELECT 1 FROM DUAL` nicht erkennt:** Nach einem Data-Guard-Switchover kann die Instanz als Read-Only-Standby hochkommen. Lesen funktioniert, Schreiben scheitert mit `ORA-16000`. Wenn dein Service schreibt, prüfe das mit:

```java
@Component
public class OracleWritableHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbc;

    OracleWritableHealthIndicator(DataSource ds) {
        this.jdbc = new JdbcTemplate(ds);
        this.jdbc.setQueryTimeout(3);          // sonst hängt der Check
    }

    @Override
    public Health health() {
        try {
            String mode = jdbc.queryForObject(
                "SELECT open_mode FROM v$database", String.class);
            return "READ WRITE".equals(mode)
                ? Health.up().withDetail("openMode", mode).build()
                : Health.down().withDetail("openMode", mode).build();
        } catch (DataAccessException e) {
            return Health.down(e).build();
        }
    }
}
```

Braucht `SELECT`-Recht auf `v$database`. Falls das im Betrieb nicht durchsetzbar ist: eine kleine eigene Statustabelle lesen — das deckt zusätzlich fehlende Schema-Migrationen und entzogene Grants ab, was `DUAL` nie erkennt.

## Watchdog

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseWatchdog {

    private static final int PAUSE_AFTER = 1;    // ~15 s
    private static final int EXIT_AFTER  = 40;   // ~10 min

    private final HealthEndpoint health;
    private final List<ConsumerControl> consumers;   // JMS + AMQP injiziert
    private final ApplicationContext ctx;
    private final AtomicInteger failures = new AtomicInteger();

    @Scheduled(fixedDelay = 15_000, initialDelay = 45_000)
    public void check() {
        if (Status.UP.equals(healthStatus())) {
            if (failures.getAndSet(0) >= PAUSE_AFTER) {
                log.info("Oracle wieder erreichbar - Consumer werden gestartet");
                consumers.forEach(ConsumerControl::resume);
            }
            return;
        }

        int n = failures.incrementAndGet();
        log.error("Oracle nicht erreichbar (Fehlversuch {}/{})", n, EXIT_AFTER);

        if (n == PAUSE_AFTER) consumers.forEach(ConsumerControl::pause);
        if (n >= EXIT_AFTER)  shutdown();
    }

    private Status healthStatus() {
        try {
            return health.healthForPath("db").getStatus();
        } catch (Exception e) {
            return Status.DOWN;
        }
    }

    /** Sofort-Pause, aufgerufen aus dem Listener bei TransientDataAccessException. */
    public void markDown() {
        if (failures.compareAndSet(0, PAUSE_AFTER)) {
            consumers.forEach(ConsumerControl::pause);
        }
    }

    private void shutdown() {
        new Thread(() -> {
            log.error("Oracle dauerhaft nicht erreichbar - Shutdown, Exit-Code 42");
            System.exit(SpringApplication.exit(ctx, () -> 42));
        }, "db-watchdog-shutdown").start();
    }
}
```

Der eigene Thread ist zwingend: `SpringApplication.exit()` fährt den `TaskScheduler` mit herunter und würde auf den Task warten, der ihn aufgerufen hat — Deadlock bis zum `awaitTermination`-Timeout. Und **Exit-Code ≠ 0**, sonst greift `restart: on-failure` nicht.

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

---

# Teil 2 — JMS / IBM MQ

## Container-Steuerung

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class JmsConsumerControl implements ConsumerControl {

    private static final Set<String> IDS = Set.of("orderListener", "invoiceListener");

    private final JmsListenerEndpointRegistry registry;

    @Override public String name() { return "JMS"; }

    @Override
    public void pause() {
        containers().filter(MessageListenerContainer::isRunning).forEach(c ->
            ((DefaultMessageListenerContainer) c)
                .stop(() -> log.warn("JMS-Listener gestoppt")));
    }

    @Override
    public void resume() {
        containers().filter(c -> !c.isRunning())
                    .forEach(MessageListenerContainer::start);
    }

    private Stream<MessageListenerContainer> containers() {
        return IDS.stream().map(registry::getListenerContainer).filter(Objects::nonNull);
    }
}
```

`stop(Runnable)` statt `stop()` — die synchrone Variante blockiert, bis alle Invoker-Threads durch sind, und genau die hängen ja gerade im DB-Zugriff.

## Factory und Listener

```java
@Bean
DefaultJmsListenerContainerFactory jmsListenerContainerFactory(ConnectionFactory cf) {
    var f = new DefaultJmsListenerContainerFactory();
    f.setConnectionFactory(cf);
    f.setSessionTransacted(true);          // ohne das: KEINE Redelivery
    f.setConcurrency("1-5");
    f.setBackOff(new ExponentialBackOff(2_000, 2.0));   // MQ-Reconnect
    f.setErrorHandler(t -> log.error("JMS-Fehler", t));
    return f;
}
```

```java
@JmsListener(id = "orderListener", destination = "ORDER.IN")
public void onMessage(OrderMessage msg) {
    try {
        service.process(msg);
    } catch (TransientDataAccessException | CannotCreateTransactionException e) {
        watchdog.markDown();
        throw e;                    // Rollback -> Redelivery
    } catch (Exception e) {
        errorService.routeToBackout(msg, e);   // fachlich: NICHT rollbacken
    }
}
```

Die Trennung technisch/fachlich ist hier entscheidend. Bei MQ zählt jeder Rollback den `JMSXDeliveryCount` hoch; ist `BOTHRESH` auf 5 gesetzt, landen fachlich kaputte Nachrichten nach fünf Runden in der `BOQNAME`. Technische Fehler dürfen rollbacken, weil der Watchdog eine Millisekunde später den Listener stoppt — es bleibt bei einem einzigen Redelivery pro Invoker-Thread.

**Zum Prüfen, dass es wirklich funktioniert:** Bei einem simulierten Ausfall muss `message.getIntProperty("JMSXDeliveryCount")` steigen. Tut es das nicht, ist `sessionTransacted` nicht aktiv und der ganze Aufbau ist wirkungslos.

---

# Teil 3 — AMQP / RabbitMQ

Strukturell identisch, aber die Registry heißt anders und das Nack-Verhalten ist ein anderes Modell:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class AmqpConsumerControl implements ConsumerControl {

    private static final Set<String> IDS = Set.of("eventListener");

    private final RabbitListenerEndpointRegistry registry;

    @Override public String name() { return "AMQP"; }

    @Override
    public void pause() {
        containers().filter(MessageListenerContainer::isRunning).forEach(c -> {
            c.stop(() -> log.warn("AMQP-Listener gestoppt"));
        });
    }

    @Override
    public void resume() {
        containers().filter(c -> !c.isRunning())
                    .forEach(MessageListenerContainer::start);
    }

    private Stream<AbstractMessageListenerContainer> containers() {
        return IDS.stream()
                  .map(registry::getListenerContainer)
                  .filter(Objects::nonNull)
                  .map(AbstractMessageListenerContainer.class::cast);
    }
}
```

## Konfiguration

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        acknowledge-mode: auto
        prefetch: 5                    # niedrig halten!
        default-requeue-rejected: false
        concurrency: 1
        max-concurrency: 5
```

Zwei Punkte, die sich von JMS unterscheiden:

**`prefetch` niedrig halten.** Beim `stop()` wird der Channel geschlossen; alle vorab geholten, noch nicht bestätigten Nachrichten gehen zurück in die Queue. Bei `prefetch: 250` (Default in älteren Boot-Versionen) sind das pro Consumer 250 Nachrichten mit erhöhtem Redelivery-Zähler.

**`default-requeue-rejected: false` plus DLX.** Der Default `true` bedeutet: Exception → `basic.nack` mit `requeue=true` → dieselbe Nachricht kommt sofort wieder → Endlosschleife mit CPU-Vollast. Mit `false` gehen abgelehnte Nachrichten in die Dead-Letter-Queue. Für den *transienten* Fall willst du aber gezielt requeuen — dafür gibt es eine eigene Exception:

```java
@RabbitListener(id = "eventListener", queues = "event.in")
public void onMessage(EventMessage msg) {
    try {
        service.process(msg);
    } catch (TransientDataAccessException | CannotCreateTransactionException e) {
        watchdog.markDown();
        throw new ImmediateRequeueAmqpException(e);   // requeue, kein DLQ
    } catch (Exception e) {
        throw new AmqpRejectAndDontRequeueException("fachlicher Fehler", e); // -> DLQ
    }
}
```

`ImmediateRequeueAmqpException` überstimmt `defaultRequeueRejected=false` für diesen einen Fall — genau das, was du bei „DB kurz weg" brauchst.

Queue-Deklaration mit DLX:

```java
@Bean
Queue eventQueue() {
    return QueueBuilder.durable("event.in")
        .withArgument("x-dead-letter-exchange", "event.dlx")
        .withArgument("x-dead-letter-routing-key", "event.failed")
        .build();
}
```

---

## Zusammenspiel und Verdrahtung

Die beiden `ConsumerControl`-Beans werden als `List<ConsumerControl>` automatisch in den Watchdog injiziert — neue Consumer-Typen brauchen keine Änderung am Watchdog.

```yaml
spring:
  jms:
    listener:
      auto-startup: false        # erst nach erstem erfolgreichen DB-Check starten
  rabbitmq:
    listener:
      simple:
        auto-startup: false
```

Dazu ein Startup-Hook, der beim Boot einmal prüft und dann freigibt:

```java
@EventListener(ApplicationReadyEvent.class)
public void onReady() {
    check();                                       // setzt failures korrekt
    if (failures.get() == 0) consumers.forEach(ConsumerControl::resume);
    else log.warn("Start ohne aktive Consumer - Oracle nicht erreichbar");
}
```

Damit startet der Service auch bei laufendem DB-Wartungsfenster durch, nimmt aber keine Nachrichten an — und beginnt automatisch, sobald Oracle wieder da ist.

**Falls du auf Kubernetes bist:** Dann brauchst du den Shutdown-Teil aus Teil 1 nicht selbst bauen. Eskalationsstufe „Pause" bleibt, aber statt `System.exit()` lässt du die Readiness-Probe auf `DOWN` gehen und definierst eine Liveness-Group, die nur das enthält, was ein Restart tatsächlich reparieren kann. Kubernetes bringt dabei sauberes `CrashLoopBackOff`, Event-Historie und kein Deadlock-Risiko im Shutdown-Pfad mit.