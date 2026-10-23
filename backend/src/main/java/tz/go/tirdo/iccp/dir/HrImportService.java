package tz.go.tirdo.iccp.dir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.dir.api.DirEvents;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.csv.CsvReader;
import tz.go.tirdo.iccp.platform.db.NestedTx;
import tz.go.tirdo.iccp.platform.outbox.OutboxWriter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Applies a daily HR export to the directory (BP-DIR-01, FR-DIR-01/02).
 *
 * <p>File format (Doc 03 §6.2), one CSV with a record type in the first column:
 * <pre>
 * U,unit_code,name_en,name_sw,unit_type,parent_unit_code,effective_from,effective_to
 * O,position_code,title_en,title_sw,unit_code,is_head
 * S,employee_no,first_name,middle_name,last_name,title,position_code,unit_code,line_manager_employee_no,
 *   employment_status,staff_category,duty_station,official_email,office_phone,mobile_phone
 * T,unit_count,position_count,staff_count
 * </pre>
 * The same file twice is a no-op (checksum). A trailer mismatch rejects the whole file. A row that
 * fails validation goes to the exception queue and never partially overwrites a valid record.
 */
@Service
public class HrImportService {

    private static final Set<String> UNIT_TYPES = Set.of("DIRECTORATE", "DEPARTMENT", "SECTION", "UNIT", "LABORATORY", "OFFICE");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "ON_LEAVE", "SUSPENDED", "LEFT");

    private final JdbcClient jdbc;
    private final NestedTx nested;
    private final OutboxWriter outbox;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public HrImportService(JdbcClient jdbc, NestedTx nested, OutboxWriter outbox, AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.nested = nested;
        this.outbox = outbox;
        this.audit = audit;
        this.mapper = mapper;
    }

    public record ImportResult(long batchId, boolean duplicate, String status, int recordsTotal, int created, int updated,
                               int deactivated, int exceptions) {
    }

    @Transactional
    public ImportResult importFile(String fileName, byte[] content) {
        String checksum = sha256(content);
        Optional<ImportResult> existing = jdbc.sql("""
                SELECT id AS batch_id, true AS duplicate, status, records_total, created_count AS created, updated_count AS updated,
                       deactivated_count AS deactivated, exception_count AS exceptions
                  FROM dir.hr_sync_batch WHERE checksum = :c
                """).param("c", checksum).query(ImportResult.class).optional();
        if (existing.isPresent()) {
            audit.record("DIR", "HR_IMPORT_DUPLICATE", "hr_sync_batch", existing.get().batchId(), Map.of("file", fileName));
            return existing.get();
        }

        List<CsvReader.Row> rows;
        try {
            rows = CsvReader.parse(new String(content, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            rows = List.of();
        }
        List<CsvReader.Row> units = byType(rows, "U");
        List<CsvReader.Row> positions = byType(rows, "O");
        List<CsvReader.Row> staff = byType(rows, "S");
        List<CsvReader.Row> trailer = byType(rows, "T");
        int total = units.size() + positions.size() + staff.size();

        long batchId = jdbc.sql("""
                INSERT INTO dir.hr_sync_batch (file_name, checksum, status, records_total)
                VALUES (:f, :c, 'RECEIVED', :n) RETURNING id
                """).param("f", fileName).param("c", checksum).param("n", total).query(Long.class).single();
        jdbc.sql("INSERT INTO integ.inbound_message (source_system, message_key, outcome) VALUES ('HR', :k, 'ACCEPTED')")
                .param("k", checksum).update();

        String trailerError = checkTrailer(trailer, units.size(), positions.size(), staff.size());
        if (trailerError != null) {
            exception(batchId, null, "OTHER", Map.of("error", trailerError));
            jdbc.sql("UPDATE dir.hr_sync_batch SET status = 'REJECTED', exception_count = 1 WHERE id = :id").param("id", batchId).update();
            audit.record("DIR", "HR_IMPORT_REJECTED", "hr_sync_batch", batchId, Map.of("file", fileName, "reason", trailerError));
            return new ImportResult(batchId, false, "REJECTED", total, 0, 0, 0, 1);
        }

        Counters c = new Counters();
        applyUnits(batchId, units, c);
        applyPositions(batchId, positions, c);
        Map<String, CsvReader.Row> applied = applyStaff(batchId, staff, c);
        applyLineManagers(batchId, applied, c);
        c.exceptions = exceptionCount(batchId);

        String status = c.exceptions == 0 ? "APPLIED" : "PARTIAL";
        jdbc.sql("""
                UPDATE dir.hr_sync_batch SET status = :s, created_count = :cr, updated_count = :up,
                       deactivated_count = :de, exception_count = :ex WHERE id = :id
                """).param("s", status).param("cr", c.created).param("up", c.updated).param("de", c.deactivated)
                .param("ex", c.exceptions).param("id", batchId).update();
        outbox.write("hr_sync_batch", UUID.nameUUIDFromBytes(("hr-batch-" + batchId).getBytes(StandardCharsets.UTF_8)), DirEvents.HR_SYNC_COMPLETED,
                new DirEvents.HrSyncCompleted(batchId, fileName, c.created, c.updated, c.deactivated, c.exceptions));
        audit.record("DIR", "HR_IMPORT", "hr_sync_batch", batchId,
                Map.of("file", fileName, "created", c.created, "updated", c.updated, "deactivated", c.deactivated, "exceptions", c.exceptions));
        return new ImportResult(batchId, false, status, total, c.created, c.updated, c.deactivated, c.exceptions);
    }

    // ------------------------------------------------------------------ units

    private void applyUnits(long batchId, List<CsvReader.Row> units, Counters c) {
        List<CsvReader.Row> valid = new ArrayList<>();
        for (CsvReader.Row r : units) {
            String code = r.get(1), name = r.get(2), type = r.get(4).toUpperCase();
            LocalDate from = date(r.get(6));
            if (code.isEmpty() || name.isEmpty() || from == null) {
                exception(batchId, null, "MISSING_FIELD", rowMap("U", r));
                continue;
            }
            if (!UNIT_TYPES.contains(type)) {
                exception(batchId, null, "OTHER", withError(rowMap("U", r), "unknown unit_type"));
                continue;
            }
            LocalDate to = date(r.get(7));
            Optional<DataAccessException> err = nested.tryRun(() -> jdbc.sql("""
                    INSERT INTO dir.org_unit (code, name_en, name_sw, unit_type, effective_from, effective_to, hr_ref)
                    VALUES (:code, :name, :sw, :type, :from, :to, :code)
                    ON CONFLICT (code) DO UPDATE SET name_en = EXCLUDED.name_en, name_sw = EXCLUDED.name_sw,
                           unit_type = EXCLUDED.unit_type, effective_from = EXCLUDED.effective_from, effective_to = EXCLUDED.effective_to
                    """).param("code", code).param("name", name).param("sw", blankToNull(r.get(3))).param("type", type)
                    .param("from", from).param("to", to).update());
            if (err.isPresent()) {
                exception(batchId, null, "OTHER", withError(rowMap("U", r), rootMessage(err.get())));
            } else {
                valid.add(r);
            }
        }
        for (CsvReader.Row r : valid) {
            String parent = r.get(5);
            if (parent.isEmpty()) {
                jdbc.sql("UPDATE dir.org_unit SET parent_id = NULL WHERE code = :c AND parent_id IS NOT NULL").param("c", r.get(1)).update();
                continue;
            }
            Optional<Long> parentId = unitId(parent);
            if (parentId.isEmpty()) {
                exception(batchId, null, "UNKNOWN_UNIT", withError(rowMap("U", r), "parent unit " + parent));
                continue;
            }
            Optional<DataAccessException> err = nested.tryRun(() -> jdbc.sql("UPDATE dir.org_unit SET parent_id = :p WHERE code = :c AND parent_id IS DISTINCT FROM :p")
                    .param("p", parentId.get()).param("c", r.get(1)).update());
            err.ifPresent(e -> exception(batchId, null, "OTHER", withError(rowMap("U", r), rootMessage(e))));
        }
    }

    // ------------------------------------------------------------------ positions

    private void applyPositions(long batchId, List<CsvReader.Row> positions, Counters c) {
        for (CsvReader.Row r : positions) {
            String code = r.get(1), title = r.get(2), unitCode = r.get(4);
            if (code.isEmpty() || title.isEmpty() || unitCode.isEmpty()) {
                exception(batchId, null, "MISSING_FIELD", rowMap("O", r));
                continue;
            }
            Optional<Long> unit = unitId(unitCode);
            if (unit.isEmpty()) {
                exception(batchId, null, "UNKNOWN_UNIT", rowMap("O", r));
                continue;
            }
            boolean head = bool(r.get(5));
            Optional<DataAccessException> err = nested.tryRun(() -> jdbc.sql("""
                    INSERT INTO dir.position (code, title_en, title_sw, org_unit_id, is_head, hr_ref)
                    VALUES (:code, :t, :sw, :u, :h, :code)
                    ON CONFLICT (code) DO UPDATE SET title_en = EXCLUDED.title_en, title_sw = EXCLUDED.title_sw,
                           org_unit_id = EXCLUDED.org_unit_id, is_head = EXCLUDED.is_head
                    """).param("code", code).param("t", title).param("sw", blankToNull(r.get(3))).param("u", unit.get()).param("h", head).update());
            err.ifPresent(e -> exception(batchId, null, "OTHER", withError(rowMap("O", r), rootMessage(e))));
        }
    }

    // ------------------------------------------------------------------ staff

    private Map<String, CsvReader.Row> applyStaff(long batchId, List<CsvReader.Row> staff, Counters c) {
        Map<String, CsvReader.Row> applied = new LinkedHashMap<>();
        for (CsvReader.Row r : staff) {
            String empNo = r.get(1), first = r.get(2), last = r.get(4), unitCode = r.get(7), status = r.get(9).toUpperCase(), email = r.get(12);
            if (empNo.isEmpty() || first.isEmpty() || last.isEmpty() || unitCode.isEmpty() || email.isEmpty()) {
                exception(batchId, blankToNull(empNo), "MISSING_FIELD", rowMap("S", r));
                continue;
            }
            if (!STATUSES.contains(status)) {
                exception(batchId, empNo, "INVALID_STATUS", rowMap("S", r));
                continue;
            }
            Optional<Long> unit = unitId(unitCode);
            if (unit.isEmpty()) {
                exception(batchId, empNo, "UNKNOWN_UNIT", rowMap("S", r));
                continue;
            }
            Long positionId = null;
            if (!r.get(6).isEmpty()) {
                Optional<Long> pos = jdbc.sql("SELECT id FROM dir.position WHERE code = :c").param("c", r.get(6)).query(Long.class).optional();
                if (pos.isEmpty()) {
                    exception(batchId, empNo, "OTHER", withError(rowMap("S", r), "unknown position " + r.get(6)));
                    continue;
                }
                positionId = pos.get();
            }
            boolean emailTaken = jdbc.sql("SELECT EXISTS (SELECT 1 FROM dir.staff_member WHERE lower(official_email) = lower(:e) AND employee_no <> :n)")
                    .param("e", email).param("n", empNo).query(Boolean.class).single();
            if (emailTaken) {
                exception(batchId, empNo, "DUPLICATE_EMAIL", rowMap("S", r));
                continue;
            }
            Optional<Existing> before = jdbc.sql("SELECT id, public_id, employment_status FROM dir.staff_member WHERE employee_no = :n")
                    .param("n", empNo).query(Existing.class).optional();
            Long pid = positionId;
            Optional<DataAccessException> err = nested.tryRun(() -> jdbc.sql("""
                    INSERT INTO dir.staff_member (employee_no, first_name, middle_name, last_name, title, position_id, org_unit_id,
                                                  employment_status, staff_category, duty_station, official_email, office_phone,
                                                  mobile_phone, hr_synced_at)
                    VALUES (:no, :first, :middle, :last, :title, :pos, :unit, :status, :cat, :station, :email, :office, :mobile, now())
                    ON CONFLICT (employee_no) DO UPDATE SET
                           first_name = EXCLUDED.first_name, middle_name = EXCLUDED.middle_name, last_name = EXCLUDED.last_name,
                           title = EXCLUDED.title, position_id = EXCLUDED.position_id, org_unit_id = EXCLUDED.org_unit_id,
                           employment_status = EXCLUDED.employment_status, staff_category = EXCLUDED.staff_category,
                           duty_station = EXCLUDED.duty_station, official_email = EXCLUDED.official_email,
                           office_phone = EXCLUDED.office_phone, mobile_phone = EXCLUDED.mobile_phone, hr_synced_at = now()
                    """)
                    .param("no", empNo).param("first", first).param("middle", blankToNull(r.get(3))).param("last", last)
                    .param("title", blankToNull(r.get(5))).param("pos", pid).param("unit", unit.get()).param("status", status)
                    .param("cat", blankToNull(r.get(10))).param("station", blankToNull(r.get(11))).param("email", email)
                    .param("office", blankToNull(r.get(13))).param("mobile", blankToNull(r.get(14))).update());
            if (err.isPresent()) {
                exception(batchId, empNo, "OTHER", withError(rowMap("S", r), rootMessage(err.get())));
                continue;
            }
            applied.put(empNo, r);
            if (before.isEmpty()) {
                c.created++;
            } else {
                c.updated++;
                boolean wasActive = Set.of("ACTIVE", "ON_LEAVE").contains(before.get().employmentStatus());
                if (wasActive && Set.of("LEFT", "SUSPENDED").contains(status)) {
                    c.deactivated++;
                    outbox.write("staff_member", before.get().publicId(), DirEvents.STAFF_DEACTIVATED,
                            new DirEvents.StaffDeactivated(before.get().id(), before.get().publicId(), status));
                }
            }
        }
        return applied;
    }

    private void applyLineManagers(long batchId, Map<String, CsvReader.Row> applied, Counters c) {
        for (Map.Entry<String, CsvReader.Row> e : applied.entrySet()) {
            String empNo = e.getKey();
            String managerNo = e.getValue().get(8);
            if (managerNo.isEmpty()) {
                jdbc.sql("UPDATE dir.staff_member SET line_manager_id = NULL WHERE employee_no = :n AND line_manager_id IS NOT NULL").param("n", empNo).update();
                continue;
            }
            if (managerNo.equals(empNo)) {
                exception(batchId, empNo, "UNKNOWN_MANAGER", withError(rowMap("S", e.getValue()), "manager is the same person"));
                continue;
            }
            Optional<Long> manager = jdbc.sql("SELECT id FROM dir.staff_member WHERE employee_no = :m").param("m", managerNo).query(Long.class).optional();
            if (manager.isEmpty()) {
                exception(batchId, empNo, "UNKNOWN_MANAGER", rowMap("S", e.getValue()));
                continue;
            }
            jdbc.sql("UPDATE dir.staff_member SET line_manager_id = :m WHERE employee_no = :n AND line_manager_id IS DISTINCT FROM :m")
                    .param("m", manager.get()).param("n", empNo).update();
        }
    }

    // ------------------------------------------------------------------ helpers

    record Existing(long id, UUID publicId, String employmentStatus) {
    }

    private static final class Counters {
        int created, updated, deactivated, exceptions;
    }

    private int exceptionCount(long batchId) {
        return jdbc.sql("SELECT count(*) FROM dir.hr_sync_exception WHERE batch_id = :b").param("b", batchId).query(Integer.class).single();
    }

    private void exception(long batchId, String employeeNo, String reason, Map<String, ?> payload) {
        jdbc.sql("""
                INSERT INTO dir.hr_sync_exception (batch_id, employee_no, reason_code, payload)
                VALUES (:b, :e, :r, CAST(:p AS jsonb))
                """).param("b", batchId).param("e", employeeNo).param("r", reason).param("p", json(payload)).update();
    }

    private Optional<Long> unitId(String code) {
        return jdbc.sql("SELECT id FROM dir.org_unit WHERE code = :c").param("c", code).query(Long.class).optional();
    }

    private static List<CsvReader.Row> byType(List<CsvReader.Row> rows, String type) {
        return rows.stream().filter(r -> type.equalsIgnoreCase(r.get(0))).toList();
    }

    private static String checkTrailer(List<CsvReader.Row> trailer, int units, int positions, int staff) {
        if (trailer.size() != 1) {
            return "the file must end with exactly one trailer (T) record";
        }
        CsvReader.Row t = trailer.get(0);
        try {
            int u = Integer.parseInt(t.get(1)), o = Integer.parseInt(t.get(2)), s = Integer.parseInt(t.get(3));
            if (u != units || o != positions || s != staff) {
                return "trailer counts (U=%d, O=%d, S=%d) do not match the file (U=%d, O=%d, S=%d)".formatted(u, o, s, units, positions, staff);
            }
            return null;
        } catch (NumberFormatException e) {
            return "trailer counts are not numbers";
        }
    }

    private Map<String, Object> rowMap(String type, CsvReader.Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", r.lineNo());
        m.put("type", type);
        m.put("cells", r.cells());
        return m;
    }

    private static Map<String, Object> withError(Map<String, Object> m, String error) {
        m.put("error", error);
        return m;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean bool(String s) {
        return Set.of("Y", "YES", "TRUE", "1").contains(s.trim().toUpperCase());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m.split("\\R")[0];
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
