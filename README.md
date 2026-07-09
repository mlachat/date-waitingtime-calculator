# date-waiting-period

Java-Bibliothek zur Berechnung, ob eine **Wartezeit überschritten** wurde.

Ausgehend von einem Start-Zeitpunkt (`java.sql.Timestamp`) und einem **Offset in Werktagen**
wird ein Fälligkeitsdatum bestimmt. Beim Aufaddieren des Offsets werden

- **Wochenenden** (Samstag/Sonntag) und
- **bundesweite deutsche Feiertage**

nicht mitgezählt. Landesspezifische Feiertage (z. B. Fronleichnam, Reformationstag)
zählen bewusst **nicht** dazu.

## Berücksichtigte bundeseinheitliche Feiertage

Neujahr, Karfreitag, Ostermontag, Tag der Arbeit, Christi Himmelfahrt,
Pfingstmontag, Tag der Deutschen Einheit, 1. + 2. Weihnachtstag.

Die beweglichen Feiertage werden über das Osterdatum (Gauß/Meeus-Algorithmus) berechnet.

## Nutzung

```java
WaitingPeriodCalculator calc = new WaitingPeriodCalculator();

Timestamp start = new Timestamp(1745791200000L); // Start-Zeitpunkt
int offset = 3;                                  // Wartezeit in Werktagen

boolean ueberschritten = calc.isWaitingPeriodExceeded(start, offset);
// true  = Wartezeit überschritten
// false = Wartezeit noch nicht überschritten
```

Für Tests lässt sich das „Jetzt“ über eine `java.time.Clock` injizieren:

```java
var calc = new WaitingPeriodCalculator(Clock.fixed(instant, ZoneId.of("Europe/Berlin")));
```

## Semantik

- Alle Datumsbetrachtungen erfolgen in der Zeitzone `Europe/Berlin`.
- Die Wartezeit gilt als überschritten, sobald das aktuelle Datum **echt nach** dem
  berechneten Fälligkeitsdatum liegt. Am Fälligkeitstag selbst ist sie noch **nicht**
  überschritten.

## Bauen & Testen

```bash
mvn test        # Tests ausführen
mvn package     # JAR bauen
```

Benötigt Java 17+ und Maven 3.9+.
