package tz.go.tirdo.iccp.ntf;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.web.ApiException;

import java.time.OffsetDateTime;
import java.util.List;

/** Operational delivery metrics for ICTT (FR-NTF-10). */
@Service
public class NotificationOperationsService {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public NotificationOperationsService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    record Latest(String status) {}

    public record Count(String channel, String status, long count) {}
    public record DeadLetter(long notificationId, String channel, int attemptNo, String error, OffsetDateTime createdAt) {}
    public record Summary(List<Count> last24Hours, long smsToday, long digestsToday, List<DeadLetter> deadLetters) {}

    @Transactional(readOnly = true)
    public Summary summary() {
        List<Count> counts = jdbc.sql("""
                SELECT channel, status, count(*) AS count FROM ntf.delivery_attempt
                 WHERE created_at >= now() - interval '24 hours' GROUP BY channel, status ORDER BY channel, status
                """).query(Count.class).list();
        long sms = jdbc.sql("SELECT count(DISTINCT notification_id) FROM ntf.delivery_attempt WHERE channel = 'SMS' AND status IN ('SENT','DELIVERED') AND created_at >= current_date")
                .query(Long.class).single();
        long digests = jdbc.sql("SELECT count(*) FROM ntf.email_digest_delivery WHERE status = 'SENT' AND created_at >= current_date")
                .query(Long.class).single();
        List<DeadLetter> dead = jdbc.sql("""
                WITH latest AS (
                    SELECT DISTINCT ON (notification_id, channel) notification_id, channel, attempt_no, status, error, created_at
                      FROM ntf.delivery_attempt ORDER BY notification_id, channel, id DESC)
                SELECT notification_id, channel, attempt_no, error, created_at FROM latest
                 WHERE status = 'DEAD_LETTER' ORDER BY created_at DESC LIMIT 50
                """).query(DeadLetter.class).list();
        return new Summary(counts, sms, digests, dead);
    }

    @Transactional
    public void retry(long notificationId, String channel) {
        if (!"EMAIL".equals(channel) && !"SMS".equals(channel)) throw ApiException.badRequest("ICCP-NTF-400", "Unsupported delivery channel");
        Latest latest = jdbc.sql("SELECT status FROM ntf.delivery_attempt WHERE notification_id = :n AND channel = :c ORDER BY id DESC LIMIT 1")
                .param("n", notificationId).param("c", channel).query(Latest.class).optional().orElseThrow(ApiException::notFound);
        if (!"DEAD_LETTER".equals(latest.status())) throw ApiException.conflict("ICCP-NTF-409", "This delivery no longer needs recovery");
        jdbc.sql("INSERT INTO ntf.delivery_attempt (notification_id, channel, attempt_no, status) VALUES (:n, :c, 1, 'QUEUED')")
                .param("n", notificationId).param("c", channel).update();
        audit.record("NTF", "DELIVERY_REQUEUE", "notification", notificationId, java.util.Map.of("channel", channel));
    }
}
