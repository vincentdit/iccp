package tz.go.tirdo.iccp.ntf;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.dir.api.StaffContact;
import tz.go.tirdo.iccp.ntf.channel.EmailSender;
import tz.go.tirdo.iccp.platform.db.DbSessionContext;

import java.time.LocalDate;
import java.util.List;

/** Sends one daily summary to staff who choose digest mode (FR-NTF-07). */
@Component
public class EmailDigestDispatcher {
    private final JdbcClient jdbc;
    private final DirectoryApi directory;
    private final EmailSender email;
    private final TransactionTemplate tx;

    public EmailDigestDispatcher(JdbcClient jdbc, DirectoryApi directory, EmailSender email, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.email = email;
        this.tx = tx;
    }

    record Due(long staffId, long count) {}
    record Item(String title, String deepLink) {}

    @Scheduled(cron = "${iccp.notifications.digest-cron:0 30 7 * * *}", zone = "${iccp.notifications.time-zone:Africa/Dar_es_Salaam}")
    public void sendDaily() {
        DbSessionContext.runAs(DbSessionContext.system("email-digest"), () -> {
            LocalDate date = LocalDate.now().minusDays(1);
            List<Due> due = jdbc.sql("""
                    SELECT n.recipient_staff_id AS staff_id, count(*) AS count
                      FROM ntf.notification n JOIN ntf.preference_setting p ON p.staff_id = n.recipient_staff_id AND p.email_digest
                     WHERE n.created_at >= :date AND n.created_at < :date + interval '1 day' AND NOT n.mandatory
                       AND NOT EXISTS (SELECT 1 FROM ntf.email_digest_delivery d
                                       WHERE d.staff_id = n.recipient_staff_id AND d.digest_date = :date AND d.status = 'SENT')
                     GROUP BY n.recipient_staff_id
                    """).param("date", date).query(Due.class).list();
            due.forEach(d -> send(date, d));
        });
    }

    private void send(LocalDate date, Due due) {
        StaffContact contact = directory.contacts(List.of(due.staffId())).stream().findFirst().orElse(null);
        if (contact == null || !contact.active() || contact.email() == null) return;
        List<Item> items = jdbc.sql("""
                SELECT title, deep_link FROM ntf.notification
                 WHERE recipient_staff_id = :staff AND created_at >= :date AND created_at < :date + interval '1 day'
                   AND NOT mandatory ORDER BY created_at
                """).param("staff", due.staffId()).param("date", date).query(Item.class).list();
        String heading = "sw".equals(contact.language()) ? "Muhtasari wa arifa za ICCP" : "Your ICCP notification digest";
        StringBuilder body = new StringBuilder(heading).append("\n\n");
        items.forEach(i -> body.append("- ").append(i.title()).append("\n"));
        body.append("\nOpen ICCP to review these items.");
        String ref = null;
        String error = null;
        try {
            ref = email.send(contact.email(), heading, body.toString());
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
        }
        String finalRef = ref;
        String finalError = error;
        tx.executeWithoutResult(s -> jdbc.sql("""
                INSERT INTO ntf.email_digest_delivery (staff_id, digest_date, notification_count, status, provider_ref, error)
                VALUES (:staff, :date, :count, :status, :ref, :error)
                ON CONFLICT (staff_id, digest_date) DO UPDATE SET notification_count = EXCLUDED.notification_count,
                    status = EXCLUDED.status, provider_ref = EXCLUDED.provider_ref, error = EXCLUDED.error, created_at = now()
                """).param("staff", due.staffId()).param("date", date).param("count", due.count())
                .param("status", finalError == null ? "SENT" : "FAILED").param("ref", finalRef).param("error", finalError).update());
    }
}
