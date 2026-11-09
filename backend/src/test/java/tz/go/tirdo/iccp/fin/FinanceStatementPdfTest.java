package tz.go.tirdo.iccp.fin;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceStatementPdfTest {
    @Test
    void producesReadableConfidentialStatementWithReconciledLedger() throws Exception {
        OffsetDateTime generated = OffsetDateTime.parse("2026-11-09T22:45:00+03:00");
        FinTypes.Summary summary = new FinTypes.Summary("TZS", new BigDecimal("3500000.00"),
                new BigDecimal("2000000.00"), new BigDecimal("1500000.00"), new BigDecimal("57.1"), 2, 0, generated.minusDays(1));
        List<FinTypes.ClaimLine> claims = List.of(
                claim("CLM-2026-0412", "Travel allowance - Mwanza field sampling", "1200000.00", "1200000.00", "0.00", "FULLY_PAID"),
                claim("CLM-2026-0520", "Consultancy allowance", "2300000.00", "800000.00", "1500000.00", "PARTLY_PAID"));
        FinTypes.Statement statement = new FinTypes.Statement("TR1007", "Neema Mushi", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31), generated, generated.minusDays(1), List.of(summary), claims);

        byte[] pdf = new FinanceStatementPdf().generate(statement);
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "finance-statement-preview.pdf"), pdf);

        assertThat(pdf).startsWith("%PDF".getBytes());
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getPage(0).getResources().getXObjectNames()).isNotEmpty();
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("ICCP PERSONAL FINANCIAL STATEMENT", "Neema Mushi (TR1007)",
                    "ICCP statement - not a payment voucher", "CLM-2026-0412", "3,500,000.00", "Confidential");
        }
    }

    private static FinTypes.ClaimLine claim(String ref, String description, String approved, String paid, String outstanding, String status) {
        return new FinTypes.ClaimLine(UUID.randomUUID(), ref, "TRAVEL", "Travel", "Safari", description, "TZS",
                LocalDate.of(2026, 7, 14), new BigDecimal(approved), new BigDecimal(paid), BigDecimal.ZERO,
                new BigDecimal(outstanding), status);
    }
}
