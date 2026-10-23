package tz.go.tirdo.iccp.platform;

import org.junit.jupiter.api.Test;
import tz.go.tirdo.iccp.platform.text.HtmlSanitizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlSanitizerTest {

    @Test
    void keepsAllowedFormatting() {
        assertEquals("<p>Hello <strong>staff</strong></p>", HtmlSanitizer.sanitize("<p>Hello <strong>staff</strong></p>"));
    }

    @Test
    void dropsScriptsHandlersAndUnknownTags() {
        String out = HtmlSanitizer.sanitize("<p onclick=\"x()\">Hi<script>alert(1)</script><img src=x onerror=y><div>there</div></p>");
        assertEquals("<p>Hithere</p>", out);
    }

    @Test
    void allowsOnlySafeLinks() {
        assertTrue(HtmlSanitizer.sanitize("<a href=\"https://tirdo.example/x\">x</a>").contains("href=\"https://tirdo.example/x\""));
        String js = HtmlSanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>");
        assertFalse(js.contains("javascript"));
        assertTrue(HtmlSanitizer.sanitize("<a href=https://a.example>x</a>").contains("rel=\"noopener noreferrer\""));
    }

    @Test
    void escapesStrayAngleBracketsAndProducesPlainText() {
        assertFalse(HtmlSanitizer.sanitize("1 < 2 & 3 > 2").contains("< 2"));
        assertEquals("Hello staff", HtmlSanitizer.toPlainText("<p>Hello</p><p><b>staff</b></p>"));
    }
}
