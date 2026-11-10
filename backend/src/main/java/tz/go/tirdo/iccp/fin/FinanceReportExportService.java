package tz.go.tirdo.iccp.fin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.web.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class FinanceReportExportService {

    private final JdbcClient jdbc;
    private final FinanceBatchService batches;
    private final AuditService audit;

    public FinanceReportExportService(JdbcClient jdbc, FinanceBatchService batches, AuditService audit) {
        this.jdbc = jdbc;
        this.batches = batches;
        this.audit = audit;
    }

    public record Exported(String signature, byte[] content) {
    }

    public record Verification(String signature, boolean valid, UUID batchPublicId, String fileName, String batchStatus,
                               String reportType, String exportedBy, OffsetDateTime exportedAt) {
    }

    private record VerificationRow(String signature, UUID batchPublicId, String fileName, String batchStatus,
                                   String reportType, String exportedBy, OffsetDateTime exportedAt) {
    }

    @Transactional
    public Exported reconciliation(UUID batchPublicId) {
        FinTypes.Batch batch = batches.batch(batchPublicId);
        String signature = "ICCP-FIN-REC-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        FinanceBatchCsvReport.Generated report = FinanceBatchCsvReport.generate(batch, batches.quarantine(batchPublicId, false), signature);
        byte[] content = ("\uFEFF" + report.content()).getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        jdbc.sql("""
                INSERT INTO fin.report_export(batch_id, report_type, signature, content_sha256, exported_by)
                SELECT id, 'RECONCILIATION_CSV', :signature, :hash, :actor FROM fin.import_batch WHERE public_id = :batch
                """).param("signature", signature).param("hash", hash).param("actor", CurrentUser.get().subject())
                .param("batch", batchPublicId).update();
        audit.record("FIN", "RECONCILIATION_REPORT_EXPORT", "import_batch", batchPublicId,
                Map.of("signature", signature, "sha256", hash));
        return new Exported(signature, content);
    }

    @Transactional(readOnly = true)
    public Verification verify(String signature) {
        FinanceBatchService.requireFinance();
        String normalized = signature == null ? "" : signature.trim().toUpperCase();
        if (!normalized.matches("ICCP-FIN-REC-[0-9A-F]{32}")) {
            throw ApiException.badRequest("ICCP-FIN-031", "Enter a valid ICCP Finance report signature.");
        }
        return jdbc.sql("""
                SELECT e.signature, b.public_id AS batch_public_id, b.file_name, b.status AS batch_status,
                       e.report_type, e.exported_by, e.exported_at
                  FROM fin.report_export e JOIN fin.import_batch b ON b.id = e.batch_id
                 WHERE e.signature = :signature
                """).param("signature", normalized).query(VerificationRow.class).optional()
                .map(r -> new Verification(r.signature(), true, r.batchPublicId(), r.fileName(), r.batchStatus(),
                        r.reportType(), r.exportedBy(), r.exportedAt()))
                .orElse(new Verification(normalized, false, null, null, null, null, null, null));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
