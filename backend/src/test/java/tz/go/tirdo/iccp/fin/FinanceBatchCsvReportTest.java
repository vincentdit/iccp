package tz.go.tirdo.iccp.fin;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceBatchCsvReportTest {

    @Test
    void includesBatchControlsQuarantineResolutionAndStableSignature() {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
        OffsetDateTime at = OffsetDateTime.parse("2026-11-10T08:00:00+03:00");
        FinTypes.Batch batch = new FinTypes.Batch(id, "FINANCE_DIARY", "staff,diary.csv", "RECONCILED", 3,
                new BigDecimal("1200000.00"), 2, 1, 0, "finance-maker", "finance-checker", at, null, at, false);
        FinTypes.QuarantineItem item = new FinTypes.QuarantineItem(7, 4, "UNKNOWN_STAFF", "{\"employeeNo\":\"TR9999\"}",
                "RESOLVED", "Corrected at source, pending next feed", "finance-maker", at);

        FinanceBatchCsvReport.Generated first = FinanceBatchCsvReport.generate(batch, List.of(item));
        FinanceBatchCsvReport.Generated second = FinanceBatchCsvReport.generate(batch, List.of(item));

        assertThat(first.signature()).matches("ICCP-FIN-REC-[0-9A-F]{16}").isEqualTo(second.signature());
        assertThat(first.content()).contains("\"BATCH\"", "\"QUARANTINE\"", "\"UNKNOWN_STAFF\"",
                "\"Corrected at source, pending next feed\"", "\"staff,diary.csv\"");
        assertThat(first.content().lines()).hasSize(3);
    }
}
