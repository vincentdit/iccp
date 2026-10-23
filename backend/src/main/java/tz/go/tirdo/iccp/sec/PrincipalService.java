package tz.go.tirdo.iccp.sec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.dir.api.StaffRef;
import tz.go.tirdo.iccp.dir.api.UnitRef;
import tz.go.tirdo.iccp.platform.db.DbSessionContext;
import tz.go.tirdo.iccp.platform.security.IccpAuthenticationException;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;
import tz.go.tirdo.iccp.platform.security.PrincipalLoader;
import tz.go.tirdo.iccp.platform.security.Roles;
import tz.go.tirdo.iccp.sec.api.RoleDirectory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a token to an ICCP principal (FR-SEC-01/03/05).
 *
 * <ul>
 *   <li>The account is found by the Keycloak subject. On first sign-in it is linked to the HR
 *       record by official email (or employee number claim) — accounts are never created for
 *       people HR does not know.</li>
 *   <li>Disabled accounts and staff who have LEFT or are SUSPENDED are refused.</li>
 *   <li>Roles = STAFF + active role assignments + roles derived from the directory
 *       (LINE_MANAGER for anyone with direct reports; DIRECTOR for heads of directorates and
 *       departments, including acting heads).</li>
 * </ul>
 * Results are cached for 60 seconds; role changes and deprovisioning evict the cache.
 */
@Service
public class PrincipalService implements PrincipalLoader, RoleDirectory {

    private static final Logger log = LoggerFactory.getLogger(PrincipalService.class);
    private static final Duration TTL = Duration.ofSeconds(60);

    private final JdbcClient jdbc;
    private final DirectoryApi directory;
    private final TransactionTemplate tx;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public PrincipalService(JdbcClient jdbc, DirectoryApi directory, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.tx = tx;
    }

    private record Cached(IccpPrincipal principal, Instant at) {
    }

    record Account(long id, Long staffId, String status) {
    }

    @Override
    public IccpPrincipal load(Jwt jwt) {
        String subject = jwt.getSubject();
        Cached c = cache.get(subject);
        if (c != null && c.at().plus(TTL).isAfter(Instant.now())) {
            return c.principal();
        }
        IccpPrincipal p = DbSessionContext.runAs(DbSessionContext.person(subject, null, false), () -> tx.execute(s -> resolve(jwt)));
        cache.put(subject, new Cached(p, Instant.now()));
        return p;
    }

    public void evict(String subject) {
        if (subject == null) {
            cache.clear();
        } else {
            cache.remove(subject);
        }
    }

    private IccpPrincipal resolve(Jwt jwt) {
        String subject = jwt.getSubject();
        Optional<Account> account = jdbc.sql("SELECT id, staff_id, status FROM sec.user_account WHERE keycloak_subject = :s")
                .param("s", subject).query(Account.class).optional();
        if (account.isEmpty()) {
            account = linkFirstSignIn(jwt);
        }
        Account a = account.orElseThrow(() -> new IccpAuthenticationException(403, "ICCP-SEC-002",
                "Your account is not set up in ICCP. Ask HR to confirm your official email address."));
        if (!"ACTIVE".equals(a.status()) || a.staffId() == null) {
            throw new IccpAuthenticationException(403, "ICCP-SEC-003", "Your ICCP account is disabled. Contact the ICTT service desk.");
        }
        StaffRef staff = directory.findById(a.staffId()).orElseThrow();
        if (!staff.isActive()) {
            throw new IccpAuthenticationException(403, "ICCP-SEC-003", "Your ICCP account is disabled. Contact the ICTT service desk.");
        }
        jdbc.sql("UPDATE sec.user_account SET last_login_at = now() WHERE id = :id AND (last_login_at IS NULL OR last_login_at < now() - interval '5 minutes')")
                .param("id", a.id()).update();
        return new IccpPrincipal(a.id(), staff.id(), staff.publicId(), subject, staff.employeeNo(), staff.displayName(), staff.email(),
                staff.orgUnitId(), staff.language(), Collections.unmodifiableSet(roles(staff.id())));
    }

    private Optional<Account> linkFirstSignIn(Jwt jwt) {
        Optional<StaffRef> staff = directory.findByEmail(jwt.getClaimAsString("email"));
        if (staff.isEmpty() && jwt.hasClaim("employee_no")) {
            staff = directory.findByEmployeeNo(jwt.getClaimAsString("employee_no"));
        }
        if (staff.isEmpty() || !staff.get().isActive()) {
            return Optional.empty();
        }
        Optional<String> existingSubject = jdbc.sql("SELECT keycloak_subject FROM sec.user_account WHERE staff_id = :s")
                .param("s", staff.get().id()).query(String.class).optional();
        if (existingSubject.isPresent()) {
            if (!existingSubject.get().startsWith("pending:")) {
                log.warn("Sign-in refused: staff {} is already linked to another identity", staff.get().employeeNo());
                return Optional.empty();
            }
            // A role was granted before the person's first sign-in: claim the placeholder account.
            jdbc.sql("UPDATE sec.user_account SET keycloak_subject = :sub WHERE staff_id = :s")
                    .param("sub", jwt.getSubject()).param("s", staff.get().id()).update();
            return jdbc.sql("SELECT id, staff_id, status FROM sec.user_account WHERE staff_id = :s")
                    .param("s", staff.get().id()).query(Account.class).optional();
        }
        long id = jdbc.sql("""
                INSERT INTO sec.user_account (staff_id, keycloak_subject, account_type) VALUES (:s, :sub, 'STAFF') RETURNING id
                """).param("s", staff.get().id()).param("sub", jwt.getSubject()).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO sec.audit_event (actor_subject, actor_staff_id, module, action, resource_type, resource_id, outcome, details)
                VALUES (:sub, :s, 'SEC', 'ACCOUNT_LINKED', 'user_account', :id, 'SUCCESS', '{}'::jsonb)
                """).param("sub", jwt.getSubject()).param("s", staff.get().id()).param("id", String.valueOf(id)).update();
        return Optional.of(new Account(id, staff.get().id(), "ACTIVE"));
    }

    // ------------------------------------------------------------------ RoleDirectory

    @Override
    public Set<String> rolesOf(long staffId) {
        return roles(staffId);
    }

    private Set<String> roles(long staffId) {
        Set<String> roles = new LinkedHashSet<>();
        roles.add(Roles.STAFF);
        roles.addAll(jdbc.sql("""
                SELECT r.code FROM sec.role_assignment ra
                  JOIN sec.role r ON r.id = ra.role_id
                  JOIN sec.user_account a ON a.id = ra.user_account_id
                 WHERE a.staff_id = :s AND a.status = 'ACTIVE'
                   AND ra.starts_on <= current_date AND (ra.ends_on IS NULL OR ra.ends_on >= current_date)
                """).param("s", staffId).query(String.class).list());
        if (directory.hasDirectReports(staffId)) {
            roles.add(Roles.LINE_MANAGER);
        }
        List<UnitRef> headed = directory.unitsHeadedBy(staffId);
        if (!headed.isEmpty()) {
            roles.add(Roles.LINE_MANAGER);
        }
        if (headed.stream().anyMatch(u -> Set.of("DIRECTORATE", "DEPARTMENT").contains(u.unitType()))) {
            roles.add(Roles.DIRECTOR);
        }
        return roles;
    }

    @Override
    public Set<Long> staffWithRole(String roleCode) {
        Set<Long> ids = new HashSet<>(jdbc.sql("""
                SELECT a.staff_id FROM sec.role_assignment ra
                  JOIN sec.role r ON r.id = ra.role_id
                  JOIN sec.user_account a ON a.id = ra.user_account_id
                 WHERE r.code = :code AND a.status = 'ACTIVE' AND a.staff_id IS NOT NULL
                   AND ra.starts_on <= current_date AND (ra.ends_on IS NULL OR ra.ends_on >= current_date)
                """).param("code", roleCode).query(Long.class).list());
        switch (roleCode) {
            case Roles.STAFF -> ids.addAll(directory.resolveAudience(List.of(new tz.go.tirdo.iccp.dir.api.AudienceRule("ALL", null))));
            case Roles.LINE_MANAGER -> {
                ids.addAll(directory.lineManagers());
                ids.addAll(directory.unitHeads(Set.of()));
            }
            case Roles.DIRECTOR -> ids.addAll(directory.unitHeads(Set.of("DIRECTORATE", "DEPARTMENT")));
            default -> { /* assigned roles only */ }
        }
        return ids;
    }
}
