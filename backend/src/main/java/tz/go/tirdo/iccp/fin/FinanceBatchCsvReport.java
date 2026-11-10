package tz.go.tirdo.iccp.fin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Creates a flat, Excel-friendly reconciliation record for one Finance import batch. */
public final class FinanceBatchCsvReport {

    private FinanceBatchCsvReport() {
    }

    public record Generated(String signature, String content) {
    }

    public static Generated generate(FinTypes.Batch batch, List<FinTypes.QuarantineItem> quarantine) {
        String signature = signature(batch);
        StringBuilder csv = new StringBuilder();
        row(csv, "record_type", "report_signature", "batch_id", "file_name", "batch_status", "feed_type",
                "source_record_count", "source_amount_total", "loaded_count", "quarantined_count", "open_quarantine",
                "imported_by", "created_at", "reviewed_by", "reviewed_at", "published_at", "row_no", "reason_code",
                "row_status", "resolution_note", "resolved_by", "resolved_at", "row_payload");
        row(csv, "BATCH", signature, batch.publicId(), batch.fileName(), batch.status(), batch.feedType(),
                batch.sourceRecordCount(), batch.sourceAmountTotal(), batch.loadedCount(), batch.quarantinedCount(),
                batch.openQuarantine(), batch.importedBy(), batch.createdAt(), batch.reviewedBy(), batch.reviewedAt(),
                batch.publishedAt(), "", "", "", "", "", "", "");
        for (FinTypes.QuarantineItem item : quarantine) {
            row(csv, "QUARANTINE", signature, batch.publicId(), batch.fileName(), batch.status(), batch.feedType(),
                    batch.sourceRecordCount(), batch.sourceAmountTotal(), batch.loadedCount(), batch.quarantinedCount(),
                    batch.openQuarantine(), batch.importedBy(), batch.createdAt(), batch.reviewedBy(), batch.reviewedAt(),
                    batch.publishedAt(), item.rowNo(), item.reasonCode(), item.status(), item.resolutionNote(),
                    item.resolvedBy(), item.resolvedAt(), item.payload());
        }
        return new Generated(signature, csv.toString());
    }

    private static String signature(FinTypes.Batch batch) {
        try {
            String seed = batch.publicId() + "|" + batch.createdAt() + "|" + batch.status() + "|" + batch.loadedCount()
                    + "|" + batch.quarantinedCount();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return "ICCP-FIN-REC-" + java.util.HexFormat.of().formatHex(digest, 0, 8).toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void row(StringBuilder csv, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) csv.append(',');
            String value = cells[i] == null ? "" : cells[i].toString();
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
