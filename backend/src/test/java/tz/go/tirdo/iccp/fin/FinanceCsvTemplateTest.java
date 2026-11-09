package tz.go.tirdo.iccp.fin;

import org.junit.jupiter.api.Test;
import tz.go.tirdo.iccp.platform.csv.CsvReader;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceCsvTemplateTest {
    @Test
    void containsLabeledRealisticRowsAndMatchingTrailerCount() {
        String csv = FinanceCsvTemplate.content();
        assertThat(csv).contains("#C,claim_source_ref,employee_no", "CLM-2026-0412", "TR1007");
        assertThat(CsvReader.parse(csv).stream().map(row -> row.get(0))).containsExactly("H", "C", "P", "A", "T");
        assertThat(csv).contains("T,3,1200000.00,1200000.00,1200000.00");
    }
}
