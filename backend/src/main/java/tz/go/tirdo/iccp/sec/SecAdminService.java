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
import java.util.Set;
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

    public record UserAccount(long id, UUID staffPublicId, String employeeNo, String staffName, String email, String unitName,
                              String status, String accountType, String identityStatus, OffsetDateTime lastLoginAt,
                              OffsetDateTime createdAt, String roles) {
    }

    public record AccountStatusRequest(String status) {
    }

    record AccountState(long id, long staffId, String subject, String status) {
    }

    @Transactional(readOnly = true)
    public List<RoleInfo> roles() {
        return jdbc.sql("SELECT code, name, privileged, requires_mfa FROM sec.role ORDER BY privileged, code").query(RoleInfo.class).list();
    }

    @Transactional(readOnly = true)
    public PageResult<UserAccount> users(String query, String status, Integer page, Integer size) {
        int pg = PageResult.clampPage(page), sz = PageResult.clampSize(size);
        StringBuilder where = new StringBuilder(" WHERE a.account_type = 'STAFF'");
        Map<String, Object> params = new LinkedHashMap<>();
        if (query != null && !query.isBlank()) {
            where.append(" AND (upper(s.employee_no) LIKE upper(:q) OR lower(concat_ws(' ', s.title, s.first_name, s.last_name)) LIKE lower(:q) OR lower(s.official_email) LIKE lower(:q))");
            params.put("q", "%" + query.trim() + "%");
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where.append(" AND a.status = :status");
            params.put("status", status.toUpperCase());
        }
        String from = """
                 FROM sec.user_account a
                 JOIN dir.staff_member s ON s.id = a.staff_id
                 JOIN dir.org_unit u ON u.id = s.org_unit_id
                """;
        long total = jdbc.sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<UserAccount> rows = jdbc.sql("""
                SELECT a.id, s.public_id AS staff_public_id, s.employee_no,
                       concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name) AS staff_name,
                       s.official_email AS email, u.name_en AS unit_name, a.status, a.account_type,
                       CASE WHEN a.keycloak_subject LIKE 'pending:%' THEN 'PENDING'
                            WHEN a.keycloak_subject LIKE 'dev:%' THEN 'DEVELOPMENT' ELSE 'LINKED' END AS identity_status,
                       a.last_login_at, a.created_at,
                       coalesce(string_agg(DISTINCT r.code, ', ' ORDER BY r.code), '') AS roles
                """ + from + """
                 LEFT JOIN sec.role_assignment ra ON ra.user_account_id = a.id AND ra.starts_on <= current_date
                    AND (ra.ends_on IS NULL OR ra.ends_on >= current_date)
                 LEFT JOIN sec.role r ON r.id = ra.role_id
                """ + where + """
                 GROUP BY a.id, s.public_id, s.employee_no, s.title, s.first_name, s.last_name,
                          s.official_email, u.name_en
                 ORDER BY s.last_name, s.first_name LIMIT :limit OFFSET :offset
                """).params(params).param("limit", sz).param("offset", pg * sz).query(UserAccount.class).list();
        return new PageResult<>(rows, total, pg, sz);
    }

    @Transactional
    public void setAccountStatus(long accountId, AccountStatusRequest request) {
        String next = request == null || request.status() == null ? "" : request.status().toUpperCase();
        if (!Set.of("ACTIVE", "DISABLED").contains(next)) {
            throw ApiException.badRequest("ICCP-SEC-020", "Account status must be ACTIVE or DISABLED");
        }
        AccountState account = jdbc.sql("SELECT id, staff_id, keycloak_subject AS subject, status FROM sec.user_account WHERE id = :id AND account_type = 'STAFF'")
                .param("id", accountId).query(AccountState.class).optional().orElseThrow(ApiException::notFound);
        if (account.id() == CurrentUser.get().accountId() && "DISABLED".equals(next)) {
            throw ApiException.conflict("ICCP-SEC-021", "You cannot disable your own account");
        }
        if ("DISABLED".equals(next)) {
            boolean isAdmin = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM sec.role_assignment ra JOIN sec.role r ON r.id = ra.role_id
                     WHERE ra.user_account_id = :id AND r.code = 'SECURITY_ADMIN' AND ra.starts_on <= current_date
                       AND (ra.ends_on IS NULL OR ra.ends_on >= current_date))
                    """).param("id", accountId).query(Boolean.class).single();
            long activeAdmins = jdbc.sql("""
                    SELECT count(DISTINCT a.id) FROM sec.user_account a JOIN sec.role_assignment ra ON ra.user_account_id = a.id
                     JOIN sec.role r ON r.id = ra.role_id WHERE a.status = 'ACTIVE' AND r.code = 'SECURITY_ADMIN'
                       AND ra.starts_on <= current_date AND (ra.ends_on IS NULL OR ra.ends_on >= current_date)
                    """).query(Long.class).single();
            if (isAdmin && activeAdmins <= 1) {
                throw ApiException.conflict("ICCP-SEC-022", "The last active Security Administrator cannot be disabled");
            }
        }
        if (!next.equals(account.status())) {
            jdbc.sql("UPDATE sec.user_account SET status = :status, deprovisioned_at = CASE WHEN :status = 'DISABLED' THEN now() ELSE NULL END WHERE id = :id")
                    .param("status", next).param("id", accountId).update();
            audit.record("SEC", "ACCOUNT_STATUS_CHANGE", "user_account", accountId,
                    Map.of("from", account.status(), "to", next, "staffId", account.staffId()));
            principals.evict(account.subject());
        }
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

    public record AuditRow(long chainSeq, OffsetDateTime occurredAt, String actorSubject, Long actorStaffId, String actorName, String module, String action,
                           String resourceType, String resourceId, String outcome, String requestId, String details) {
    }

    @Transactional
    public PageResult<AuditRow> searchAudit(String actor, String module, String action, String resourceId, OffsetDateTime from,
                                            OffsetDateTime to, Integer page, Integer size) {
        int pg = PageResult.clampPage(page), sz = PageResult.clampSize(size);
        StringBuilder where = new StringBuilder(" WHERE true");
        Map<String, Object> p = new LinkedHashMap<>();
        if (actor != null && !actor.isBlank()) {
            where.append(" AND (e.actor_subject = :actor OR upper(s.employee_no) = upper(:actor) OR " +
                    "lower(concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name)) LIKE lower(:actorLike))");
            p.put("actor", actor.trim());
            p.put("actorLike", "%" + actor.trim() + "%");
        }
        if (module != null && !module.isBlank()) { where.append(" AND e.module = :module"); p.put("module", module.toUpperCase()); }
        if (action != null && !action.isBlank()) { where.append(" AND e.action = :action"); p.put("action", action.toUpperCase()); }
        if (resourceId != null && !resourceId.isBlank()) { where.append(" AND e.resource_id = :rid"); p.put("rid", resourceId); }
        if (from != null) { where.append(" AND e.occurred_at >= :from"); p.put("from", from); }
        if (to != null) { where.append(" AND e.occurred_at < :to"); p.put("to", to); }
        String fromClause = " FROM sec.audit_event e LEFT JOIN dir.staff_member s ON s.id = e.actor_staff_id";
        long total = jdbc.sql("SELECT count(*)" + fromClause + where).params(p).query(Long.class).single();
        List<AuditRow> rows = jdbc.sql("""
                SELECT e.chain_seq, e.occurred_at, e.actor_subject, e.actor_staff_id,
                       nullif(concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name), '') AS actor_name,
                       e.module, e.action, e.resource_type, e.resource_id, e.outcome,
                       e.request_id::text AS request_id, e.details::text AS details
                """ + fromClause + where + " ORDER BY e.chain_seq DESC LIMIT :lim OFFSET :off")
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
