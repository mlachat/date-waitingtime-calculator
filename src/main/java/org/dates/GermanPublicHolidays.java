package org.dates;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Liefert die <strong>bundesweit einheitlichen</strong> gesetzlichen Feiertage in Deutschland.
 *
 * <p>Nur die neun bundeseinheitlichen Feiertage werden beruecksichtigt. Feiertage, die
 * lediglich in einzelnen Bundeslaendern gelten (z.&nbsp;B. Heilige Drei Koenige, Fronleichnam,
 * Reformationstag, Allerheiligen), zaehlen bewusst <em>nicht</em> dazu.</p>
 *
 * <p>Die beweglichen Feiertage werden ueber das Osterdatum berechnet
 * (Gauss/Meeus-Algorithmus fuer den gregorianischen Kalender, gueltig ab 1583).</p>
 *
 * <p>Die Klasse ist zustandslos nach aussen, threadsicher und cached die einmal
 * berechneten Jahre intern.</p>
 */
public final class GermanPublicHolidays {

    /** Erstes Jahr des gregorianischen Kalenders, fuer das der Algorithmus gilt. */
    private static final int MIN_GREGORIAN_YEAR = 1583;

    /** Feste (jaehrlich gleiche) bundeseinheitliche Feiertage. */
    private static final Map<MonthDay, String> FIXED_HOLIDAYS = Map.of(
            MonthDay.of(1, 1), "Neujahr",
            MonthDay.of(5, 1), "Tag der Arbeit",
            MonthDay.of(10, 3), "Tag der Deutschen Einheit",
            MonthDay.of(12, 25), "1. Weihnachtstag",
            MonthDay.of(12, 26), "2. Weihnachtstag"
    );

    /** Cache der bereits berechneten Jahre (Jahr -> unveraenderliche Feiertags-Map). */
    private static final Map<Integer, Map<LocalDate, String>> CACHE = new ConcurrentHashMap<>();

    private GermanPublicHolidays() {
        throw new AssertionError("Utility-Klasse - nicht instanziierbar");
    }

    /**
     * Berechnet alle bundeseinheitlichen Feiertage eines Jahres. Ergebnisse werden
     * intern gecacht; wiederholte Aufrufe fuer dasselbe Jahr sind billig.
     *
     * @param year Jahr (gregorianischer Kalender, {@code >= 1583})
     * @return unveraenderliche Map von Datum auf Feiertagsnamen
     * @throws IllegalArgumentException wenn {@code year < 1583}
     */
    public static Map<LocalDate, String> forYear(int year) {
        requireGregorianYear(year);
        return CACHE.computeIfAbsent(year, GermanPublicHolidays::computeForYear);
    }

    /**
     * Alle Feiertagsdaten eines Jahres als unveraenderliche Menge.
     *
     * @param year Jahr (gregorianischer Kalender, {@code >= 1583})
     * @throws IllegalArgumentException wenn {@code year < 1583}
     */
    public static Set<LocalDate> datesForYear(int year) {
        return forYear(year).keySet();
    }

    /**
     * Prueft, ob das gegebene Datum ein bundeseinheitlicher Feiertag ist.
     *
     * @param date zu pruefendes Datum, nicht {@code null}
     * @throws NullPointerException wenn {@code date} {@code null} ist
     */
    public static boolean isPublicHoliday(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return forYear(date.getYear()).containsKey(date);
    }

    /**
     * Berechnet den Ostersonntag eines Jahres nach dem anonymen gregorianischen
     * Algorithmus (auch Meeus/Jones/Butcher-Algorithmus), wie in Jean Meeus,
     * "Astronomical Algorithms", beschrieben.
     *
     * <p>Grundidee: Ostern ist der erste Sonntag nach dem ersten (kirchlichen)
     * Fruehlingsvollmond am oder nach dem 21. Maerz. Der Algorithmus bestimmt
     * dazu erst den Abstand zum Vollmond, dann den Abstand zum naechsten Sonntag.</p>
     *
     * @param year Jahr (gregorianischer Kalender, {@code >= 1583})
     * @throws IllegalArgumentException wenn {@code year < 1583}
     */
    public static LocalDate easterSunday(int year) {
        requireGregorianYear(year);

        // Position im 19-jaehrigen Mondzyklus (Metonischer Zyklus)
        int metonicCycle = year % 19;

        int century = year / 100;
        int yearInCentury = year % 100;

        // Schaltjahr-Korrekturen des gregorianischen Kalenders
        int centuryLeapDays = century / 4;
        int centuryRemainder = century % 4;
        int leapDays = yearInCentury / 4;
        int leapRemainder = yearInCentury % 4;

        // Korrekturen der Mondbahn (Sonnen- und Mondgleichung)
        int solarCorrection = (century + 8) / 25;
        int lunarCorrection = (century - solarCorrection + 1) / 3;

        // Tage vom 21. Maerz bis zum kirchlichen Fruehlingsvollmond (0..29)
        int daysUntilFullMoon =
                (19 * metonicCycle + century - centuryLeapDays - lunarCorrection + 15) % 30;

        // Tage vom Vollmond bis zum naechsten Sonntag (0..6)
        int daysUntilSunday =
                (32 + 2 * centuryRemainder + 2 * leapDays - daysUntilFullMoon - leapRemainder) % 7;

        // Seltene Sonderfall-Korrektur: Ostern faellt nie auf den 26. April
        int lateEasterCorrection =
                (metonicCycle + 11 * daysUntilFullMoon + 22 * daysUntilSunday) / 451;

        // Ostersonntag = 22. Maerz (fruehestmoegliches Datum) + Tage bis Vollmond
        // + Tage bis Sonntag (- Korrektur)
        int daysAfterMarch22 = daysUntilFullMoon + daysUntilSunday - 7 * lateEasterCorrection;
        return LocalDate.of(year, 3, 22).plusDays(daysAfterMarch22);
    }

    private static Map<LocalDate, String> computeForYear(int year) {
        Map<LocalDate, String> holidays = new HashMap<>();

        FIXED_HOLIDAYS.forEach((monthDay, name) -> holidays.put(monthDay.atYear(year), name));

        LocalDate easterSunday = easterSunday(year);
        holidays.put(easterSunday.minusDays(2), "Karfreitag");
        holidays.put(easterSunday.plusDays(1), "Ostermontag");
        holidays.put(easterSunday.plusDays(39), "Christi Himmelfahrt");
        holidays.put(easterSunday.plusDays(50), "Pfingstmontag");

        return Map.copyOf(holidays);
    }

    private static void requireGregorianYear(int year) {
        if (year < MIN_GREGORIAN_YEAR) {
            throw new IllegalArgumentException(
                    "year muss >= " + MIN_GREGORIAN_YEAR + " sein (gregorianischer Kalender): " + year);
        }
    }
}
