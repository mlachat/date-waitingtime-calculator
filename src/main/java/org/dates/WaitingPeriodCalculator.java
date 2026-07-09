package org.dates;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/**
 * Berechnet, ob eine Wartezeit ueberschritten wurde.
 *
 * <p>Ausgehend von einem Start-Zeitpunkt ({@link java.sql.Timestamp}) und einem Offset in <em>Werktagen</em>
 * wird ein Faelligkeitsdatum bestimmt. Beim Aufaddieren des Offsets werden
 * <strong>Wochenenden</strong> (Samstag/Sonntag) und <strong>bundesweite deutsche
 * Feiertage</strong> nicht mitgezaehlt.</p>
 *
 * <p>Die Wartezeit gilt als <em>ueberschritten</em> ({@code true}), sobald das aktuelle
 * Datum <strong>echt nach</strong> dem berechneten Faelligkeitsdatum liegt. Am
 * Faelligkeitstag selbst ist sie noch nicht ueberschritten ({@code false}).</p>
 *
 * <p>Alle Datums-Betrachtungen erfolgen in der Zeitzone {@code Europe/Berlin}, da sich
 * die deutschen Feiertage darauf beziehen.</p>
 *
 * <p>Instanzen sind unveraenderlich und threadsicher.</p>
 */
public final class WaitingPeriodCalculator {

    /** Zeitzone fuer die Datumsbetrachtung (deutsche Feiertage). */
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /** Wochenendtage: Samstag und Sonntag. */
    private static final Set<DayOfWeek> WEEKEND = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private final Clock clock;

    /** Erzeugt einen Rechner, der die aktuelle Systemzeit ({@code Europe/Berlin}) verwendet. */
    public WaitingPeriodCalculator() {
        this(Clock.system(ZONE));
    }

    /**
     * Erzeugt einen Rechner mit einer definierten Uhr. Vor allem fuer Tests gedacht,
     * um das "Jetzt" reproduzierbar zu machen.
     *
     * @param clock Uhr, die das aktuelle "Jetzt" liefert, nicht {@code null}
     * @throws NullPointerException wenn {@code clock} {@code null} ist
     */
    public WaitingPeriodCalculator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Prueft, ob die Wartezeit ueberschritten wurde.
     *
     * @param startTimestamp     Start-Zeitpunkt, nicht {@code null}
     * @param offsetBusinessDays Wartezeit in Werktagen ({@code >= 0})
     * @return {@code true}, wenn die Wartezeit ueberschritten ist, sonst {@code false}
     * @throws IllegalArgumentException wenn {@code offsetBusinessDays < 0}
     * @throws NullPointerException     wenn {@code startTimestamp} {@code null} ist
     */
    public boolean isWaitingPeriodExceeded(Timestamp startTimestamp, int offsetBusinessDays) {
        Objects.requireNonNull(startTimestamp, "startTimestamp");
        LocalDate startDate = startTimestamp.toInstant().atZone(ZONE).toLocalDate();
        LocalDate dueDate = addBusinessDays(startDate, offsetBusinessDays);
        LocalDate today = LocalDate.now(clock);
        return today.isAfter(dueDate);
    }

    /**
     * Addiert eine Anzahl Werktage auf ein Datum. Wochenenden und bundesweite deutsche
     * Feiertage werden uebersprungen und nicht mitgezaehlt.
     *
     * @param start  Startdatum (wird selbst nicht mitgezaehlt), nicht {@code null}
     * @param offset Anzahl der zu addierenden Werktage ({@code >= 0})
     * @return das Faelligkeitsdatum
     * @throws IllegalArgumentException wenn {@code offset < 0}
     * @throws NullPointerException     wenn {@code start} {@code null} ist
     */
    public static LocalDate addBusinessDays(LocalDate start, int offset) {
        Objects.requireNonNull(start, "start");
        if (offset < 0) {
            throw new IllegalArgumentException("offset darf nicht negativ sein: " + offset);
        }
        LocalDate date = start;
        int added = 0;
        while (added < offset) {
            date = date.plusDays(1);
            if (isBusinessDay(date)) {
                added++;
            }
        }
        return date;
    }

    /**
     * Ein Werktag ist ein Tag, der weder Wochenende noch ein bundesweiter deutscher
     * Feiertag ist.
     *
     * @param date zu pruefendes Datum, nicht {@code null}
     * @throws NullPointerException wenn {@code date} {@code null} ist
     */
    public static boolean isBusinessDay(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return !isWeekend(date) && !GermanPublicHolidays.isPublicHoliday(date);
    }

    /**
     * Wochenende = Samstag oder Sonntag.
     *
     * @param date zu pruefendes Datum, nicht {@code null}
     * @throws NullPointerException wenn {@code date} {@code null} ist
     */
    public static boolean isWeekend(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return WEEKEND.contains(date.getDayOfWeek());
    }
}
