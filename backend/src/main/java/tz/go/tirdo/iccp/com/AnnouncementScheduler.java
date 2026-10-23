package tz.go.tirdo.iccp.com;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.ntf.api.NotificationRequest;
import tz.go.tirdo.iccp.ntf.api.Notifier;
import tz.go.tirdo.iccp.ntf.api.NtfCategory;
import tz.go.tirdo.iccp.platform.db.DbSessionContext;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Time-based COM rules: publish scheduled notices at their effective time (FR-COM-05), archive
 * expired notices (FR-COM-12), and remind / escalate outstanding acknowledgements at 48 h and
 * 96 h (FR-COM-08, BRL-09).
 */
@Component
public class AnnouncementScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementScheduler.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AnnouncementService service;
    private final Notifier notifier;
    private final DirectoryApi directory;

    public AnnouncementScheduler(JdbcClient jdbc, TransactionTemplate tx, AnnouncementService service, Notifier notifier, DirectoryApi directory) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.service = service;
        this.notifier = notifier;
        this.directory = directory;
    }

    @Scheduled(fixedDelayString = "${iccp.com.schedule-ms:30000}", initialDelay = 15000)
    public void publishDueAndArchive() {
        DbSessionContext.runAs(DbSessionContext.system("com-scheduler"), () -> {
            List<UUID> due = tx.execute(s -> jdbc.sql("SELECT public_id FROM com.announcement WHERE status = 'SCHEDULED' AND effective_at <= now()")
                    .query(UUID.class).list());
            for (UUID id : due == null ? List.<UUID>of() : due) {
                tx.executeWithoutResult(s -> service.publish(service.load(id)));
                log.info("Published scheduled announcement {}", id);
            }
            tx.executeWithoutResult(s -> jdbc.sql("UPDATE com.announcement SET status = 'ARCHIVED' WHERE status = 'PUBLISHED' AND expires_at <= now()").update());
        });
    }

    record Pending(long announcementId, UUID publicId, String title, OffsetDateTime publishedAt, long staffId) {
    }

    @Scheduled(fixedDelayString = "${iccp.com.reminder-ms:300000}", initialDelay = 60000)
    public void remindAndEscalate() {
        DbSessionContext.runAs(DbSessionContext.system("com-reminders"), () -> tx.executeWithoutResult(s -> {
            List<Pending> pending = jdbc.sql("""
                    SELECT a.id AS announcement_id, a.public_id, coalesce(v.title_en, v.title_sw) AS title, a.published_at, r.staff_id
                      FROM com.announcement a
                      JOIN com.announcement_version v ON v.announcement_id = a.id AND v.version_no = a.current_version
                      JOIN com.announcement_recipient r ON r.announcement_id = a.id
                     WHERE a.status = 'PUBLISHED' AND a.requires_ack AND r.acknowledged_at IS NULL
                       AND a.published_at < now() - interval '48 hours' AND a.published_at > now() - interval '30 days'
                    """).query(Pending.class).list();
            OffsetDateTime now = OffsetDateTime.now();
            Map<UUID, List<Pending>> byNotice = pending.stream().collect(Collectors.groupingBy(Pending::publicId));
            byNotice.forEach((publicId, people) -> {
                Pending first = people.get(0);
                long hours = Duration.between(first.publishedAt(), now).toHours();
                for (Pending p : people) {
                    long sent = notifier.countSent(p.staffId(), "AnnouncementAckReminder", publicId);
                    if ((sent == 0 && hours >= 48) || (sent == 1 && hours >= 96)) {
                        notifier.notify(new NotificationRequest(List.of(p.staffId()), "AnnouncementAckReminder", NtfCategory.COMMUNICATION,
                                Map.of("title", p.title()), "/announcements/" + publicId, Set.of(NotificationRequest.Channel.EMAIL), "COM", publicId));
                    }
                }
                if (hours >= 96) {
                    // After the second reminder, tell each line manager once how many of their staff are outstanding.
                    Map<Long, Long> perManager = people.stream()
                            .filter(p -> notifier.countSent(p.staffId(), "AnnouncementAckReminder", publicId) >= 2)
                            .map(p -> directory.lineManagerOf(p.staffId()).orElse(null))
                            .filter(m -> m != null)
                            .collect(Collectors.groupingBy(m -> m, Collectors.counting()));
                    perManager.forEach((manager, count) -> {
                        if (notifier.countSent(manager, "AnnouncementAckOverdue", publicId) == 0) {
                            notifier.notify(NotificationRequest.inApp(List.of(manager), "AnnouncementAckOverdue", NtfCategory.COMMUNICATION,
                                    Map.of("title", first.title(), "count", String.valueOf(count)), "/announcements/manage/" + publicId + "/reach", "COM", publicId));
                        }
                    });
                }
            });
        }));
    }
}
