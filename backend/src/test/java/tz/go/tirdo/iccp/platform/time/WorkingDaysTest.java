package tz.go.tirdo.iccp.platform.time;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkingDaysTest {

    private static final ZoneOffset EAT = ZoneOffset.ofHours(3);

    @Test
    void skipsWeekends() {
        OffsetDateTime friday = OffsetDateTime.of(2026, 10, 23, 10, 0, 0, 0, EAT);
        assertEquals(OffsetDateTime.of(2026, 10, 28, 10, 0, 0, 0, EAT), WorkingDays.plus(friday, 3, d -> false));
    }

    @Test
    void skipsPublicHolidays() {
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 12, 9), LocalDate.of(2026, 12, 25));
        OffsetDateTime tuesday = OffsetDateTime.of(2026, 12, 8, 9, 0, 0, 0, EAT);
        assertEquals(LocalDate.of(2026, 12, 11), WorkingDays.plus(tuesday, 2, holidays::contains).toLocalDate());
        assertFalse(WorkingDays.isWorkingDay(LocalDate.of(2026, 12, 25), holidays::contains));
    }
}
