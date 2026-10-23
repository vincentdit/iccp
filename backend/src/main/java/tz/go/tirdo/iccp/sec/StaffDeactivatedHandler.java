package tz.go.tirdo.iccp.sec;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tz.go.tirdo.iccp.dir.api.DirEvents;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.outbox.OutboxHandler;
import tz.go.tirdo.iccp.platform.outbox.OutboxMessage;

import java.util.Map;

/**
 * Deprovisions leavers and suspended staff (FR-DIR-08, FR-SEC-03, BRL-20): disables the account,
 * ends active role assignments and evicts cached sessions. The outbox runs every few seconds, well
 * inside the 1-hour target. Keycloak sessions are revoked by the identity team's leaver procedure
 * (or the Keycloak admin API in a later release).
 */
@Component
public class StaffDeactivatedHandler implements OutboxHandler {

    private final JdbcClient jdbc;
    private final PrincipalService principals;
    private final AuditService audit;

    public StaffDeactivatedHandler(JdbcClient jdbc, PrincipalService principals, AuditService audit) {
        this.jdbc = jdbc;
        this.principals = principals;
        this.audit = audit;
    }

    @Override
    public String eventType() {
        return DirEvents.STAFF_DEACTIVATED;
    }

    @Override
    public void handle(OutboxMessage message) {
        long staffId = message.payload().get("staffId").asLong();
        int n = jdbc.sql("""
                UPDATE sec.user_account SET status = 'DISABLED', deprovisioned_at = now()
                 WHERE staff_id = :s AND status = 'ACTIVE'
                """).param("s", staffId).update();
        jdbc.sql("""
                UPDATE sec.role_assignment SET ends_on = current_date - 1
                 WHERE user_account_id IN (SELECT id FROM sec.user_account WHERE staff_id = :s)
                   AND starts_on < current_date AND (ends_on IS NULL OR ends_on >= current_date)
                """).param("s", staffId).update();
        jdbc.sql("""
                DELETE FROM sec.role_assignment
                 WHERE user_account_id IN (SELECT id FROM sec.user_account WHERE staff_id = :s) AND starts_on >= current_date
                """).param("s", staffId).update();
        principals.evict(null);
        if (n > 0) {
            audit.record("SEC", "ACCOUNT_DEPROVISIONED", "staff_member", message.aggregateId(),
                    Map.of("employmentStatus", message.payload().path("employmentStatus").asText()));
        }
    }
}
