package tz.go.tirdo.iccp.fin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.ntf.api.NotificationRequest;
import tz.go.tirdo.iccp.ntf.api.Notifier;
import tz.go.tirdo.iccp.ntf.api.NtfCategory;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.csv.CsvReader;
import tz.go.tirdo.iccp.platform.db.DbSessionContext;
import tz.go.tirdo.iccp.platform.db.NestedTx;
import tz.go.tirdo.iccp.platform.security.Roles;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.sec.api.RoleDirectory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Loads a Finance export into a staging batch (BP-FIN-01 steps 1–4; FR-FIN-01..05).
 *
 * <p>Format (Doc 03 §6.1), one UTF-8 CSV with a record type in the first column:
 * <pre>
 * H,file_id,extracted_at,source_system,period
 * C,source_ref,employee_no,claim_type_code,description,approved_amount,currency,approval_date,voucher_ref,status(APPROVED|CANCELLED)
 * P,source_ref,employee_no,amount,currency,paid_on,payment_ref,status(CONFIRMED|CANCELLED)
 * A,source_ref,payment_source_ref,claim_source_ref,amount(negative = reversal),reverses_source_ref
 * J,source_ref,claim_source_ref,amount,reason
 * T,record_count,claims_total,payments_total,allocations_total
 * </pre>
 * Rules: the same file twice is a no-op; a trailer that does not match the detail rows rejects the
 * whole batch; each detail row is applied in its own savepoint, and rows that fail validation or a
 * database rule (e.g. over-allocation, ICCP-FIN-007/008) go to quarantine. Records already visible
 * to staff never change through a later file: Finance corrects them with adjustments (J) and
 * reversals (negative A). Nothing is visible to staff until a different reviewer approves the batch.
 */
@Service
public class FinanceImportService {

    private final JdbcClient jdbc;
    private final NestedTx nested;
    private final AuditService audit;
    private final Notifier notifier;
    private final RoleDirectory roles;
    private final ObjectMapper mapper;

    public FinanceImportService(JdbcClient jdbc, NestedTx nested, AuditService audit, Notifier notifier, RoleDirectory roles, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.nested = nested;
        this.audit = audit;
        this.notifier = notifier;
        this.roles = roles;
        this.mapper = mapper;
    }

    @Transactional
    public FinTypes.ImportResult importFile(String fileName, byte[] content) {
        DbSessionContext ctx = DbSessionContext.current();
        if (ctx == null || !ctx.finScope()) {
            throw ApiException.forbidden();
        }
        String checksum = sha256(content);
        record Existing(UUID publicId, String status, int sourceRecordCount, int loadedCount, int quarantinedCount) { }
        Optional<Existing> existing = jdbc.sql("""
                SELECT public_id, status, source_record_count, loaded_count, quarantined_count FROM fin.import_batch WHERE checksum = :c
                """).param("c", checksum).query(Existing.class).optional();
        if (existing.isPresent()) {
            Existing e = existing.get();
            audit.record("FIN", "IMPORT_DUPLICATE", "import_batch", e.publicId(), Map.of("file", String.valueOf(fileName)));
            return new FinTypes.ImportResult(e.publicId(), true, e.status(), e.sourceRecordCount(), e.loadedCount(), e.quarantinedCount(), null);
        }

        List<CsvReader.Row> rows;
        String parseError = null;
        try {
            rows = FinanceCsvFormat.normalize(CsvReader.parse(new String(content, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            rows = List.of();
            parseError = e.getMessage();
        }
        List<CsvReader.Row> claims = byType(rows, "C"), payments = byType(rows, "P"), allocations = byType(rows, "A"),
                adjustments = byType(rows, "J"), trailer = byType(rows, "T");
        int detail = claims.size() + payments.size() + allocations.size() + adjustments.size();
        String feedType = allocations.isEmpty() && payments.isEmpty() ? "CLAIMS" : claims.isEmpty() && adjustments.isEmpty() ? "PAYMENTS" : "MIXED";

        Trailer t = parseTrailer(trailer);
        String rejection = parseError != null ? "file could not be read: " + parseError : checkTrailer(t, detail, claims, payments, allocations);
        record Created(long id, UUID publicId) { }
        Created batch = jdbc.sql("""
                INSERT INTO fin.import_batch (feed_type, file_name, checksum, source_record_count, source_amount_total, imported_by, status)
                VALUES (:ft, :f, :c, :n, :total, :by, 'RECEIVED') RETURNING id, public_id
                """).param("ft", feedType).param("f", fileName).param("c", checksum).param("n", t == null ? detail : t.count())
                .param("total", t == null ? BigDecimal.ZERO : t.claims().add(t.payments()).add(t.allocations()))
                .param("by", ctx.actor()).query(Created.class).single();
        jdbc.sql("INSERT INTO integ.inbound_message (source_system, message_key, outcome) VALUES ('FINANCE', :k, :o) ON CONFLICT DO NOTHING")
                .param("k", checksum).param("o", rejection == null ? "ACCEPTED" : "REJECTED").update();

        if (rejection != null) {
            quarantine(batch.id(), 0, "SCHEMA_ERROR", Map.of("error", rejection));
            jdbc.sql("UPDATE fin.import_batch SET status = 'REJECTED', quarantined_count = 1 WHERE id = :id").param("id", batch.id()).update();
            audit.record("FIN", "IMPORT_REJECTED", "import_batch", batch.publicId(), Map.of("file", String.valueOf(fileName), "reason", rejection));
            return new FinTypes.ImportResult(batch.publicId(), false, "REJECTED", detail, 0, 0, rejection);
        }

        int[] loaded = {0};
        claims.forEach(r -> { if (loadClaim(batch.id(), r)) loaded[0]++; });
        payments.forEach(r -> { if (loadPayment(batch.id(), r)) loaded[0]++; });
        allocations.forEach(r -> { if (loadAllocation(batch.id(), r)) loaded[0]++; });
        adjustments.forEach(r -> { if (loadAdjustment(batch.id(), r)) loaded[0]++; });
        int quarantined = detail - loaded[0];

        jdbc.sql("""
                UPDATE fin.import_batch SET loaded_count = :l, quarantined_count = :q, status = 'RECONCILED' WHERE id = :id
                """).param("l", loaded[0]).param("q", quarantined).param("id", batch.id()).update();

        Set<Long> reviewers = new HashSet<>(roles.staffWithRole(Roles.FIN_REVIEWER));
        if (ctx.staffId() != null) {
            reviewers.remove(ctx.staffId());
        }
        notifier.notify(NotificationRequest.inApp(reviewers, "FinanceBatchAwaitingReview", NtfCategory.APPROVALS,
                Map.of("file", String.valueOf(fileName)), "/finance/batches/" + batch.publicId(), "FIN", batch.publicId()));
        audit.record("FIN", "IMPORT", "import_batch", batch.publicId(),
                Map.of("file", String.valueOf(fileName), "records", detail, "loaded", loaded[0], "quarantined", quarantined));
        return new FinTypes.ImportResult(batch.publicId(), false, "RECONCILED", detail, loaded[0], quarantined, null);
    }

    // ---------------------------------------------------------------- rows

    private boolean loadClaim(long batchId, CsvReader.Row r) {
        String ref = r.get(1), emp = r.get(2), type = r.get(3), desc = r.get(4), currency = r.get(6).toUpperCase(), status = r.get(9).toUpperCase();
        BigDecimal amount = amount(r.get(5));
        LocalDate approved = date(r.get(7));
        if (ref.isEmpty() || desc.isEmpty() || approved == null || !currency.matches("[A-Z]{3}") || !Set.of("APPROVED", "CANCELLED").contains(status)) {
            return quarantine(batchId, r, "SCHEMA_ERROR");
        }
        if (amount == null || amount.signum() <= 0) {
            return quarantine(batchId, r, "INVALID_AMOUNT");
        }
        Optional<Long> staff = staffId(emp);
        if (staff.isEmpty()) {
            return quarantine(batchId, r, "UNKNOWN_EMPLOYEE");
        }
        Optional<Long> typeId = jdbc.sql("SELECT id FROM fin.claim_type WHERE code = :c").param("c", type.toUpperCase()).query(Long.class).optional();
        if (typeId.isEmpty()) {
            return quarantine(batchId, r, "SCHEMA_ERROR");
        }
        record Prior(long id, String status, boolean published) { }
        Optional<Prior> prior = jdbc.sql("SELECT id, status, published_at IS NOT NULL AS published FROM fin.claim WHERE source_ref = :r")
                .param("r", ref).query(Prior.class).optional();
        if (prior.isPresent()) {
            // Only a claim not yet visible to staff may be cancelled by a later file; published claims need an adjustment (J).
            if ("CANCELLED".equals(status) && !prior.get().published() && "APPROVED".equals(prior.get().status())) {
                return apply(batchId, r, "SCHEMA_ERROR", () -> jdbc.sql("UPDATE fin.claim SET status = 'CANCELLED' WHERE id = :id").param("id", prior.get().id()).update());
            }
            return quarantine(batchId, r, "DUPLICATE_REF");
        }
        return apply(batchId, r, "SCHEMA_ERROR", () -> jdbc.sql("""
                INSERT INTO fin.claim (staff_id, batch_id, source_ref, claim_type_id, description, approved_amount, currency, approval_date, voucher_ref, status)
                VALUES (:s, :b, :ref, :t, :d, :amt, :cur, :ad, :v, :st)
                """).param("s", staff.get()).param("b", batchId).param("ref", ref).param("t", typeId.get()).param("d", desc)
                .param("amt", amount).param("cur", currency).param("ad", approved).param("v", blank(r.get(8))).param("st", status).update());
    }

    private boolean loadPayment(long batchId, CsvReader.Row r) {
        String ref = r.get(1), emp = r.get(2), currency = r.get(4).toUpperCase(), status = r.get(7).toUpperCase();
        BigDecimal amount = amount(r.get(3));
        LocalDate paidOn = date(r.get(5));
        if (ref.isEmpty() || paidOn == null || !currency.matches("[A-Z]{3}") || !Set.of("CONFIRMED", "CANCELLED").contains(status)) {
            return quarantine(batchId, r, "SCHEMA_ERROR");
        }
        if (amount == null || amount.signum() <= 0) {
            return quarantine(batchId, r, "INVALID_AMOUNT");
        }
        Optional<Long> staff = staffId(emp);
        if (staff.isEmpty()) {
            return quarantine(batchId, r, "UNKNOWN_EMPLOYEE");
        }
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM fin.payment WHERE source_ref = :r)").param("r", ref).query(Boolean.class).single()) {
            return quarantine(batchId, r, "DUPLICATE_REF");
        }
        return apply(batchId, r, "SCHEMA_ERROR", () -> jdbc.sql("""
                INSERT INTO fin.payment (staff_id, batch_id, source_ref, amount, currency, paid_on, payment_ref, status)
                VALUES (:s, :b, :ref, :amt, :cur, :paid, :pref, :st)
                """).param("s", staff.get()).param("b", batchId).param("ref", ref).param("amt", amount).param("cur", currency)
                .param("paid", paidOn).param("pref", blank(r.get(6))).param("st", status).update());
    }

    private boolean loadAllocation(long batchId, CsvReader.Row r) {
        String ref = r.get(1), paymentRef = r.get(2), claimRef = r.get(3), reversesRef = r.get(5);
        BigDecimal amount = amount(r.get(4));
        if (ref.isEmpty() || paymentRef.isEmpty() || claimRef.isEmpty()) {
            return quarantine(batchId, r, "SCHEMA_ERROR");
        }
        if (amount == null || amount.signum() == 0 || (amount.signum() < 0) == reversesRef.isEmpty()) {
            return quarantine(batchId, r, "INVALID_AMOUNT");
        }
        Optional<Long> payment = jdbc.sql("SELECT id FROM fin.payment WHERE source_ref = :r").param("r", paymentRef).query(Long.class).optional();
        Optional<Long> claim = jdbc.sql("SELECT id FROM fin.claim WHERE source_ref = :r").param("r", claimRef).query(Long.class).optional();
        if (payment.isEmpty() || claim.isEmpty()) {
            return quarantine(batchId, r, "UNKNOWN_CLAIM");
        }
        Long reverses = null;
        if (!reversesRef.isEmpty()) {
            Optional<Long> original = jdbc.sql("SELECT id FROM fin.payment_allocation WHERE source_ref = :r").param("r", reversesRef).query(Long.class).optional();
            if (original.isEmpty()) {
                return quarantine(batchId, r, "UNKNOWN_CLAIM");
            }
            reverses = original.get();
        }
        Long rev = reverses;
        return apply(batchId, r, "SCHEMA_ERROR", () -> jdbc.sql("""
                INSERT INTO fin.payment_allocation (payment_id, claim_id, amount, reverses_allocation_id, source_ref, allocated_by)
                VALUES (:p, :c, :amt, :rev, :ref, 'FINANCE_FEED')
                """).param("p", payment.get()).param("c", claim.get()).param("amt", amount).param("rev", rev).param("ref", ref).update());
    }

    private boolean loadAdjustment(long batchId, CsvReader.Row r) {
        String ref = r.get(1), claimRef = r.get(2), reason = r.get(4);
        BigDecimal amount = amount(r.get(3));
        if (ref.isEmpty() || claimRef.isEmpty() || reason.isEmpty()) {
            return quarantine(batchId, r, "SCHEMA_ERROR");
        }
        if (amount == null || amount.signum() == 0) {
            return quarantine(batchId, r, "INVALID_AMOUNT");
        }
        Optional<Long> claim = jdbc.sql("SELECT id FROM fin.claim WHERE source_ref = :r").param("r", claimRef).query(Long.class).optional();
        if (claim.isEmpty()) {
            return quarantine(batchId, r, "UNKNOWN_CLAIM");
        }
        return apply(batchId, r, "SCHEMA_ERROR", () -> jdbc.sql("""
                INSERT INTO fin.adjustment (claim_id, batch_id, source_ref, amount, reason) VALUES (:c, :b, :ref, :amt, :reason)
                """).param("c", claim.get()).param("b", batchId).param("ref", ref).param("amt", amount).param("reason", reason).update());
    }

    /** Runs the insert in a savepoint; database rule violations become quarantine items with a specific reason. */
    private boolean apply(long batchId, CsvReader.Row r, String defaultReason, Runnable work) {
        Optional<DataAccessException> err = nested.tryRun(work);
        if (err.isEmpty()) {
            return true;
        }
        DataAccessException e = err.get();
        String msg = String.valueOf(root(e).getMessage());
        String reason = e instanceof DuplicateKeyException ? "DUPLICATE_REF"
                : msg.contains("ICCP-FIN-006") || msg.contains("ICCP-FIN-007") || msg.contains("ICCP-FIN-008") ? "OVER_ALLOCATION"
                : msg.contains("ICCP-FIN-004") ? "CURRENCY_MISMATCH"
                : defaultReason;
        Map<String, Object> payload = rowPayload(r);
        payload.put("error", msg.split("\\R")[0].replaceFirst("^ERROR: ", ""));
        quarantine(batchId, r.lineNo(), reason, payload);
        return false;
    }

    private boolean quarantine(long batchId, CsvReader.Row r, String reason) {
        quarantine(batchId, r.lineNo(), reason, rowPayload(r));
        return false;
    }

    private void quarantine(long batchId, int rowNo, String reason, Map<String, ?> payload) {
        jdbc.sql("INSERT INTO fin.quarantine_item (batch_id, row_no, reason_code, payload) VALUES (:b, :n, :r, CAST(:p AS jsonb))")
                .param("b", batchId).param("n", rowNo).param("r", reason).param("p", json(payload)).update();
    }

    // ---------------------------------------------------------------- trailer

    record Trailer(int count, BigDecimal claims, BigDecimal payments, BigDecimal allocations) {
    }

    private static Trailer parseTrailer(List<CsvReader.Row> trailer) {
        if (trailer.size() != 1) {
            return null;
        }
        CsvReader.Row t = trailer.get(0);
        try {
            return new Trailer(Integer.parseInt(t.get(1)), new BigDecimal(t.get(2)), new BigDecimal(t.get(3)), new BigDecimal(t.get(4)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String checkTrailer(Trailer t, int detail, List<CsvReader.Row> claims, List<CsvReader.Row> payments, List<CsvReader.Row> allocations) {
        if (t == null) {
            return "the file must end with exactly one valid trailer (T) record";
        }
        if (t.count() != detail) {
            return "trailer record count %d does not match %d detail rows".formatted(t.count(), detail);
        }
        BigDecimal c = sum(claims, 5), p = sum(payments, 3), a = sum(allocations, 4);
        if (c == null || p == null || a == null) {
            return "one or more amounts are not valid numbers";
        }
        if (c.compareTo(t.claims()) != 0 || p.compareTo(t.payments()) != 0 || a.compareTo(t.allocations()) != 0) {
            return "trailer totals do not match the detail rows (claims %s/%s, payments %s/%s, allocations %s/%s)"
                    .formatted(t.claims().toPlainString(), c.toPlainString(), t.payments().toPlainString(), p.toPlainString(),
                            t.allocations().toPlainString(), a.toPlainString());
        }
        return null;
    }

    private static BigDecimal sum(List<CsvReader.Row> rows, int col) {
        BigDecimal total = BigDecimal.ZERO;
        for (CsvReader.Row r : rows) {
            BigDecimal v = amount(r.get(col));
            if (v == null) {
                return null;
            }
            total = total.add(v);
        }
        return total;
    }

    // ---------------------------------------------------------------- helpers

    private Optional<Long> staffId(String employeeNo) {
        return jdbc.sql("SELECT id FROM dir.staff_member WHERE employee_no = :n").param("n", employeeNo).query(Long.class).optional();
    }

    private static List<CsvReader.Row> byType(List<CsvReader.Row> rows, String type) {
        return rows.stream().filter(r -> type.equalsIgnoreCase(r.get(0))).toList();
    }

    static BigDecimal amount(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(s.trim().replace(",", ""));
            return v.scale() > 2 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Map<String, Object> rowPayload(CsvReader.Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", r.lineNo());
        m.put("cells", r.cells());
        return m;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static Throwable root(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
