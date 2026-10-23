package tz.go.tirdo.iccp.ntf;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.dir.api.StaffContact;
import tz.go.tirdo.iccp.ntf.api.NotificationRequest;
import tz.go.tirdo.iccp.ntf.api.Notifier;
import tz.go.tirdo.iccp.ntf.api.NtfCategory;
import tz.go.tirdo.iccp.platform.config.IccpProperties;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.platform.web.PageResult;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates in-app notifications and queues external deliveries (FR-NTF-01..04). External messages
 * are sent later by {@link DeliveryDispatcher}, with retries, so a slow mail server never delays
 * the business transaction.
 */
@Service
public class NotificationService implements Notifier {

    private final JdbcClient jdbc;
    private final DirectoryApi directory;
    private final TemplateService templates;
    private final PreferenceService preferences;
    private final ObjectMapper mapper;
    private final IccpProperties props;

    public NotificationService(JdbcClient jdbc, DirectoryApi directory, TemplateService templates, PreferenceService preferences,
                               ObjectMapper mapper, IccpProperties props) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.templates = templates;
        this.preferences = preferences;
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void notify(NotificationRequest r) {
        if (r.recipients() == null || r.recipients().isEmpty()) {
            return;
        }
        List<StaffContact> contacts = directory.contacts(new LinkedHashSet<>(r.recipients())).stream().filter(StaffContact::active).toList();
        if (contacts.isEmpty()) {
            return;
        }
        boolean mandatory = NtfCategory.isMandatory(r.category());
        List<Long> ids = contacts.stream().map(StaffContact::staffId).toList();
        Map<Long, Boolean> emailOn = preferences.enabled(ids, r.category(), "EMAIL");
        Map<Long, Boolean> smsOn = preferences.enabled(ids, r.category(), "SMS");
        for (StaffContact c : contacts) {
            Map<String, String> params = new HashMap<>(r.params() == null ? Map.of() : r.params());
            params.putIfAbsent("link", props.app().publicUrl() + r.deepLink());
            params.putIfAbsent("name", c.displayName());
            String title = templates.render(r.eventType(), "IN_APP", c.language(), params).map(TemplateService.Rendered::body)
                    .orElse(params.getOrDefault("title", r.eventType()));
            long id = jdbc.sql("""
                    INSERT INTO ntf.notification (recipient_staff_id, event_type, category, mandatory, title, deep_link, source_module,
                                                  source_public_id, params)
                    VALUES (:s, :e, :c, :m, :t, :l, :mod, :src, CAST(:p AS jsonb)) RETURNING id
                    """).param("s", c.staffId()).param("e", r.eventType()).param("c", r.category()).param("m", mandatory)
                    .param("t", truncate(title, 300)).param("l", r.deepLink()).param("mod", r.sourceModule()).param("src", r.sourcePublicId())
                    .param("p", json(params)).query(Long.class).single();
            if (r.channels().contains(NotificationRequest.Channel.EMAIL) && emailOn.getOrDefault(c.staffId(), true)
                    && c.email() != null && templates.exists(r.eventType(), "EMAIL")) {
                queue(id, "EMAIL");
            }
            if (r.channels().contains(NotificationRequest.Channel.SMS) && smsOn.getOrDefault(c.staffId(), false)
                    && c.mobilePhone() != null && templates.exists(r.eventType(), "SMS")) {
                queue(id, "SMS");
            }
        }
    }

    @Override
    public long countSent(long staffId, String eventType, UUID sourcePublicId) {
        return jdbc.sql("""
                SELECT count(*) FROM ntf.notification WHERE recipient_staff_id = :s AND event_type = :e AND source_public_id = :src
                """).param("s", staffId).param("e", eventType).param("src", sourcePublicId).query(Long.class).single();
    }

    private void queue(long notificationId, String channel) {
        jdbc.sql("INSERT INTO ntf.delivery_attempt (notification_id, channel, attempt_no, status) VALUES (:n, :c, 1, 'QUEUED')")
                .param("n", notificationId).param("c", channel).update();
    }

    // ---------------------------------------------------------------- inbox (SCR-NTF-01)

    public record InboxItem(UUID publicId, String eventType, String category, boolean mandatory, String title, String deepLink,
                            String sourceModule, OffsetDateTime createdAt, OffsetDateTime readAt) {
    }

    @Transactional(readOnly = true)
    public PageResult<InboxItem> inbox(long staffId, boolean unreadOnly, Integer page, Integer size) {
        int p = PageResult.clampPage(page), sz = PageResult.clampSize(size);
        String where = " WHERE recipient_staff_id = :s" + (unreadOnly ? " AND read_at IS NULL" : "");
        long total = jdbc.sql("SELECT count(*) FROM ntf.notification" + where).param("s", staffId).query(Long.class).single();
        List<InboxItem> items = jdbc.sql("""
                SELECT public_id, event_type, category, mandatory, title, deep_link, source_module, created_at, read_at
                  FROM ntf.notification""" + where + " ORDER BY created_at DESC, id DESC LIMIT :lim OFFSET :off")
                .param("s", staffId).param("lim", sz).param("off", p * sz).query(InboxItem.class).list();
        return new PageResult<>(items, total, p, sz);
    }

    @Transactional(readOnly = true)
    public long unreadCount(long staffId) {
        return jdbc.sql("SELECT count(*) FROM ntf.notification WHERE recipient_staff_id = :s AND read_at IS NULL")
                .param("s", staffId).query(Long.class).single();
    }

    @Transactional
    public void markRead(long staffId, UUID publicId) {
        int n = jdbc.sql("UPDATE ntf.notification SET read_at = coalesce(read_at, now()) WHERE public_id = :p AND recipient_staff_id = :s")
                .param("p", publicId).param("s", staffId).update();
        if (n == 0) {
            throw ApiException.notFound();
        }
    }

    @Transactional
    public int markAllRead(long staffId) {
        return jdbc.sql("UPDATE ntf.notification SET read_at = now() WHERE recipient_staff_id = :s AND read_at IS NULL").param("s", staffId).update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markReadForSource(long staffId, UUID sourcePublicId) {
        jdbc.sql("UPDATE ntf.notification SET read_at = now() WHERE recipient_staff_id = :s AND source_public_id = :src AND read_at IS NULL")
                .param("s", staffId).param("src", sourcePublicId).update();
    }

    private String json(Map<String, String> params) {
        try {
            return mapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
