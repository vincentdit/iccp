package tz.go.tirdo.iccp.ntf;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.ntf.api.NtfCategory;
import tz.go.tirdo.iccp.platform.audit.AuditService;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notification preferences (FR-NTF-04/05). Defaults: email on for every category; SMS on only for
 * emergency alerts and finance updates (SMS costs money). Mandatory categories ignore the
 * switches and are delivered anyway.
 */
@Service
public class PreferenceService {

    private static final Set<String> SMS_DEFAULT_ON = Set.of(NtfCategory.EMERGENCY, NtfCategory.FINANCE);

    private final JdbcClient jdbc;
    private final AuditService audit;

    public PreferenceService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record CategoryPref(String category, boolean mandatory, boolean email, boolean sms) {
    }

    public record Preferences(List<CategoryPref> categories, LocalTime quietStart, LocalTime quietEnd, boolean emailDigest) {
    }

    record Row(long staffId, String category, String channel, boolean enabled) {
    }

    public boolean defaultEnabled(String category, String channel) {
        return "EMAIL".equals(channel) || ("SMS".equals(channel) && SMS_DEFAULT_ON.contains(category));
    }

    /** Effective switch per staff for one category and channel. */
    public Map<Long, Boolean> enabled(Collection<Long> staffIds, String category, String channel) {
        Map<Long, Boolean> out = new HashMap<>();
        boolean def = defaultEnabled(category, channel);
        staffIds.forEach(id -> out.put(id, def || NtfCategory.isMandatory(category)));
        if (NtfCategory.isMandatory(category) || staffIds.isEmpty()) {
            return out;
        }
        List<Long> ids = new ArrayList<>(staffIds);
        for (int i = 0; i < ids.size(); i += 1000) {
            jdbc.sql("SELECT staff_id, category, channel, enabled FROM ntf.user_preference WHERE staff_id IN (:ids) AND category = :c AND channel = :ch")
                    .param("ids", ids.subList(i, Math.min(i + 1000, ids.size()))).param("c", category).param("ch", channel)
                    .query(Row.class).list().forEach(r -> out.put(r.staffId(), r.enabled()));
        }
        return out;
    }

    public record Quiet(LocalTime quietStart, LocalTime quietEnd) {
        public boolean contains(LocalTime t) {
            return quietStart.isAfter(quietEnd) ? !t.isBefore(quietStart) || t.isBefore(quietEnd) : !t.isBefore(quietStart) && t.isBefore(quietEnd);
        }
    }

    public Quiet quietHours(long staffId) {
        return jdbc.sql("SELECT quiet_start, quiet_end FROM ntf.preference_setting WHERE staff_id = :s")
                .param("s", staffId).query(Quiet.class).optional().orElse(new Quiet(LocalTime.of(20, 0), LocalTime.of(7, 0)));
    }

    @Transactional(readOnly = true)
    public Preferences get(long staffId) {
        Map<String, Boolean> saved = new HashMap<>();
        jdbc.sql("SELECT staff_id, category, channel, enabled FROM ntf.user_preference WHERE staff_id = :s").param("s", staffId)
                .query(Row.class).list().forEach(r -> saved.put(r.category() + "|" + r.channel(), r.enabled()));
        List<CategoryPref> cats = NtfCategory.ALL.stream().map(c -> {
            boolean m = NtfCategory.isMandatory(c);
            return new CategoryPref(c, m,
                    m || saved.getOrDefault(c + "|EMAIL", defaultEnabled(c, "EMAIL")),
                    m || saved.getOrDefault(c + "|SMS", defaultEnabled(c, "SMS")));
        }).toList();
        Quiet q = quietHours(staffId);
        boolean digest = jdbc.sql("SELECT email_digest FROM ntf.preference_setting WHERE staff_id = :s").param("s", staffId)
                .query(Boolean.class).optional().orElse(false);
        return new Preferences(cats, q.quietStart(), q.quietEnd(), digest);
    }

    @Transactional
    public Preferences save(long staffId, Preferences p) {
        for (CategoryPref c : p.categories()) {
            if (!NtfCategory.ALL.contains(c.category())) {
                continue;
            }
            // FR-NTF-04: mandatory categories cannot be disabled; the request is ignored for them.
            if (NtfCategory.isMandatory(c.category())) {
                continue;
            }
            upsert(staffId, c.category(), "EMAIL", c.email());
            upsert(staffId, c.category(), "SMS", c.sms());
        }
        if (p.quietStart() != null && p.quietEnd() != null) {
            jdbc.sql("""
                    INSERT INTO ntf.preference_setting (staff_id, quiet_start, quiet_end, email_digest) VALUES (:s, :qs, :qe, :d)
                    ON CONFLICT (staff_id) DO UPDATE SET quiet_start = EXCLUDED.quiet_start, quiet_end = EXCLUDED.quiet_end, email_digest = EXCLUDED.email_digest
                    """).param("s", staffId).param("qs", p.quietStart()).param("qe", p.quietEnd()).param("d", p.emailDigest()).update();
        }
        audit.record("NTF", "PREFERENCES_UPDATE", "staff_member", staffId, Map.of());
        return get(staffId);
    }

    private void upsert(long staffId, String category, String channel, boolean enabled) {
        jdbc.sql("""
                INSERT INTO ntf.user_preference (staff_id, category, channel, enabled) VALUES (:s, :c, :ch, :e)
                ON CONFLICT (staff_id, category, channel) DO UPDATE SET enabled = EXCLUDED.enabled
                """).param("s", staffId).param("c", category).param("ch", channel).param("e", enabled).update();
    }
}
