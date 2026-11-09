package tz.go.tirdo.iccp.fin;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A labeled, fillable Finance diary CSV matching {@link FinanceImportService}'s record layout. */
public final class FinanceCsvTemplate {
    private FinanceCsvTemplate() {}

    public record Generated(String signature, String content) {}

    public static Generated generate() {
        return generate(UUID.randomUUID(), OffsetDateTime.now());
    }

    static Generated generate(UUID id, OffsetDateTime generatedAt) {
        String signature = id.toString().toUpperCase();
        String fileId = "ICCP-DIARY-" + signature;
        String header = String.join(",", FinanceCsvFormat.COLUMNS);
        String content = header + "\n"
                + row("record_type", "H", "template_signature", signature, "file_id", fileId,
                        "extracted_at", generatedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "source_system", "FINANCE",
                        "period", YearMonth.from(generatedAt).toString()) + "\n"
                + row("record_type", "C", "template_signature", signature, "source_ref", "CLM-2026-0412",
                        "employee_no", "TR1007", "claim_type_code", "TRAVEL", "description", "Travel allowance - Mwanza field sampling",
                        "approved_amount", "1200000.00", "currency", "TZS", "transaction_date", "2026-07-14",
                        "external_reference", "PV-26-1180", "status", "APPROVED") + "\n"
                + row("record_type", "P", "template_signature", signature, "source_ref", "PAY-2026-0901",
                        "employee_no", "TR1007", "amount", "1200000.00", "currency", "TZS",
                        "transaction_date", "2026-07-28", "external_reference", "NMB-77120931", "status", "CONFIRMED") + "\n"
                + row("record_type", "A", "template_signature", signature, "source_ref", "ALC-2026-1501",
                        "payment_source_ref", "PAY-2026-0901", "claim_source_ref", "CLM-2026-0412", "amount", "1200000.00") + "\n"
                + row("record_type", "T", "template_signature", signature, "detail_row_count", "3",
                        "claims_total", "1200000.00", "payments_total", "1200000.00", "allocations_total", "1200000.00") + "\n";
        return new Generated(signature, content);
    }

    private static String row(String... pairs) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return FinanceCsvFormat.COLUMNS.stream().map(column -> csv(values.getOrDefault(column, ""))).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String csv(String value) {
        return value.contains(",") || value.contains("\"") || value.contains("\n") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
