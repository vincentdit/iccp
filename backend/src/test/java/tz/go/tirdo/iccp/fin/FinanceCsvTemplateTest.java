package tz.go.tirdo.iccp.fin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceCsvTemplateTest {
    @Test
    void containsEverySupportedRecordAndMatchingTrailerCount() {
        String csv = FinanceCsvTemplate.content();
        assertThat(csv.lines().map(line -> line.substring(0, 1))).containsExactly("H", "C", "P", "A", "J", "T");
        assertThat(csv).contains("T,4,0.00,0.00,0.00");
    }
}
