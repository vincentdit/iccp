package tz.go.tirdo.iccp.fin;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FinanceImportRulesTest {

    @Test
    void parsesMoneyStrictly() {
        assertEquals(new BigDecimal("1500000.00"), FinanceImportService.amount("1,500,000.00"));
        assertEquals(new BigDecimal("-250.5"), FinanceImportService.amount(" -250.5 "));
        assertNull(FinanceImportService.amount("12.345"), "more than two decimals is rejected");
        assertNull(FinanceImportService.amount("TZS 10"));
        assertNull(FinanceImportService.amount(""));
    }

    @Test
    void checksumIsStableSha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FinanceImportService.sha256(new byte[0]));
    }
}
