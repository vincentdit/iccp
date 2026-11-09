package tz.go.tirdo.iccp.fin;

import tz.go.tirdo.iccp.platform.csv.CsvReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts the user-friendly, flat spreadsheet template into the positional Finance feed rows. */
final class FinanceCsvFormat {
    static final List<String> COLUMNS = List.of(
            "record_type", "template_signature", "file_id", "extracted_at", "source_system", "period",
            "source_ref", "employee_no", "claim_type_code", "description", "approved_amount", "currency",
            "transaction_date", "external_reference", "status", "payment_source_ref", "claim_source_ref",
            "amount", "reverses_source_ref", "reason", "detail_row_count", "claims_total", "payments_total",
            "allocations_total");

    private FinanceCsvFormat() {}

    static List<CsvReader.Row> normalize(List<CsvReader.Row> rows) {
        if (rows.isEmpty() || !"record_type".equalsIgnoreCase(rows.getFirst().get(0))) return rows;
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < rows.getFirst().size(); i++) columns.put(rows.getFirst().get(i).toLowerCase(Locale.ROOT), i);
        List<CsvReader.Row> result = new ArrayList<>();
        for (CsvReader.Row row : rows.subList(1, rows.size())) {
            String type = value(row, columns, "record_type").toUpperCase(Locale.ROOT);
            List<String> cells = switch (type) {
                case "H" -> List.of("H", value(row, columns, "file_id"), value(row, columns, "extracted_at"),
                        value(row, columns, "source_system"), value(row, columns, "period"));
                case "C" -> List.of("C", value(row, columns, "source_ref"), value(row, columns, "employee_no"),
                        value(row, columns, "claim_type_code"), value(row, columns, "description"),
                        value(row, columns, "approved_amount"), value(row, columns, "currency"),
                        value(row, columns, "transaction_date"), value(row, columns, "external_reference"),
                        value(row, columns, "status"));
                case "P" -> List.of("P", value(row, columns, "source_ref"), value(row, columns, "employee_no"),
                        value(row, columns, "amount"), value(row, columns, "currency"),
                        value(row, columns, "transaction_date"), value(row, columns, "external_reference"),
                        value(row, columns, "status"));
                case "A" -> List.of("A", value(row, columns, "source_ref"), value(row, columns, "payment_source_ref"),
                        value(row, columns, "claim_source_ref"), value(row, columns, "amount"),
                        value(row, columns, "reverses_source_ref"));
                case "J" -> List.of("J", value(row, columns, "source_ref"), value(row, columns, "claim_source_ref"),
                        value(row, columns, "amount"), value(row, columns, "reason"));
                case "T" -> List.of("T", value(row, columns, "detail_row_count"), value(row, columns, "claims_total"),
                        value(row, columns, "payments_total"), value(row, columns, "allocations_total"));
                default -> null;
            };
            if (cells != null) result.add(new CsvReader.Row(row.lineNo(), cells));
        }
        return result;
    }

    private static String value(CsvReader.Row row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null ? "" : row.get(index);
    }
}
