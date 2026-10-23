package tz.go.tirdo.iccp.platform;

import org.junit.jupiter.api.Test;
import tz.go.tirdo.iccp.platform.csv.CsvReader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvReaderTest {

    @Test
    void parsesQuotedFieldsEmbeddedCommasAndQuotes() {
        List<CsvReader.Row> rows = CsvReader.parse("S,TR1,\"Mushi, Neema\",\"He said \"\"hi\"\"\"\nT,1\n");
        assertEquals(2, rows.size());
        assertEquals("Mushi, Neema", rows.get(0).get(2));
        assertEquals("He said \"hi\"", rows.get(0).get(3));
        assertEquals(2, rows.get(1).lineNo());
    }

    @Test
    void handlesCrLfMultilineAndMissingCells() {
        List<CsvReader.Row> rows = CsvReader.parse("C,\"line one\r\nline two\",x\r\nT,1\r\n");
        assertEquals(2, rows.size());
        assertEquals("line one\r\nline two", rows.get(0).get(1));
        assertEquals("", rows.get(0).get(9), "cells beyond the row are empty, not an exception");
    }

    @Test
    void trimsCellsAndSkipsBlankLines() {
        List<CsvReader.Row> rows = CsvReader.parse("\n  U , DG \n\n");
        assertEquals(1, rows.size());
        assertEquals("U", rows.get(0).get(0));
        assertEquals("DG", rows.get(0).get(1));
    }
}
