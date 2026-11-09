package tz.go.tirdo.iccp.fin;

import org.junit.jupiter.api.Test;
import tz.go.tirdo.iccp.platform.csv.CsvReader;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceCsvTemplateTest {
    @Test
    void containsLabeledRealisticRowsAndMatchingTrailerCount() {
        UUID id = UUID.fromString("12345678-1234-5678-9abc-123456789abc");
        String csv = FinanceCsvTemplate.generate(id, OffsetDateTime.parse("2026-11-09T21:45:00+03:00")).content();
        assertThat(csv.lines().findFirst()).hasValue(String.join(",", FinanceCsvFormat.COLUMNS));
        assertThat(csv).contains("12345678-1234-5678-9ABC-123456789ABC", "CLM-2026-0412", "TR1007");
        var normalized = FinanceCsvFormat.normalize(CsvReader.parse(csv));
        assertThat(normalized.stream().map(row -> row.get(0))).containsExactly("H", "C", "P", "A", "T");
        assertThat(normalized.getFirst().cells()).containsExactly("H", "ICCP-DIARY-12345678-1234-5678-9ABC-123456789ABC",
                "2026-11-09T21:45:00+03:00", "FINANCE", "2026-11");
        assertThat(normalized.getLast().cells()).containsExactly("T", "3", "1200000.00", "1200000.00", "1200000.00");
    }

    @Test
    void everyDownloadGetsADifferentSignature() {
        assertThat(FinanceCsvTemplate.generate().signature()).isNotEqualTo(FinanceCsvTemplate.generate().signature());
    }

    @Test
    void keepsLegacyPositionalFilesCompatible() {
        var legacy = CsvReader.parse("H,FIN-001,2026-11-09T21:45:00+03:00,FINANCE,2026-11\nT,0,0,0,0\n");

        assertThat(FinanceCsvFormat.normalize(legacy)).isSameAs(legacy);
    }
}
