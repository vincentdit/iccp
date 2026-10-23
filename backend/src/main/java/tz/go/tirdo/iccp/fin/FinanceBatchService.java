package tz.go.tirdo.iccp.fin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.outbox.OutboxWriter;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;
import tz.go.tirdo.iccp.platform.security.Roles;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.platform.web.PageResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finance workspace (SCR-FIN-04..07): batch review and maker-checker publication (BP-FIN-01 steps
 * 5–7), quarantine resolution, manual allocations and the liability dashboard.
 */
@Service
public class FinanceBatchService {

    public static final String BATCH_PUBLISHED = "FinanceBatchPublished";

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final OutboxWriter outbox;

    public FinanceBatchService(JdbcClient jdbc, AuditService audit, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
    }

    private static final String BATCH_SELECT = """
            SELECT b.public_id, b.feed_type, b.file_name, b.status, b.source_record_count, b.source_amount_total, b.loaded_count,
                   b.quarantined_count,
                   (SELECT count(*) FROM fin.quarantine_item q WHERE q.batch_id = b.id AND q.status = 'OPEN') AS open_quarantine,
                   b.imported_by, b.reviewed_by, b.reviewed_at, b.published_at, b.created_at,
                   (b.status = 'RECONCILED' AND b.imported_by <> :me AND :reviewer) AS can_approve
              FROM fin.import_batch b
            """;

    @Transactional(readOnly = true)
    public PageResult<FinTypes.Batch> batches(String status, Integer page, Integer size) {
        IccpPrincipal me = requireFinance();
        int p = PageResult.clampPage(page), sz = PageResult.clampSize(size);
        String where = status == null || status.isBlank() ? "" : " WHERE b.status = :st";
        var count = jdbc.sql("SELECT count(*) FROM fin.import_batch b" + where);
        var list = jdbc.sql(BATCH_SELECT + where + " ORDER BY b.created_at DESC, b.id DESC LIMIT :lim OFFSET :off");
        if (!where.isEmpty()) {
            count = count.param("st", status.toUpperCase());
            list = list.param("st", status.toUpperCase());
        }
        long total = count.query(Long.class).single();
        List<FinTypes.Batch> items = list.param("me", me.subject()).param("reviewer", me.roles().contains(Roles.FIN_REVIEWER))
                .param("lim", sz).param("off", p * sz).query(FinTypes.Batch.class).list();
        return new PageResult<>(items, total, p, sz);
    }

    @Transactional(readOnly = true)
    public FinTypes.Batch batch(UUID publicId) {
        IccpPrincipal me = requireFinance();
        return jdbc.sql(BATCH_SELECT + " WHERE b.public_id = :id").param("id", publicId).param("me", me.subject())
                .param("reviewer", me.roles().contains(Roles.FIN_REVIEWER)).query(FinTypes.Batch.class).optional()
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public List<FinTypes.QuarantineItem> quarantine(UUID batchPublicId, boolean openOnly) {
        requireFinance();
        return jdbc.sql("""
                SELECT q.id, q.row_no, q.reason_code, q.payload::text AS payload, q.status, q.resolution_note, q.resolved_by, q.resolved_at
                  FROM fin.quarantine_item q JOIN fin.import_batch b ON b.id = q.batch_id
                 WHERE b.public_id = :b""" + (openOnly ? " AND q.status = 'OPEN'" : "") + " ORDER BY q.row_no")
                .param("b", batchPublicId).query(FinTypes.QuarantineItem.class).list();
    }

    /** Checker approval: publishes everything loaded by the batch to staff in one transaction. */
    @Transactional
    public FinTypes.Batch approve(UUID publicId) {
        IccpPrincipal me = CurrentUser.get();
        CurrentUser.require(Roles.FIN_REVIEWER);
        record B(long id, String status, String importedBy) { }
        B b = jdbc.sql("SELECT id, status, imported_by FROM fin.import_batch WHERE public_id = :p FOR UPDATE")
                .param("p", publicId).query(B.class).optional().orElseThrow(ApiException::notFound);
        if (!"RECONCILED".equals(b.status())) {
            throw ApiException.conflict("ICCP-FIN-023", "fin.batchNotReady");
        }
        if (b.importedBy().equals(me.subject())) {
            throw ApiException.conflict("ICCP-FIN-024", "fin.makerChecker");
        }
        jdbc.sql("UPDATE fin.claim SET published_at = now() WHERE batch_id = :b AND published_at IS NULL").param("b", b.id()).update();
        jdbc.sql("UPDATE fin.payment SET published_at = now() WHERE batch_id = :b AND published_at IS NULL").param("b", b.id()).update();
        jdbc.sql("UPDATE fin.adjustment SET published_at = now() WHERE batch_id = :b AND published_at IS NULL").param("b", b.id()).update();
        // Feed allocations (and approved manual ones) become visible once both sides are visible.
        jdbc.sql("""
                UPDATE fin.payment_allocation a SET published_at = now()
                  FROM fin.payment p, fin.claim c
                 WHERE p.id = a.payment_id AND c.id = a.claim_id AND a.published_at IS NULL
                   AND (a.allocated_by = 'FINANCE_FEED' OR a.approved_by IS NOT NULL)
                   AND p.published_at IS NOT NULL AND c.published_at IS NOT NULL
                """).update();
        jdbc.sql("""
                UPDATE fin.import_batch SET status = 'PUBLISHED', reviewed_by = :me, reviewed_at = now(), published_at = now() WHERE id = :b
                """).param("me", me.subject()).param("b", b.id()).update();
        List<Long> staff = jdbc.sql("""
                SELECT staff_id FROM fin.claim WHERE batch_id = :b
                UNION SELECT staff_id FROM fin.payment WHERE batch_id = :b
                UNION SELECT c.staff_id FROM fin.adjustment j JOIN fin.claim c ON c.id = j.claim_id WHERE j.batch_id = :b
                """).param("b", b.id()).query(Long.class).list();
        outbox.write("FinanceBatch", publicId, BATCH_PUBLISHED, Map.of("staffIds", staff));
        audit.record("FIN", "BATCH_PUBLISH", "import_batch", publicId, Map.of("staff", staff.size()));
        return batch(publicId);
    }

    /** Checker rejection: nothing is published and the staged rows are removed so a corrected file can be loaded. */
    @Transactional
    public FinTypes.Batch reject(UUID publicId, String reason) {
        IccpPrincipal me = CurrentUser.get();
        CurrentUser.require(Roles.FIN_REVIEWER);
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("ICCP-FIN-022", "fin.reasonRequired");
        }
        record B(long id, String status, String importedBy) { }
        B b = jdbc.sql("SELECT id, status, imported_by FROM fin.import_batch WHERE public_id = :p FOR UPDATE")
                .param("p", publicId).query(B.class).optional().orElseThrow(ApiException::notFound);
        if (!"RECONCILED".equals(b.status())) {
            throw ApiException.conflict("ICCP-FIN-023", "fin.batchNotReady");
        }
        if (b.importedBy().equals(me.subject())) {
            throw ApiException.conflict("ICCP-FIN-024", "fin.makerChecker");
        }
        jdbc.sql("""
                DELETE FROM fin.payment_allocation a
                 WHERE a.published_at IS NULL
                   AND (a.claim_id IN (SELECT id FROM fin.claim WHERE batch_id = :b) OR a.payment_id IN (SELECT id FROM fin.payment WHERE batch_id = :b)
                        -- feed allocations have no batch column; they were inserted in the import transaction, so share its now()
                        OR (a.allocated_by = 'FINANCE_FEED' AND a.created_at = (SELECT created_at FROM fin.import_batch WHERE id = :b)))
                """).param("b", b.id()).update();
        jdbc.sql("DELETE FROM fin.adjustment WHERE batch_id = :b AND published_at IS NULL").param("b", b.id()).update();
        jdbc.sql("DELETE FROM fin.claim WHERE batch_id = :b AND published_at IS NULL").param("b", b.id()).update();
        jdbc.sql("DELETE FROM fin.payment WHERE batch_id = :b AND published_at IS NULL").param("b", b.id()).update();
        jdbc.sql("""
                UPDATE fin.quarantine_item SET status = 'REJECTED', resolution_note = :n, resolved_by = :me, resolved_at = now()
                 WHERE batch_id = :b AND status = 'OPEN'
                """).param("n", "Batch rejected: " + reason).param("me", me.subject()).param("b", b.id()).update();
        // Allow the corrected file — or the same file after a reference fix — to be imported again.
        jdbc.sql("UPDATE fin.import_batch SET status = 'REJECTED', reviewed_by = :me, reviewed_at = now(), checksum = 'R' || substr(checksum, 2) WHERE id = :b")
                .param("me", me.subject()).param("b", b.id()).update();
        audit.record("FIN", "BATCH_REJECT", "import_batch", publicId, Map.of("reason", reason));
        return batch(publicId);
    }

    @Transactional
    public void closeQuarantine(long itemId, FinTypes.CloseQuarantine in) {
        IccpPrincipal me = CurrentUser.get();
        CurrentUser.require(Roles.FIN_OFFICER, Roles.FIN_REVIEWER);
        int n = jdbc.sql("""
                UPDATE fin.quarantine_item SET status = :s, resolution_note = :n, resolved_by = :me, resolved_at = now()
                 WHERE id = :id AND status = 'OPEN'
                """).param("s", in.status()).param("n", in.note()).param("me", me.subject()).param("id", itemId).update();
        if (n == 0) {
            throw ApiException.notFound();
        }
        audit.record("FIN", "QUARANTINE_" + in.status(), "quarantine_item", itemId, Map.of("note", in.note()));
    }

    // ---------------------------------------------------------------- manual allocations (maker-checker)

    @Transactional
    public long allocate(FinTypes.ManualAllocation in) {
        IccpPrincipal me = CurrentUser.get();
        CurrentUser.require(Roles.FIN_OFFICER);
        if (in.amount() == null || in.amount().signum() == 0 || in.amount().scale() > 2) {
            throw ApiException.badRequest("ICCP-FIN-020", "fin.invalidAmount");
        }
        long payment = jdbc.sql("SELECT id FROM fin.payment WHERE source_ref = :r").param("r", in.paymentSourceRef()).query(Long.class)
                .optional().orElseThrow(ApiException::notFound);
        long claim = jdbc.sql("SELECT id FROM fin.claim WHERE source_ref = :r").param("r", in.claimSourceRef()).query(Long.class)
                .optional().orElseThrow(ApiException::notFound);
        Long reverses = null;
        if (in.reversesSourceRef() != null && !in.reversesSourceRef().isBlank()) {
            reverses = jdbc.sql("SELECT id FROM fin.payment_allocation WHERE source_ref = :r OR CAST(id AS text) = :r LIMIT 1")
                    .param("r", in.reversesSourceRef().trim()).query(Long.class).optional().orElseThrow(ApiException::notFound);
        }
        long id = jdbc.sql("""
                INSERT INTO fin.payment_allocation (payment_id, claim_id, amount, reverses_allocation_id, allocated_by)
                VALUES (:p, :c, :a, :r, :me) RETURNING id
                """).param("p", payment).param("c", claim).param("a", in.amount()).param("r", reverses).param("me", me.subject())
                .query(Long.class).single();
        audit.record("FIN", "ALLOCATION_CREATE", "payment_allocation", id,
                Map.of("payment", in.paymentSourceRef(), "claim", in.claimSourceRef(), "amount", in.amount()));
        return id;
    }

    @Transactional(readOnly = true)
    public List<FinTypes.PendingAllocation> pendingAllocations() {
        IccpPrincipal me = requireFinance();
        return jdbc.sql("""
                SELECT a.id, p.source_ref AS payment_source_ref, c.source_ref AS claim_source_ref,
                       concat_ws(' ', s.first_name, s.last_name) AS staff_name, a.amount, a.allocated_by, a.created_at,
                       (a.allocated_by <> :me AND :reviewer) AS can_approve
                  FROM fin.payment_allocation a
                  JOIN fin.payment p ON p.id = a.payment_id
                  JOIN fin.claim c ON c.id = a.claim_id
                  JOIN dir.staff_member s ON s.id = c.staff_id
                 WHERE a.approved_by IS NULL AND a.allocated_by <> 'FINANCE_FEED' AND a.published_at IS NULL
                 ORDER BY a.created_at
                """).param("me", me.subject()).param("reviewer", me.roles().contains(Roles.FIN_REVIEWER))
                .query(FinTypes.PendingAllocation.class).list();
    }

    @Transactional
    public void approveAllocation(long id) {
        IccpPrincipal me = CurrentUser.get();
        CurrentUser.require(Roles.FIN_REVIEWER);
        record A(String allocatedBy, long staffId, UUID claimPublicId, boolean visible) { }
        A a = jdbc.sql("""
                SELECT a.allocated_by, c.staff_id, c.public_id AS claim_public_id,
                       (c.published_at IS NOT NULL AND p.published_at IS NOT NULL) AS visible
                  FROM fin.payment_allocation a JOIN fin.claim c ON c.id = a.claim_id JOIN fin.payment p ON p.id = a.payment_id
                 WHERE a.id = :id AND a.approved_by IS NULL AND a.allocated_by <> 'FINANCE_FEED' FOR UPDATE OF a
                """).param("id", id).query(A.class).optional().orElseThrow(ApiException::notFound);
        if (a.allocatedBy().equals(me.subject())) {
            throw ApiException.conflict("ICCP-FIN-024", "fin.makerChecker");
        }
        jdbc.sql("UPDATE fin.payment_allocation SET approved_by = :me, published_at = CASE WHEN :v THEN now() END WHERE id = :id")
                .param("me", me.subject()).param("v", a.visible()).param("id", id).update();
        if (a.visible()) {
            outbox.write("FinanceBatch", a.claimPublicId(), BATCH_PUBLISHED, Map.of("staffIds", List.of(a.staffId())));
        }
        audit.record("FIN", "ALLOCATION_APPROVE", "payment_allocation", id, Map.of());
    }

    @Transactional
    public void rejectAllocation(long id) {
        IccpPrincipal me = CurrentUser.get();
        CurrentUser.require(Roles.FIN_REVIEWER);
        int n = jdbc.sql("""
                DELETE FROM fin.payment_allocation WHERE id = :id AND approved_by IS NULL AND allocated_by NOT IN ('FINANCE_FEED', :me)
                   AND published_at IS NULL
                """).param("id", id).param("me", me.subject()).update();
        if (n == 0) {
            throw ApiException.notFound();
        }
        audit.record("FIN", "ALLOCATION_REJECT", "payment_allocation", id, Map.of());
    }

    // ---------------------------------------------------------------- dashboard (SCR-FIN-07)

    @Transactional(readOnly = true)
    public FinTypes.Dashboard dashboard() {
        requireFinance();
        List<FinTypes.StatusCount> batches = jdbc.sql("SELECT status, count(*) AS count FROM fin.import_batch GROUP BY status ORDER BY status")
                .query(FinTypes.StatusCount.class).list();
        long openQ = jdbc.sql("SELECT count(*) FROM fin.quarantine_item WHERE status = 'OPEN'").query(Long.class).single();
        long pending = jdbc.sql("""
                SELECT count(*) FROM fin.payment_allocation WHERE approved_by IS NULL AND allocated_by <> 'FINANCE_FEED' AND published_at IS NULL
                """).query(Long.class).single();
        List<FinTypes.AgeBucket> ages = jdbc.sql("""
                SELECT bucket, count(*) AS count FROM (
                  SELECT CASE WHEN now() - created_at < interval '3 days' THEN '0-2d'
                              WHEN now() - created_at < interval '8 days' THEN '3-7d'
                              ELSE '8d+' END AS bucket
                    FROM fin.claim_query WHERE status IN ('OPEN','REOPENED','ESCALATED')) x
                 GROUP BY bucket ORDER BY bucket
                """).query(FinTypes.AgeBucket.class).list();
        List<FinTypes.TypeLiability> liabilities = jdbc.sql("""
                SELECT t.code AS claim_type_code, t.name_en AS claim_type_en, v.currency, count(*) AS claims, sum(v.outstanding_amount) AS outstanding
                  FROM fin.v_claim_balance v JOIN fin.claim_type t ON t.id = v.claim_type_id
                 WHERE v.payment_status <> 'CANCELLED' AND v.outstanding_amount > 0
                 GROUP BY t.code, t.name_en, v.currency ORDER BY v.currency = 'TZS' DESC, outstanding DESC
                """).query(FinTypes.TypeLiability.class).list();
        BigDecimal total = liabilities.stream().filter(l -> "TZS".equals(l.currency())).map(FinTypes.TypeLiability::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        long staff = jdbc.sql("""
                SELECT count(DISTINCT staff_id) FROM fin.v_claim_balance WHERE payment_status <> 'CANCELLED' AND outstanding_amount > 0
                """).query(Long.class).single();
        return new FinTypes.Dashboard(batches, openQ, pending, ages, liabilities, total, staff, OffsetDateTime.now());
    }

    static IccpPrincipal requireFinance() {
        CurrentUser.require(Roles.FIN_OFFICER, Roles.FIN_REVIEWER);
        return CurrentUser.get();
    }
}
