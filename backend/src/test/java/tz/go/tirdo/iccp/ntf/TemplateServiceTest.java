package tz.go.tirdo.iccp.ntf;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TemplateServiceTest {

    @Test
    void fillsPlaceholdersAndBlanksUnknownOnes() {
        assertEquals("Finance batch a.csv is ready ", TemplateService.fill("Finance batch {{file}} is ready {{ missing }}", Map.of("file", "a.csv")));
    }

    @Test
    void doesNotInterpretReplacementCharacters() {
        assertEquals("Ref $1\\x", TemplateService.fill("Ref {{ref}}", Map.of("ref", "$1\\x")));
    }

    @Test
    void nullTextStaysNull() {
        assertNull(TemplateService.fill(null, Map.of()));
    }
}
