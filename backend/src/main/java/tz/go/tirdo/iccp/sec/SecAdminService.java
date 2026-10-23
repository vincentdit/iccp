package tz.go.tirdo.iccp.sec;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.dir.api.StaffRef;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.platform.web.PageResult;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Role administration and audit search for SECURITY_ADMIN and AUDITOR (SCR-SEC-02/03, FR-SEC-04/07/08). */
@Service
public class SecAdminService {

    private final JdbcClient jdbc;
    private final DirectoryApi directory;
    private final AuditService audit;
    private final PrincipalService principals;

    public SecAdminService(JdbcClient jdbc, DirectoryApi directory, AuditService audit, PrincipalService principals) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.audit = audit;
        this.principals = principals;
    }

    public record RoleInfo(String code, String name, boolean privileged, boolean requiresMfa) {
    }

    public record Assignment(long id, UUID staffPublicId, String staffName, String employeeNo, String roleCode, String roleName,
                             boolean privileged, String scopeUnitCode, LocalDate startsOn, LocalDate endsOn, String approvedBy,
                             String approvalRef, String createdBy, OffsetDateTime createdAt) {
    }

    public record AssignRequest(UUID staffPublicId, String roleCode, String scopeUnitCode, LocalDate startsOn, LocalDate endsOn,
                                String approvedBy, String approvalRef) {
    }

    @Transactional(readOnly = true)
    public List<RoleInfo> roles() {
        return jdbc.sql("SELECT code, name, privileged, requires_mfa FROM sec.role ORDER BY privileged, code").query(RoleInfo.class).list();
    }

    private static final String ASSIGNMENT_SELECT = """
            SELECT ra.id, s.public_id AS staff_public_id, concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name) AS staff_name,
                   s.employee_no, r.code AS role_code, r.name AS role_name, r.privileged, u.code AS scope_unit_code,
                   ra.starts_on, ra.ends_on, ra.approved_by, ra.approval_ref, ra.created_by, ra.created_at
              FROM sec.role_assignment ra
              JOIN sec.role r ON r.id = ra.role_id
              JOIN sec.user_account a ON a.id = ra.user_account_id
              JOIN dir.staff_member s ON s.id = a.staff_id
              LEFT JOIN dir.org_unit u ON u.id = ra.scope_org_unit_id
            """;

    @Transactional(readOnly = true)
    public List<Assignment> assignments(UUID staffPublicId, String roleCode) {
        StringBuilder where = new StringBuilder(" WHERE (ra.ends_on IS NULL OR ra.ends_on >= current_date)");
        Map<String, Object> p = new LinkedHashMap<>();
        if (staffPublicId != null) {
            where.append(" AND s.public_id = :s");
            p.put("s", staffPublicId);
        }
        if (roleCode != null && !roleCode.isBlank()) {
            where.append(" AND r.code = :r");
            p.put("r", roleCode);
        }
        return jdbc.sql(ASSIGNMENT_SELECT + where + " ORDER BY r.code, s.last_name").params(p).query(Assignment.class).list();
    }

    @Transactional
    public Assignment assign(AssignRequest req) {
        StaffRef staff = directory.findByPublicId(req.staffPublicId()).filter(StaffRef::isActive)
                .orElseThrow(() -> ApiException.badRequest("ICCP-SEC-010", "sec.unknownStaff"));
        RoleInfo role = jdbc.sql("SELECT code, name, privileged, requires_mfa FROM sec.role WHERE code = :c").param("c", req.roleCode())
                .query(RoleInfo.class).optional().orElseThrow(() -> ApiException.badRequest("ICCP-SEC-011", "sec.unknownRole"));
        if (role.privileged() && (req.endsOn() == null || req.approvedBy() == null || req.approvedBy().isBlank())) {
            throw ApiException.badRequest("ICCP-SEC-001", "db.ICCP-SEC-001");
        }
        if (staff.id() == CurrentUser.get().staffId() && role.privileged()) {
            throw ApiException.conflict("ICCP-SEC-012", "sec.selfGrant");
        }
        long accountId = jdbc.sql("SELECT id FROM sec.user_account WHERE staff_id = :s").param("s", staff.id()).query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("""
                        INSERT INTO sec.user_account (staff_id, keycloak_subject, account_type, status)
                        VALUES (:s, :sub, 'STAFF', 'ACTIVE') RETURNING id
                        """).param("s", staff.id()).param("sub", "pending:" + staff.publicId()).query(Long.class).single());
        Long scope = null;
        if (req.scopeUnitCode() != null && !req.scopeUnitCode().isBlank()) {
            scope = directory.findUnitByCode(req.scopeUnitCode()).orElseThrow(() -> ApiException.badRequest("ICCP-SEC-013", "sec.unknownUnit")).id();
        }
        long id = jdbc.sql("""
                INSERT INTO sec.role_assignment (user_account_id, role_id, scope_org_unit_id, starts_on, ends_on, approved_by, approval_ref)
                VALUES (:a, (SELECT id FROM sec.role WHERE code = :r), :scope, coalesce(:from, current_date), :to, :by, :ref) RETURNING id
                """).param("a", accountId).param("r", role.code()).param("scope", scope).param("from", req.startsOn())
                .param("to", req.endsOn()).param("by", req.approvedBy()).param("ref", req.approvalRef()).query(Long.class).single();
        audit.record("SEC", "ROLE_GRANT", "role_assignment", id, Map.of("role", role.code(), "staff", staff.publicId().toString()));
        principals.evict(null);
        return jdbc.sql(ASSIGNMENT_SELECT + " WHERE ra.id = :id").param("id", id).query(Assignment.class).single();
    }

    /** Ends an assignment today (or removes it if it had not started yet). */
    @Transactional
    public void revoke(long id) {
        int n = jdbc.sql("UPDATE sec.role_assignment SET ends_on = current_date - 1 WHERE id = :id AND starts_on < current_date").param("id", id).update();
        if (n == 0) {
            n = jdbc.sql("DELETE FROM sec.role_assignment WHERE id = :id").param("id", id).update();
        }
        if (n == 0) {
            throw ApiException.notFound();
        }
        audit.record("SEC", "ROLE_REVOKE", "role_assignment", id, Map.of());
        principals.evict(null);
    }

    public record AuditRow(long chainSeq, OffsetDateTime occurredAt, String actorSubject, Long actorStaffId, String module, String action,
                           String resourceType, String resourceId, String outcome, String requestId, String details) {
    }

    @Transactional
    public PageResult<AuditRow> searchAudit(String actor, String module, String action, String resourceId, OffsetDateTime from,
                                            OffsetDateTime to, Integer page, Integer size) {
        int pg = PageResult.clampPage(page), sz = PageResult.clampSize(size);
        StringBuilder where = new StringBuilder(" WHERE true");
        Map<String, Object> p = new LinkedHashMap<>();
        if (actor != null && !actor.isBlank()) { where.append(" AND actor_subject = :actor"); p.put("actor", actor); }
        if (module != null && !module.isBlank()) { where.append(" AND module = :module"); p.put("module", module.toUpperCase()); }
        if (action != null && !action.isBlank()) { where.append(" AND action = :action"); p.put("action", action.toUpperCase()); }
        if (resourceId != null && !resourceId.isBlank()) { where.append(" AND resource_id = :rid"); p.put("rid", resourceId); }
        if (from != null) { where.append(" AND occurred_at >= :from"); p.put("from", from); }
        if (to != null) { where.append(" AND occurred_at < :to"); p.put("to", to); }
        long total = jdbc.sql("SELECT count(*) FROM sec.audit_event" + where).params(p).query(Long.class).single();
        List<AuditRow> rows = jdbc.sql("""
                SELECT chain_seq, occurred_at, actor_subject, actor_staff_id, module, action, resource_type, resource_id, outcome,
                       request_id::text AS request_id, details::text AS details
                  FROM sec.audit_event""" + where + " ORDER BY chain_seq DESC LIMIT :lim OFFSET :off")
                .params(p).param("lim", sz).param("off", pg * sz).query(AuditRow.class).list();
        audit.record("SEC", "AUDIT_SEARCH", "audit_event", null, Map.of("filters", p.keySet().toString()));
        return new PageResult<>(rows, total, pg, sz);
    }

    public record ChainStatus(boolean intact, Long firstBrokenSeq, long events) {
    }

    @Transactional
    public ChainStatus verifyChain() {
        Long broken = jdbc.sql("SELECT sec.verify_audit_chain()").query(Long.class).optional().orElse(null);
        long count = jdbc.sql("SELECT count(*) FROM sec.audit_event").query(Long.class).single();
        audit.record("SEC", "AUDIT_VERIFY", "audit_event", null, Map.of("intact", broken == null));
        return new ChainStatus(broken == null, broken, count);
    }
}
