package tz.go.tirdo.iccp.platform.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 CSV reader for the HR and Finance batch files (Doc 03 §6.1–6.2):
 * comma separator, double-quote quoting with "" escapes, CRLF or LF line endings, optional UTF-8
 * BOM. Blank lines and lines starting with {@code #} are skipped. Each returned row keeps its
 * 1-based line number so quarantine records can point back to the source file.
 */
public final class CsvReader {

    public record Row(int lineNo, List<String> cells) {
        public String get(int i) {
            if (i >= cells.size()) {
                return "";
            }
            String v = cells.get(i);
            return v == null ? "" : v.trim();
        }

        public int size() {
            return cells.size();
        }
    }

    private CsvReader() {
    }

    public static List<Row> parse(String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean cellStarted = false;
        int line = 1;
        int rowStartLine = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    if (cell.isEmpty() && !cellStarted) {
                        quoted = true;
                        cellStarted = true;
                    } else {
                        cell.append(c);
                    }
                }
                case ',' -> {
                    cells.add(cell.toString());
                    cell.setLength(0);
                    cellStarted = false;
                }
                case '\r' -> { /* ignored; \n ends the row */ }
                case '\n' -> {
                    cells.add(cell.toString());
                    addRow(rows, rowStartLine, cells);
                    cells = new ArrayList<>();
                    cell.setLength(0);
                    cellStarted = false;
                    line++;
                    rowStartLine = line;
                }
                default -> {
                    cell.append(c);
                    cellStarted = true;
                }
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unterminated quoted value starting on line " + rowStartLine);
        }
        if (cellStarted || !cell.isEmpty() || !cells.isEmpty()) {
            cells.add(cell.toString());
            addRow(rows, rowStartLine, cells);
        }
        return rows;
    }

    private static void addRow(List<Row> rows, int lineNo, List<String> cells) {
        boolean blank = cells.stream().allMatch(s -> s.isBlank());
        if (blank || cells.get(0).startsWith("#")) {
            return;
        }
        rows.add(new Row(lineNo, List.copyOf(cells)));
    }
}
