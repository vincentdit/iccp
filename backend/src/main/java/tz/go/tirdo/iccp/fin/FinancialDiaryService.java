package tz.go.tirdo.iccp.fin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.ntf.api.NotificationRequest;
import tz.go.tirdo.iccp.ntf.api.Notifier;
import tz.go.tirdo.iccp.ntf.api.NtfCategory;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;
import tz.go.tirdo.iccp.platform.security.Roles;
import tz.go.tirdo.iccp.platform.time.WorkingDays;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.platform.web.PageResult;
import tz.go.tirdo.iccp.sec.api.RoleDirectory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The staff Financial Diary (SCR-FIN-01..03; FR-FIN-06..09) and the claim query workflow
 * (BP-FIN-02; FR-FIN-10..12). Staff only ever see their own published rows — the queries filter by
 * the caller and PostgreSQL row-level security enforces the same rule underneath.
 */
@Service
public class FinancialDiaryService {

    /** Working days Finance has to respond to a query (Doc 03 SLA). */
    static final int RESPONSE_DAYS = 3;
    static final int MAX_REOPEN = 2;
    private static final Set<String> ACTIVE = Set.of("OPEN", "RESPONDED", "REOPENED", "ESCALATED");

    private final JdbcClient jdbc;
    private final Notifier notifier;
    private final RoleDirectory roles;
    private final WorkingDays workingDays;
    private final AuditService audit;

    public FinancialDiaryService(JdbcClient jdbc, Notifier notifier, RoleDirectory roles, WorkingDays workingDays, AuditService audit) {
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.roles = roles;
        this.workingDays = workingDays;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- diary

    @Transactional(readOnly = true)
    public List<FinTypes.Summary> summary() {
        long me = staffId();
        record Row(String currency, BigDecimal approved, BigDecimal adjusted, BigDecimal paid, BigDecimal outstanding, int claims) { }
        List<Row> rows = jdbc.sql("""
                SELECT currency, sum(approved_amount) AS approved, sum(adjustment_amount) AS adjusted, sum(paid_amount) AS paid,
                       sum(greatest(outstanding_amount, 0)) AS outstanding, count(*) AS claims
                  FROM fin.v_claim_balance WHERE staff_id = :me AND payment_status <> 'CANCELLED'
                 GROUP BY currency ORDER BY count(*) DESC
                """).param("me", me).query(Row.class).list();
        int openQueries = jdbc.sql("SELECT count(*) FROM fin.claim_query WHERE staff_id = :me AND status IN ('OPEN','RESPONDED','REOPENED','ESCALATED')")
                .param("me", me).query(Integer.class).single();
        OffsetDateTime asAt = jdbc.sql("""
                SELECT max(published_at) FROM (SELECT published_at FROM fin.claim WHERE staff_id = :me
                                               UNION ALL SELECT published_at FROM fin.payment WHERE staff_id = :me) x
                """).param("me", me).query(OffsetDateTime.class).optional().orElse(null);
        if (rows.isEmpty()) {
            return List.of(new FinTypes.Summary("TZS", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, openQueries, asAt));
        }
        return rows.stream().map(r -> {
            BigDecimal owed = r.approved().add(r.adjusted());
            BigDecimal pct = owed.signum() <= 0 ? BigDecimal.ZERO
                    : r.paid().multiply(BigDecimal.valueOf(100)).divide(owed, 1, RoundingMode.HALF_UP).min(BigDecimal.valueOf(100));
            return new FinTypes.Summary(r.currency(), owed, r.paid(), r.outstanding(), pct, r.claims(), openQueries, asAt);
        }).toList();
    }

    private static final String CLAIM_SELECT = """
            SELECT v.public_id, c.source_ref, t.code AS claim_type_code, t.name_en AS claim_type_en, t.name_sw AS claim_type_sw,
                   v.description, v.currency, v.approval_date, v.approved_amount, v.paid_amount, v.adjustment_amount,
                   v.outstanding_amount, v.payment_status
              FROM fin.v_claim_balance v
              JOIN fin.claim c ON c.id = v.claim_id
              JOIN fin.claim_type t ON t.id = v.claim_type_id
            """;

    @Transactional(readOnly = true)
    public PageResult<FinTypes.ClaimLine> claims(String status, String type, String q, Integer page, Integer size) {
        long me = staffId();
        int p = PageResult.clampPage(page), sz = PageResult.clampSize(size);
        StringBuilder where = new StringBuilder(" WHERE v.staff_id = :me");
        if (status != null && !status.isBlank()) {
            where.append(" AND v.payment_status = :st");
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND t.code = :t");
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (v.description ILIKE :q OR c.source_ref ILIKE :q)");
        }
        String countSql = "SELECT count(*) FROM fin.v_claim_balance v JOIN fin.claim c ON c.id = v.claim_id JOIN fin.claim_type t ON t.id = v.claim_type_id" + where;
        var count = jdbc.sql(countSql).param("me", me);
        var list = jdbc.sql(CLAIM_SELECT + where + " ORDER BY v.approval_date DESC, v.claim_id DESC LIMIT :lim OFFSET :off").param("me", me);
        if (status != null && !status.isBlank()) {
            count = count.param("st", status.toUpperCase());
            list = list.param("st", status.toUpperCase());
        }
        if (type != null && !type.isBlank()) {
            count = count.param("t", type.toUpperCase());
            list = list.param("t", type.toUpperCase());
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            count = count.param("q", like);
            list = list.param("q", like);
        }
        return new PageResult<>(list.param("lim", sz).param("off", p * sz).query(FinTypes.ClaimLine.class).list(),
                count.query(Long.class).single(), p, sz);
    }

    @Transactional(readOnly = true)
    public FinTypes.ClaimDetail claim(UUID publicId) {
        long me = staffId();
        FinTypes.ClaimLine line = jdbc.sql(CLAIM_SELECT + " WHERE v.public_id = :id AND v.staff_id = :me").param("id", publicId).param("me", me)
                .query(FinTypes.ClaimLine.class).optional().orElseThrow(ApiException::notFound);
        String voucher = jdbc.sql("SELECT voucher_ref FROM fin.claim WHERE public_id = :id").param("id", publicId).query(String.class)
                .optional().orElse(null);
        List<FinTypes.Allocation> allocations = jdbc.sql("""
                SELECT a.id, p.paid_on, p.payment_ref, p.source_ref AS payment_source_ref, a.amount, a.amount < 0 AS reversal, a.published_at
                  FROM fin.payment_allocation a JOIN fin.payment p ON p.id = a.payment_id JOIN fin.claim c ON c.id = a.claim_id
                 WHERE c.public_id = :id AND a.published_at IS NOT NULL
                 ORDER BY p.paid_on, a.id
                """).param("id", publicId).query(FinTypes.Allocation.class).list();
        List<FinTypes.Adjustment> adjustments = jdbc.sql("""
                SELECT j.source_ref, j.amount, j.reason, j.published_at
                  FROM fin.adjustment j JOIN fin.claim c ON c.id = j.claim_id
                 WHERE c.public_id = :id AND j.published_at IS NOT NULL ORDER BY j.published_at, j.id
                """).param("id", publicId).query(FinTypes.Adjustment.class).list();
        List<FinTypes.QuerySummary> queries = jdbc.sql(QUERY_SUMMARY + " WHERE c.public_id = :id AND q.staff_id = :me ORDER BY q.created_at DESC")
                .param("id", publicId).param("me", me).query(FinTypes.QuerySummary.class).list();
        return new FinTypes.ClaimDetail(line, voucher, allocations, adjustments, queries);
    }

    @Transactional
    public FinTypes.Statement statement(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusYears(1).plusDays(1) : from;
        if (start.isAfter(end) || start.isBefore(end.minusYears(5))) {
            throw ApiException.badRequest("ICCP-FIN-030", "Choose a valid statement period of no more than five years.");
        }
        IccpPrincipal me = CurrentUser.get();
        List<FinTypes.ClaimLine> lines = jdbc.sql(CLAIM_SELECT + """
                 WHERE v.staff_id = :me AND v.approval_date BETWEEN :from AND :to
                 ORDER BY v.approval_date, v.claim_id
                """).param("me", me.staffId()).param("from", start).param("to", end).query(FinTypes.ClaimLine.class).list();
        List<FinTypes.Summary> totals = summaryFor(lines, summary().stream().map(FinTypes.Summary::asAt).filter(java.util.Objects::nonNull)
                .max(OffsetDateTime::compareTo).orElse(null));
        audit.record("FIN", "STATEMENT_GENERATE", "staff_statement", me.staffPublicId(),
                Map.of("from", start.toString(), "to", end.toString()));
        OffsetDateTime asAt = totals.stream().map(FinTypes.Summary::asAt).filter(java.util.Objects::nonNull)
                .max(OffsetDateTime::compareTo).orElse(null);
        return new FinTypes.Statement(me.employeeNo(), me.displayName(), start, end, OffsetDateTime.now(), asAt, totals, lines);
    }

    private static List<FinTypes.Summary> summaryFor(List<FinTypes.ClaimLine> lines, OffsetDateTime asAt) {
        return lines.stream().collect(java.util.stream.Collectors.groupingBy(FinTypes.ClaimLine::currency,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.toList())).entrySet().stream().map(e -> {
            BigDecimal approved = e.getValue().stream().map(c -> c.approvedAmount().add(c.adjustmentAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal paid = e.getValue().stream().map(FinTypes.ClaimLine::paidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal outstanding = e.getValue().stream().map(FinTypes.ClaimLine::outstandingAmount)
                    .map(v -> v.max(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal pct = approved.signum() <= 0 ? BigDecimal.ZERO : paid.multiply(BigDecimal.valueOf(100))
                    .divide(approved, 1, RoundingMode.HALF_UP).min(BigDecimal.valueOf(100));
            return new FinTypes.Summary(e.getKey(), approved, paid, outstanding, pct, e.getValue().size(), 0, asAt);
        }).toList();
    }

    // ---------------------------------------------------------------- queries (BP-FIN-02)

    private static final String QUERY_SUMMARY = """
            SELECT q.public_id, c.source_ref AS claim_ref, q.category, q.description, q.status, q.reopen_count, q.due_at, q.created_at,
                   coalesce(q.updated_at, q.created_at) AS updated_at
              FROM fin.claim_query q JOIN fin.claim c ON c.id = q.claim_id
            """;

    @Transactional(readOnly = true)
    public List<FinTypes.QuerySummary> myQueries() {
        return jdbc.sql(QUERY_SUMMARY + " WHERE q.staff_id = :me ORDER BY coalesce(q.updated_at, q.created_at) DESC")
                .param("me", staffId()).query(FinTypes.QuerySummary.class).list();
    }

    @Transactional
    public UUID raise(UUID claimPublicId, FinTypes.NewQuery in) {
        long me = staffId();
        record C(long id, String sourceRef) { }
        C c = jdbc.sql("SELECT id, source_ref FROM fin.claim WHERE public_id = :p AND staff_id = :me AND published_at IS NOT NULL")
                .param("p", claimPublicId).param("me", me).query(C.class).optional().orElseThrow(ApiException::notFound);
        boolean open = jdbc.sql("SELECT EXISTS (SELECT 1 FROM fin.claim_query WHERE claim_id = :c AND status IN ('OPEN','RESPONDED','REOPENED','ESCALATED'))")
                .param("c", c.id()).query(Boolean.class).single();
        if (open) {
            throw ApiException.conflict("ICCP-FIN-025", "fin.queryAlreadyOpen");
        }
        UUID id = jdbc.sql("""
                INSERT INTO fin.claim_query (staff_id, claim_id, category, description, status, due_at)
                VALUES (:me, :c, :cat, :d, 'OPEN', :due) RETURNING public_id
                """).param("me", me).param("c", c.id()).param("cat", in.category()).param("d", in.description().trim())
                .param("due", workingDays.plus(OffsetDateTime.now(), RESPONSE_DAYS)).query(UUID.class).single();
        notifyFinance(id, c.sourceRef());
        audit.record("FIN", "QUERY_RAISE", "claim_query", id, Map.of("category", in.category()));
        return id;
    }

    @Transactional
    public FinTypes.QueryDetail query(UUID publicId) {
        return detail(load(publicId));
    }

    /** A message from Finance marks the query RESPONDED and tells the staff member; a staff message just adds to the thread. */
    @Transactional
    public FinTypes.QueryDetail post(UUID publicId, FinTypes.MessageInput in) {
        IccpPrincipal me = CurrentUser.get();
        Q q = load(publicId);
        boolean finance = isFinance(me) && !mine(q, me);
        if (!ACTIVE.contains(q.status()) || (!finance && !mine(q, me))) {
            throw ApiException.conflict("ICCP-FIN-026", "fin.queryClosed");
        }
        jdbc.sql("INSERT INTO fin.query_message (query_id, author_staff_id, body) VALUES (:q, :a, :b)")
                .param("q", q.id()).param("a", me.staffId()).param("b", in.body().trim()).update();
        if (finance) {
            jdbc.sql("UPDATE fin.claim_query SET status = 'RESPONDED', assigned_to_staff_id = coalesce(assigned_to_staff_id, :a) WHERE id = :q")
                    .param("a", me.staffId()).param("q", q.id()).update();
            notifier.notify(new NotificationRequest(List.of(q.staffId()), "FinanceQueryResponded", NtfCategory.FINANCE,
                    Map.of("ref", q.claimRef()), "/finance/queries/" + publicId, EnumSet.of(NotificationRequest.Channel.EMAIL), "FIN", publicId));
            audit.record("FIN", "QUERY_RESPOND", "claim_query", publicId, Map.of());
        }
        return detail(load(publicId));
    }

    @Transactional
    public FinTypes.QueryDetail confirm(UUID publicId) {
        Q q = ownActive(publicId, Set.of("RESPONDED"));
        jdbc.sql("UPDATE fin.claim_query SET status = 'RESOLVED' WHERE id = :q").param("q", q.id()).update();
        audit.record("FIN", "QUERY_CONFIRM", "claim_query", publicId, Map.of());
        return detail(load(publicId));
    }

    /** Staff may reopen twice; a third disagreement escalates to the Finance reviewer. */
    @Transactional
    public FinTypes.QueryDetail reopen(UUID publicId, FinTypes.MessageInput in) {
        IccpPrincipal me = CurrentUser.get();
        Q q = ownActive(publicId, Set.of("RESPONDED", "RESOLVED"));
        jdbc.sql("INSERT INTO fin.query_message (query_id, author_staff_id, body) VALUES (:q, :a, :b)")
                .param("q", q.id()).param("a", me.staffId()).param("b", in.body().trim()).update();
        if (q.reopenCount() >= MAX_REOPEN) {
            escalate(q, publicId);
        } else {
            jdbc.sql("UPDATE fin.claim_query SET status = 'REOPENED', reopen_count = reopen_count + 1, due_at = :due WHERE id = :q")
                    .param("due", workingDays.plus(OffsetDateTime.now(), RESPONSE_DAYS)).param("q", q.id()).update();
            notifyFinance(publicId, q.claimRef());
            audit.record("FIN", "QUERY_REOPEN", "claim_query", publicId, Map.of());
        }
        return detail(load(publicId));
    }

    @Transactional
    public FinTypes.QueryDetail close(UUID publicId) {
        IccpPrincipal me = CurrentUser.get();
        if (!isFinance(me)) {
            throw ApiException.forbidden();
        }
        Q q = load(publicId);
        if (!Set.of("RESOLVED", "RESPONDED").contains(q.status())) {
            throw ApiException.conflict("ICCP-FIN-026", "fin.queryClosed");
        }
        jdbc.sql("UPDATE fin.claim_query SET status = 'CLOSED' WHERE id = :q").param("q", q.id()).update();
        audit.record("FIN", "QUERY_CLOSE", "claim_query", publicId, Map.of());
        return detail(load(publicId));
    }

    @Transactional
    public FinTypes.QueryDetail escalateByFinance(UUID publicId) {
        if (!isFinance(CurrentUser.get())) {
            throw ApiException.forbidden();
        }
        Q q = load(publicId);
        if (!ACTIVE.contains(q.status())) {
            throw ApiException.conflict("ICCP-FIN-026", "fin.queryClosed");
        }
        escalate(q, publicId);
        return detail(load(publicId));
    }

    private void escalate(Q q, UUID publicId) {
        jdbc.sql("UPDATE fin.claim_query SET status = 'ESCALATED', due_at = :due WHERE id = :q")
                .param("due", workingDays.plus(OffsetDateTime.now(), RESPONSE_DAYS)).param("q", q.id()).update();
        Set<Long> reviewers = new HashSet<>(roles.staffWithRole(Roles.FIN_REVIEWER));
        notifier.notify(NotificationRequest.inApp(reviewers, "FinanceQueryRaised", NtfCategory.APPROVALS, Map.of("ref", q.claimRef()),
                "/finance/desk/" + publicId, "FIN", publicId));
        audit.record("FIN", "QUERY_ESCALATE", "claim_query", publicId, Map.of());
    }

    /** Finance query desk (SCR-FIN-06). */
    @Transactional(readOnly = true)
    public PageResult<FinTypes.DeskItem> desk(String status, Integer page, Integer size) {
        FinanceBatchService.requireFinance();
        int p = PageResult.clampPage(page), sz = PageResult.clampSize(size);
        String where = switch (status == null ? "active" : status.toLowerCase()) {
            case "all" -> "";
            case "overdue" -> " WHERE q.status IN ('OPEN','REOPENED','ESCALATED') AND q.due_at < now()";
            case "active" -> " WHERE q.status IN ('OPEN','RESPONDED','REOPENED','ESCALATED')";
            default -> " WHERE q.status = '" + status.toUpperCase().replaceAll("[^A-Z]", "") + "'";
        };
        long total = jdbc.sql("SELECT count(*) FROM fin.claim_query q" + where).query(Long.class).single();
        List<FinTypes.DeskItem> items = jdbc.sql("""
                SELECT q.public_id, c.source_ref AS claim_ref, concat_ws(' ', s.first_name, s.last_name) AS staff_name, u.name_en AS unit_name,
                       q.category, q.status, q.reopen_count, q.due_at, q.created_at,
                       (q.status IN ('OPEN','REOPENED','ESCALATED') AND q.due_at < now()) AS overdue
                  FROM fin.claim_query q
                  JOIN fin.claim c ON c.id = q.claim_id
                  JOIN dir.staff_member s ON s.id = q.staff_id
                  JOIN dir.org_unit u ON u.id = s.org_unit_id""" + where + """

                 ORDER BY (q.status = 'ESCALATED') DESC, q.due_at NULLS LAST, q.id
                 LIMIT :lim OFFSET :off
                """).param("lim", sz).param("off", p * sz).query(FinTypes.DeskItem.class).list();
        return new PageResult<>(items, total, p, sz);
    }

    // ---------------------------------------------------------------- helpers

    record Q(long id, UUID publicId, long staffId, String status, int reopenCount, String claimRef) {
    }

    /** Loads a query the caller may see: their own, or any query for Finance. Others get 404. */
    private Q load(UUID publicId) {
        IccpPrincipal me = CurrentUser.get();
        Q q = jdbc.sql("""
                SELECT q.id, q.public_id, q.staff_id, q.status, q.reopen_count, c.source_ref AS claim_ref
                  FROM fin.claim_query q JOIN fin.claim c ON c.id = q.claim_id WHERE q.public_id = :p
                """).param("p", publicId).query(Q.class).optional().orElseThrow(ApiException::notFound);
        if (!mine(q, me) && !isFinance(me)) {
            throw ApiException.notFound();
        }
        return q;
    }

    private Q ownActive(UUID publicId, Set<String> allowed) {
        Q q = load(publicId);
        if (!mine(q, CurrentUser.get())) {
            throw ApiException.forbidden();
        }
        if (!allowed.contains(q.status())) {
            throw ApiException.conflict("ICCP-FIN-026", "fin.queryClosed");
        }
        return q;
    }

    private FinTypes.QueryDetail detail(Q q) {
        IccpPrincipal me = CurrentUser.get();
        boolean own = mine(q, me);
        boolean finance = isFinance(me) && !own;
        record Head(UUID claimPublicId, String claimDescription, UUID staffPublicId, String staffName, String category, String description,
                    OffsetDateTime dueAt, OffsetDateTime createdAt, String assignedTo) { }
        Head h = jdbc.sql("""
                SELECT c.public_id AS claim_public_id, c.description AS claim_description, s.public_id AS staff_public_id,
                       concat_ws(' ', s.first_name, s.last_name) AS staff_name, q.category, q.description, q.due_at, q.created_at,
                       concat_ws(' ', a.first_name, a.last_name) AS assigned_to
                  FROM fin.claim_query q JOIN fin.claim c ON c.id = q.claim_id JOIN dir.staff_member s ON s.id = q.staff_id
                  LEFT JOIN dir.staff_member a ON a.id = q.assigned_to_staff_id
                 WHERE q.id = :q
                """).param("q", q.id()).query(Head.class).single();
        List<FinTypes.QueryMessage> messages = jdbc.sql("""
                SELECT concat_ws(' ', s.first_name, s.last_name) AS author_name, (m.author_staff_id <> q.staff_id) AS from_finance, m.body, m.created_at
                  FROM fin.query_message m JOIN fin.claim_query q ON q.id = m.query_id JOIN dir.staff_member s ON s.id = m.author_staff_id
                 WHERE m.query_id = :q ORDER BY m.created_at, m.id
                """).param("q", q.id()).query(FinTypes.QueryMessage.class).list();
        boolean active = ACTIVE.contains(q.status());
        return new FinTypes.QueryDetail(q.publicId(), h.claimPublicId(), q.claimRef(), h.claimDescription(), h.staffPublicId(), h.staffName(),
                h.category(), h.description(), q.status(), q.reopenCount(), h.dueAt(), h.createdAt(), blank(h.assignedTo()), messages,
                active && (finance || own), own && "RESPONDED".equals(q.status()),
                own && Set.of("RESPONDED", "RESOLVED").contains(q.status()));
    }

    private void notifyFinance(UUID queryId, String claimRef) {
        notifier.notify(NotificationRequest.inApp(roles.staffWithRole(Roles.FIN_OFFICER), "FinanceQueryRaised", NtfCategory.APPROVALS,
                Map.of("ref", claimRef), "/finance/desk/" + queryId, "FIN", queryId));
    }

    private static boolean mine(Q q, IccpPrincipal p) {
        return p.staffId() == q.staffId();
    }

    private static boolean isFinance(IccpPrincipal p) {
        return p.roles().contains(Roles.FIN_OFFICER) || p.roles().contains(Roles.FIN_REVIEWER);
    }

    private static long staffId() {
        return CurrentUser.get().staffId();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
