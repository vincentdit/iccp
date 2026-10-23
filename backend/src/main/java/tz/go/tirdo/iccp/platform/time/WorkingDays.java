package tz.go.tirdo.iccp.platform.time;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tz.go.tirdo.iccp.platform.config.IccpProperties;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Working-day calendar shared by every SLA and escalation timer (XFR-09): Monday to Friday,
 * excluding public holidays maintained in wf.holiday. Times are in East Africa Time.
 */
@Component
public class WorkingDays {

    private final JdbcClient jdbc;
    private final ZoneId zone;
    private volatile Set<LocalDate> holidays;
    private volatile ZonedDateTime loadedAt;

    public WorkingDays(JdbcClient jdbc, IccpProperties props) {
        this.jdbc = jdbc;
        this.zone = ZoneId.of(props.notifications().timeZone());
    }

    public ZoneId zone() {
        return zone;
    }

    /** Adds {@code days} working days to {@code from}, keeping the time of day. */
    public OffsetDateTime plus(OffsetDateTime from, int days) {
        return plus(from, days, isHoliday());
    }

    public boolean isWorkingDay(LocalDate date) {
        return isWorkingDay(date, isHoliday());
    }

    static OffsetDateTime plus(OffsetDateTime from, int days, Predicate<LocalDate> holiday) {
        OffsetDateTime t = from;
        int added = 0;
        while (added < days) {
            t = t.plusDays(1);
            if (isWorkingDay(t.toLocalDate(), holiday)) {
                added++;
            }
        }
        return t;
    }

    static boolean isWorkingDay(LocalDate d, Predicate<LocalDate> holiday) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holiday.test(d);
    }

    private Predicate<LocalDate> isHoliday() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (holidays == null || loadedAt == null || Duration.between(loadedAt, now).toMinutes() > 60) {
            holidays = new HashSet<>(jdbc.sql("SELECT holiday_date FROM wf.holiday").query(LocalDate.class).list());
            loadedAt = now;
        }
        Set<LocalDate> h = holidays;
        return h::contains;
    }
}
