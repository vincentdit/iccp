package tz.go.tirdo.iccp.ntf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import tz.go.tirdo.iccp.platform.config.IccpProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.dir.api.StaffContact;
import tz.go.tirdo.iccp.ntf.api.NtfCategory;
import tz.go.tirdo.iccp.ntf.channel.EmailSender;
import tz.go.tirdo.iccp.ntf.channel.SmsGateway;
import tz.go.tirdo.iccp.platform.db.DbSessionContext;
import tz.go.tirdo.iccp.platform.time.WorkingDays;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sends queued email and SMS deliveries (FR-NTF-05/06). Retries failures after 1, 5, 15 and 60
 * minutes, then moves the delivery to DEAD_LETTER for ICTT review. Holds SMS during the person's
 * quiet hours except for emergency alerts. Only one node dispatches at a time (advisory lock).
 * Every attempt is a new append-only row in ntf.delivery_attempt.
 */
@Component
public class DeliveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TemplateService templates;
    private final PreferenceService preferences;
    private final DirectoryApi directory;
    private final EmailSender email;
    private final SmsGateway sms;
    private final ObjectMapper mapper;
    private final WorkingDays calendar;
    private final int smsBatchSize;

    public DeliveryDispatcher(JdbcClient jdbc, TransactionTemplate tx, TemplateService templates, PreferenceService preferences,
                              DirectoryApi directory, EmailSender email, SmsGateway sms, ObjectMapper mapper, WorkingDays calendar, IccpProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.templates = templates;
        this.preferences = preferences;
        this.directory = directory;
        this.email = email;
        this.sms = sms;
        this.mapper = mapper;
        this.calendar = calendar;
        this.smsBatchSize = props.notifications().smsBatchSize();
    }

    record Due(long attemptId, long notificationId, String channel, int attemptNo, String status, long recipientStaffId,
               String eventType, String category, String params) {
    }

    @Scheduled(fixedDelayString = "${iccp.notifications.dispatch-ms:10000}")
    public void dispatch() {
        DbSessionContext.runAs(DbSessionContext.system("ntf-dispatch"), () -> tx.executeWithoutResult(s -> {
            boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('ntf.delivery_dispatch'))").query(Boolean.class).single();
            if (!locked) {
                return;
            }
            List<Due> due = jdbc.sql("""
                    WITH latest AS (
                        SELECT DISTINCT ON (notification_id, channel) id, notification_id, channel, attempt_no, status, created_at
                          FROM ntf.delivery_attempt
                         WHERE created_at > now() - interval '3 days'
                         ORDER BY notification_id, channel, id DESC)
                    SELECT l.id AS attempt_id, l.notification_id, l.channel, l.attempt_no, l.status, n.recipient_staff_id,
                           n.event_type, n.category, n.params::text AS params
                      FROM latest l JOIN ntf.notification n ON n.id = l.notification_id
                     WHERE l.status = 'QUEUED'
                        OR (l.status = 'FAILED' AND l.attempt_no < 5 AND l.created_at < now() - CASE l.attempt_no
                               WHEN 1 THEN interval '1 minute' WHEN 2 THEN interval '5 minutes'
                               WHEN 3 THEN interval '15 minutes' ELSE interval '60 minutes' END)
                     ORDER BY l.id
                     LIMIT 200
                    """).query(Due.class).list();
            int smsCount = 0;
            for (Due d : due) {
                if ("SMS".equals(d.channel()) && smsCount >= smsBatchSize) {
                    continue;
                }
                deliver(d);
                if ("SMS".equals(d.channel())) smsCount++;
            }
        }));
    }

    private void deliver(Due d) {
        int attempt = "QUEUED".equals(d.status()) ? d.attemptNo() : d.attemptNo() + 1;
        Optional<StaffContact> contact = directory.contacts(List.of(d.recipientStaffId())).stream().findFirst();
        if (contact.isEmpty() || !contact.get().active()) {
            record(d, attempt, "DEAD_LETTER", null, "recipient no longer active");
            return;
        }
        StaffContact c = contact.get();
        if ("SMS".equals(d.channel()) && !NtfCategory.EMERGENCY.equals(d.category())) {
            LocalTime now = ZonedDateTime.now(calendar.zone()).toLocalTime();
            if (preferences.quietHours(c.staffId()).contains(now)) {
                return; // stays QUEUED until quiet hours end (FR-NTF-05)
            }
        }
        Map<String, String> params = readParams(d.params());
        Optional<TemplateService.Rendered> msg = templates.render(d.eventType(), d.channel(), c.language(), params);
        if (msg.isEmpty()) {
            record(d, attempt, "DEAD_LETTER", null, "no published template");
            return;
        }
        try {
            String ref = "EMAIL".equals(d.channel())
                    ? email.send(c.email(), msg.get().subject() == null ? "TIRDO ICCP" : msg.get().subject(), msg.get().body())
                    : sms.send(c.mobilePhone(), msg.get().body(), "iccp-notification-" + d.notificationId());
            record(d, attempt, "SENT", ref, null);
        } catch (Exception e) {
            log.warn("Delivery {} {} attempt {} failed: {}", d.notificationId(), d.channel(), attempt, e.getMessage());
            record(d, attempt, attempt >= MAX_ATTEMPTS ? "DEAD_LETTER" : "FAILED", null, String.valueOf(e.getMessage()));
        }
    }

    private void record(Due d, int attemptNo, String status, String providerRef, String error) {
        jdbc.sql("""
                INSERT INTO ntf.delivery_attempt (notification_id, channel, attempt_no, status, provider_ref, error)
                VALUES (:n, :c, :a, :s, :ref, :err)
                """).param("n", d.notificationId()).param("c", d.channel()).param("a", Math.min(attemptNo, MAX_ATTEMPTS))
                .param("s", status).param("ref", providerRef).param("err", error == null ? null : error.length() > 500 ? error.substring(0, 500) : error)
                .update();
    }

    private Map<String, String> readParams(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
