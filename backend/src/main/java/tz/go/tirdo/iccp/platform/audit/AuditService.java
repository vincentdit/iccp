package tz.go.tirdo.iccp.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.platform.db.DbSessionContext;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;
import tz.go.tirdo.iccp.platform.web.RequestIds;

import java.util.Map;
import java.util.UUID;

/**
 * Writes append-only, hash-chained audit events (sec.audit_event; chain maintained by trigger,
 * Doc 06 §7). Never put secrets, amounts or personal financial values in {@code details}.
 */
@Service
public class AuditService {

    public enum Outcome { SUCCESS, DENIED, ERROR }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Records an event inside the caller's transaction, so it commits or rolls back with the action. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String module, String action, String resourceType, Object resourceId, Map<String, ?> details) {
        write(module, action, resourceType, resourceId, Outcome.SUCCESS, details);
    }

    /** Records a denied or failed attempt in its own transaction, so it survives the caller's rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(String module, String action, String resourceType, Object resourceId, Outcome outcome, Map<String, ?> details) {
        write(module, action, resourceType, resourceId, outcome, details);
    }

    private void write(String module, String action, String resourceType, Object resourceId, Outcome outcome, Map<String, ?> details) {
        IccpPrincipal p = CurrentUser.optional().orElse(null);
        DbSessionContext ctx = DbSessionContext.current();
        String actor = p != null ? p.subject() : ctx != null ? ctx.actor() : "anonymous";
        Long staffId = p != null ? Long.valueOf(p.staffId()) : ctx != null ? ctx.staffId() : null;
        String requestId = RequestIds.current();
        jdbc.sql("""
                INSERT INTO sec.audit_event (actor_subject, actor_staff_id, module, action, resource_type, resource_id,
                                             outcome, client_ip, request_id, details)
                VALUES (:actor, :staffId, :module, :action, :type, :rid, :outcome, CAST(:ip AS inet), :reqId, CAST(:details AS jsonb))
                """)
                .param("actor", actor)
                .param("staffId", staffId)
                .param("module", module)
                .param("action", action)
                .param("type", resourceType)
                .param("rid", resourceId == null ? null : resourceId.toString())
                .param("outcome", outcome.name())
                .param("ip", RequestIds.clientIp())
                .param("reqId", requestId == null ? null : UUID.fromString(requestId))
                .param("details", toJson(details))
                .update();
    }

    private String toJson(Map<String, ?> details) {
        try {
            return mapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
