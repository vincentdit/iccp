package tz.go.tirdo.iccp.ntf;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Operational delivery metrics for ICTT (FR-NTF-10). */
@Service
public class NotificationOperationsService {
    private final JdbcClient jdbc;

    public NotificationOperationsService(JdbcClient jdbc) { this.jdbc = jdbc; }

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
                SELECT notification_id, channel, attempt_no, error, created_at FROM ntf.delivery_attempt
                 WHERE status = 'DEAD_LETTER' ORDER BY created_at DESC LIMIT 50
                """).query(DeadLetter.class).list();
        return new Summary(counts, sms, digests, dead);
    }
}
