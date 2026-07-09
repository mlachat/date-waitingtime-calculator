package org.dates;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GermanPublicHolidays – bundeseinheitliche Feiertage")
class GermanPublicHolidaysTest {

    @Nested
    @DisplayName("Osterberechnung")
    class Easter {

        @ParameterizedTest(name = "Ostersonntag {0} = {1}")
        @CsvSource({
                "2020, 2020-04-12",
                "2021, 2021-04-04",
                "2022, 2022-04-17",
                "2023, 2023-04-09",
                "2024, 2024-03-31",
                "2025, 2025-04-20",
                "2026, 2026-04-05",
                "2027, 2027-03-28",
                "2030, 2030-04-21"
        })
        void berechnetOstersonntagKorrekt(int year, String expected) {
            assertEquals(LocalDate.parse(expected), GermanPublicHolidays.easterSunday(year));
        }
    }

    @Nested
    @DisplayName("Feste Feiertage")
    class Fixed {

        @ParameterizedTest(name = "{0} ist Feiertag")
        @CsvSource({
                "2025-01-01",  // Neujahr
                "2025-05-01",  // Tag der Arbeit
                "2025-10-03",  // Tag der Deutschen Einheit
                "2025-12-25",  // 1. Weihnachtstag
                "2025-12-26"   // 2. Weihnachtstag
        })
        void festeFeiertageWerdenErkannt(String date) {
            assertTrue(GermanPublicHolidays.isPublicHoliday(LocalDate.parse(date)));
        }
    }

    @Nested
    @DisplayName("Bewegliche Feiertage (Ostern 2025)")
    class Movable {

        @ParameterizedTest(name = "{0} ({1}) ist Feiertag")
        @CsvSource({
                "2025-04-18, Karfreitag",
                "2025-04-21, Ostermontag",
                "2025-05-29, Christi Himmelfahrt",
                "2025-06-09, Pfingstmontag"
        })
        void beweglicheFeiertageWerdenErkannt(String date, String name) {
            Map<LocalDate, String> holidays = GermanPublicHolidays.forYear(2025);
            assertEquals(name, holidays.get(LocalDate.parse(date)));
        }
    }

    @Nested
    @DisplayName("Nicht bundeseinheitliche Feiertage zaehlen NICHT")
    class NotNationwide {

        @ParameterizedTest(name = "{0} ist KEIN bundesweiter Feiertag")
        @CsvSource({
                "2025-01-06",  // Heilige Drei Koenige (nur einige Laender)
                "2025-06-19",  // Fronleichnam (nur einige Laender)
                "2025-08-15",  // Mariae Himmelfahrt (nur teilweise)
                "2025-10-31",  // Reformationstag (nur einige Laender)
                "2025-11-01",  // Allerheiligen (nur einige Laender)
                "2025-11-19",  // Buss- und Bettag (nur Sachsen)
                "2025-04-20",  // Ostersonntag (kein gesetzlicher Feiertag)
                "2025-06-08"   // Pfingstsonntag (kein gesetzlicher Feiertag)
        })
        void landesFeiertageSindKeineBundesweitenFeiertage(String date) {
            assertFalse(GermanPublicHolidays.isPublicHoliday(LocalDate.parse(date)));
        }
    }

    @Test
    @DisplayName("Ein Jahr hat genau 9 bundeseinheitliche Feiertage")
    void neunFeiertageProJahr() {
        assertEquals(9, GermanPublicHolidays.forYear(2025).size());
        assertEquals(9, GermanPublicHolidays.forYear(2024).size());
    }

    @Nested
    @DisplayName("Validierung & Immutability")
    class Validation {

        @Test
        @DisplayName("Jahre vor 1583 (vor gregorianischem Kalender) werden abgelehnt")
        void jahrVorGregorianischemKalender() {
            assertThrows(IllegalArgumentException.class, () -> GermanPublicHolidays.forYear(1582));
            assertThrows(IllegalArgumentException.class, () -> GermanPublicHolidays.easterSunday(1500));
        }

        @Test
        @DisplayName("null-Datum wirft NullPointerException")
        void nullDatum() {
            assertThrows(NullPointerException.class, () -> GermanPublicHolidays.isPublicHoliday(null));
        }

        @Test
        @DisplayName("Zurueckgegebene Map ist unveraenderlich")
        void mapIstUnveraenderlich() {
            Map<LocalDate, String> holidays = GermanPublicHolidays.forYear(2025);
            assertThrows(UnsupportedOperationException.class,
                    () -> holidays.put(LocalDate.parse("2025-01-06"), "Heilige Drei Koenige"));
        }

        @Test
        @DisplayName("Wiederholte Aufrufe liefern dieselbe (gecachte) Instanz")
        void cacheLiefertGleicheInstanz() {
            assertSame(GermanPublicHolidays.forYear(2025), GermanPublicHolidays.forYear(2025));
        }
    }
}
