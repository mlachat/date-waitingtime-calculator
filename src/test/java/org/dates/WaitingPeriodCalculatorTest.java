package org.dates;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WaitingPeriodCalculator – Wartezeit in Werktagen")
class WaitingPeriodCalculatorTest {

    private static final ZoneId ZONE = WaitingPeriodCalculator.ZONE;

    /** Timestamp fuer den Tagesbeginn (00:00 Europe/Berlin) eines Datums. */
    private static Timestamp timestampAtStartOfDay(String isoDate) {
        return Timestamp.from(LocalDate.parse(isoDate).atStartOfDay(ZONE).toInstant());
    }

    /** Fixierte Uhr, die "jetzt" auf 12:00 des angegebenen Datums setzt (Europe/Berlin). */
    private static Clock clockAt(String isoDate) {
        ZonedDateTime now = LocalDate.parse(isoDate).atTime(LocalTime.NOON).atZone(ZONE);
        return Clock.fixed(now.toInstant(), ZONE);
    }

    private static WaitingPeriodCalculator calcAt(String isoDate) {
        return new WaitingPeriodCalculator(clockAt(isoDate));
    }

    @Nested
    @DisplayName("addBusinessDays – Werktage aufaddieren")
    class AddBusinessDays {


        @Test
        @DisplayName("Innerhalb einer Arbeitswoche ohne Feiertage")
        void innerhalbDerArbeitswoche() {
            // Montag 2025-04-28 + 3 Werktage -> Do 2025-05-01 waere Feiertag,
            // daher wird hier bewusst eine feiertagsfreie Woche gewaehlt:
            // Mo 2025-06-02 + 3 -> Di, Mi, Do = 2025-06-05
            assertEquals(LocalDate.parse("2025-06-05"),
                    WaitingPeriodCalculator.addBusinessDays(LocalDate.parse("2025-06-02"), 3));
        }

        @Test
        @DisplayName("Wochenende wird uebersprungen")
        void wochenendeWirdUebersprungen() {
            // Freitag 2025-04-25 + 3 Werktage: Sa/So uebersprungen ->
            // Mo(1), Di(2), Mi(3) = 2025-04-30
            assertEquals(LocalDate.parse("2025-04-30"),
                    WaitingPeriodCalculator.addBusinessDays(LocalDate.parse("2025-04-25"), 3));
        }

        @Test
        @DisplayName("Einzelner Feiertag mitten in der Woche wird uebersprungen")
        void feiertagWirdUebersprungen() {
            // Mi 2025-04-30 + 2 Werktage: Do 2025-05-01 (Tag der Arbeit) uebersprungen ->
            // Fr(1) 2025-05-02, Mo(2) 2025-05-05
            assertEquals(LocalDate.parse("2025-05-05"),
                    WaitingPeriodCalculator.addBusinessDays(LocalDate.parse("2025-04-30"), 2));
        }

        @Test
        @DisplayName("Mehrere Feiertage + Wochenende (Weihnachten 2025)")
        void weihnachtenUndWochenende() {
            // Start Mi 2025-12-24, + 1 Werktag:
            // Do 25.12 (Feiertag), Fr 26.12 (Feiertag), Sa 27, So 28 -> Mo 29.12 = 1. Werktag
            assertEquals(LocalDate.parse("2025-12-29"),
                    WaitingPeriodCalculator.addBusinessDays(LocalDate.parse("2025-12-24"), 1));
        }

        @Test
        @DisplayName("Osterwoche 2025: Karfreitag + Ostermontag werden uebersprungen")
        void osterwoche() {
            // Start Do 2025-04-17, + 1 Werktag:
            // Fr 18.04 Karfreitag, Sa 19, So 20, Mo 21 Ostermontag -> Di 22.04 = 1. Werktag
            assertEquals(LocalDate.parse("2025-04-22"),
                    WaitingPeriodCalculator.addBusinessDays(LocalDate.parse("2025-04-17"), 1));
        }

        @Test
        @DisplayName("Offset 0 liefert das Startdatum zurueck")
        void offsetNullLiefertStartdatum() {
            LocalDate start = LocalDate.parse("2025-06-02");
            assertEquals(start, WaitingPeriodCalculator.addBusinessDays(start, 0));
        }

        @Test
        @DisplayName("Negativer Offset ist nicht erlaubt")
        void negativerOffsetWirftException() {
            assertThrows(IllegalArgumentException.class,
                    () -> WaitingPeriodCalculator.addBusinessDays(LocalDate.parse("2025-06-02"), -1));
        }
    }

    @Nested
    @DisplayName("isWaitingPeriodExceeded – Grenzverhalten")
    class Boundary {

        @Test
        @DisplayName("Am Faelligkeitstag selbst ist die Wartezeit NICHT ueberschritten")
        void amFaelligkeitstagNichtUeberschritten() {
            // Start Mo 2025-06-02, Offset 3 -> Faelligkeit Do 2025-06-05
            Timestamp start = timestampAtStartOfDay("2025-06-02");
            WaitingPeriodCalculator calc = calcAt("2025-06-05");
            assertFalse(calc.isWaitingPeriodExceeded(start, 3));
        }

        @Test
        @DisplayName("Einen Tag nach Faelligkeit ist die Wartezeit ueberschritten")
        void tagNachFaelligkeitUeberschritten() {
            Timestamp start = timestampAtStartOfDay("2025-06-02");
            WaitingPeriodCalculator calc = calcAt("2025-06-06");
            assertTrue(calc.isWaitingPeriodExceeded(start, 3));
        }

        @Test
        @DisplayName("Vor Faelligkeit ist die Wartezeit nicht ueberschritten")
        void vorFaelligkeitNichtUeberschritten() {
            Timestamp start = timestampAtStartOfDay("2025-06-02");
            WaitingPeriodCalculator calc = calcAt("2025-06-04");
            assertFalse(calc.isWaitingPeriodExceeded(start, 3));
        }
    }

    @Nested
    @DisplayName("Kernszenario: Offset 3 Tage, Feiertage/Wochenenden zaehlen nicht")
    class CoreScenario {

        @Test
        @DisplayName("3 Werktage mit Feiertag (Tag der Arbeit) dazwischen verschieben die Faelligkeit")
        void dreiWerktageMitFeiertag() {
            // Start Mo 2025-04-28, Offset 3:
            // Di 29(1), Mi 30(2), Do 01.05 Feiertag uebersprungen, Fr 02.05(3) -> Faelligkeit Fr 02.05
            Timestamp start = timestampAtStartOfDay("2025-04-28");

            // Am 02.05 (Faelligkeit) noch nicht ueberschritten
            assertFalse(calcAt("2025-05-02").isWaitingPeriodExceeded(start, 3),
                    "Am Faelligkeitstag darf noch nicht ueberschritten sein");

            // Ohne Feiertagsberuecksichtigung waere schon der 01.05 ueberschritten gewesen –
            // wegen des Feiertags ist am 01.05 noch NICHT ueberschritten.
            assertFalse(calcAt("2025-05-01").isWaitingPeriodExceeded(start, 3),
                    "Feiertag darf nicht als abgelaufener Werktag zaehlen");

            // Erst ab dem 03.05 ist die Wartezeit ueberschritten
            assertTrue(calcAt("2025-05-03").isWaitingPeriodExceeded(start, 3));
        }

        @Test
        @DisplayName("3 Werktage ueber ein Wochenende verschieben die Faelligkeit")
        void dreiWerktageUeberWochenende() {
            // Start Fr 2025-04-25, Offset 3: Sa/So uebersprungen -> Mo(1),Di(2),Mi(3)=30.04
            Timestamp start = timestampAtStartOfDay("2025-04-25");

            // Am Wochenende (So 27.04) noch lange nicht ueberschritten
            assertFalse(calcAt("2025-04-27").isWaitingPeriodExceeded(start, 3));
            // Am Faelligkeitstag Mi 30.04 noch nicht ueberschritten
            assertFalse(calcAt("2025-04-30").isWaitingPeriodExceeded(start, 3));
            // Do 01.05 (obwohl Feiertag) liegt NACH der Faelligkeit -> ueberschritten
            assertTrue(calcAt("2025-05-01").isWaitingPeriodExceeded(start, 3));
        }

        @Test
        @DisplayName("Vergleich: gleiche Kalenderspanne, aber ohne Feiertag laeuft Wartezeit frueher ab")
        void feiertagVerlaengertWartezeitImVergleich() {
            // Feiertagswoche: Start Mo 28.04.2025 (mit Tag der Arbeit am Do)
            Timestamp mitFeiertag = timestampAtStartOfDay("2025-04-28");
            // Normale Woche: Start Mo 02.06.2025 (keine Feiertage)
            Timestamp ohneFeiertag = timestampAtStartOfDay("2025-06-02");

            // Ohne Feiertag: Faelligkeit Do 05.06; mit Feiertag: Faelligkeit Fr 02.05 (statt Do 01.05)
            assertEquals(LocalDate.parse("2025-06-05"),
                    WaitingPeriodCalculator.addBusinessDays(LocalDate.parse("2025-06-02"), 3));
            assertEquals(LocalDate.parse("2025-05-02"),
                    WaitingPeriodCalculator.addBusinessDays(LocalDate.parse("2025-04-28"), 3));
        }
    }

    @Nested
    @DisplayName("Eingaben & Randfaelle")
    class Inputs {

        @Test
        @DisplayName("Offset 0: nur ueberschritten, wenn heute nach dem Starttag liegt")
        void offsetNull() {
            Timestamp start = timestampAtStartOfDay("2025-06-02");
            assertFalse(calcAt("2025-06-02").isWaitingPeriodExceeded(start, 0));
            assertTrue(calcAt("2025-06-03").isWaitingPeriodExceeded(start, 0));
        }

        @ParameterizedTest(name = "Offset {0} bleibt konsistent (idempotent bzgl. Feiertagen)")
        @ValueSource(ints = {1, 5, 10, 20})
        void groessereOffsetsLiefernWerktageAlsFaelligkeit(int offset) {
            LocalDate due = WaitingPeriodCalculator.addBusinessDays(LocalDate.parse("2025-01-02"), offset);
            assertTrue(WaitingPeriodCalculator.isBusinessDay(due),
                    "Faelligkeitsdatum muss selbst ein Werktag sein");
        }

        @Test
        @DisplayName("Negativer Offset wirft Exception")
        void negativerOffset() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WaitingPeriodCalculator().isWaitingPeriodExceeded(
                            timestampAtStartOfDay("2025-06-02"), -1));
        }

        @Test
        @DisplayName("null-Clock im Konstruktor wirft NullPointerException")
        void nullClock() {
            assertThrows(NullPointerException.class, () -> new WaitingPeriodCalculator(null));
        }

        @Test
        @DisplayName("null-Startdatum wirft NullPointerException")
        void nullStartdatum() {
            assertThrows(NullPointerException.class,
                    () -> WaitingPeriodCalculator.addBusinessDays(null, 3));
        }

        @Test
        @DisplayName("null-Timestamp wirft NullPointerException")
        void nullTimestamp() {
            assertThrows(NullPointerException.class,
                    () -> new WaitingPeriodCalculator().isWaitingPeriodExceeded(null, 3));
        }
    }

    @Nested
    @DisplayName("Hilfsmethoden")
    class Helpers {

        @Test
        @DisplayName("Samstag und Sonntag sind Wochenende")
        void wochenende() {
            assertTrue(WaitingPeriodCalculator.isWeekend(LocalDate.parse("2025-04-26"))); // Sa
            assertTrue(WaitingPeriodCalculator.isWeekend(LocalDate.parse("2025-04-27"))); // So
            assertFalse(WaitingPeriodCalculator.isWeekend(LocalDate.parse("2025-04-28"))); // Mo
        }

        @Test
        @DisplayName("Feiertag ist kein Werktag")
        void feiertagKeinWerktag() {
            assertFalse(WaitingPeriodCalculator.isBusinessDay(LocalDate.parse("2025-05-01"))); // Tag der Arbeit
            assertTrue(WaitingPeriodCalculator.isBusinessDay(LocalDate.parse("2025-05-02")));  // normaler Freitag
        }
    }
}
