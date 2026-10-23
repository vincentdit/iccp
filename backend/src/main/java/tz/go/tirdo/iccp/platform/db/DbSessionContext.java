package tz.go.tirdo.iccp.platform.db;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;

import java.util.function.Supplier;

/**
 * Who the database should believe is acting in the current transaction.
 *
 * <p>The values are written with {@code set_config(..., true)} at the start of every transaction by
 * {@link DbSessionTransactionManager}. PostgreSQL row-level security (Doc 06 §6.2) and the audit
 * columns ({@code created_by}, {@code updated_by}) read them. Outside a transaction nothing is set,
 * so protected tables return no rows — a safe default.
 *
 * @param actor    Keycloak subject or {@code system:<job>}
 * @param staffId  dir.staff_member.id of the caller, or null for system jobs
 * @param finScope true only for Finance roles and system jobs that must see all financial rows
 */
public record DbSessionContext(String actor, Long staffId, boolean finScope) {

    private static final ThreadLocal<DbSessionContext> SYSTEM = new ThreadLocal<>();

    /** The context for the current thread: an explicit system context if one is active, otherwise the signed-in user. */
    public static DbSessionContext current() {
        DbSessionContext system = SYSTEM.get();
        if (system != null) {
            return system;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof IccpPrincipal p) {
            return new DbSessionContext(p.subject(), p.staffId(), p.hasFinanceScope());
        }
        return null;
    }

    /**
     * Runs work as a named system actor (scheduled jobs, outbox handlers, demo seeding). Start the
     * transaction inside {@code work} so the context is applied to it.
     */
    public static <T> T runAs(DbSessionContext context, Supplier<T> work) {
        DbSessionContext previous = SYSTEM.get();
        SYSTEM.set(context);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                SYSTEM.remove();
            } else {
                SYSTEM.set(previous);
            }
        }
    }

    public static void runAs(DbSessionContext context, Runnable work) {
        runAs(context, () -> {
            work.run();
            return null;
        });
    }

    /** A system job that may read all financial rows. */
    public static DbSessionContext system(String job) {
        return new DbSessionContext("system:" + job, null, true);
    }

    /** Acting on behalf of a named person outside an HTTP request (e.g. demo seeding). */
    public static DbSessionContext person(String subject, Long staffId, boolean finScope) {
        return new DbSessionContext(subject, staffId, finScope);
    }
}
