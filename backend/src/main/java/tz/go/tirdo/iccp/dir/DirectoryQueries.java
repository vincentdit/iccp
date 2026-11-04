package tz.go.tirdo.iccp.dir;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.platform.web.PageResult;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read and self-service operations behind the Directory screens (SCR-DIR-01..04). */
@Service
public class DirectoryQueries {

    private final JdbcClient jdbc;
    private final AuditService audit;

    public DirectoryQueries(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record StaffSummary(UUID publicId, String employeeNo, String displayName, String positionTitle, String unitCode,
                               String unitName, String email, String officePhone, String mobilePhone, String employmentStatus,
                               List<String> expertise) {
    }

    public record PersonLink(UUID publicId, String displayName, String positionTitle) {
    }

    public record StaffProfile(UUID publicId, String employeeNo, String title, String firstName, String middleName, String lastName,
                               String displayName, String positionTitle, String unitCode, String unitName, String unitNameSw,
                               String email, String officePhone, String mobilePhone, boolean mobileVisible, String dutyStation,
                               String staffCategory, String employmentStatus, String preferredLanguage, String bio,
                               List<String> expertise, PersonLink lineManager, List<PersonLink> directReports,
                               List<ActingRole> actingRoles, boolean self) {
    }

    public record ActingRole(String positionTitle, String unitName, LocalDate startsOn, LocalDate endsOn) {
    }

    public record UnitNode(long id, String code, String nameEn, String nameSw, String unitType, PersonLink head, boolean headActing,
                           int staffCount, List<UnitNode> children) {
    }

    public record ProfileUpdate(String bio, List<String> expertise, Boolean mobileVisible, String preferredLanguage) {
    }

    public record DirectoryExport(UUID staffPublicId, String employeeNo, String displayName, String email,
                                  String mobilePhone, String unitCode, String unitName, String positionTitle,
                                  String supervisorEmployeeNo, String preferredLanguage, boolean active) {
    }

    /** Service-to-service directory projection. It deliberately excludes biographies and private profile data. */
    @Transactional(readOnly = true)
    public List<DirectoryExport> exportForMms() {
        return jdbc.sql("""
                SELECT s.public_id AS staff_public_id, s.employee_no,
                       concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name) AS display_name,
                       s.official_email AS email, s.mobile_phone, u.code AS unit_code, u.name_en AS unit_name,
                       p.title_en AS position_title, manager.employee_no AS supervisor_employee_no,
                       s.preferred_language,
                       (s.employment_status IN ('ACTIVE','ON_LEAVE')) AS active
                  FROM dir.staff_member s
                  JOIN dir.org_unit u ON u.id = s.org_unit_id
                  LEFT JOIN dir.position p ON p.id = s.position_id
                  LEFT JOIN dir.staff_member manager ON manager.id = s.line_manager_id
                 ORDER BY s.employee_no
                """).query(DirectoryExport.class).list();
    }

    private static final String SUMMARY_SELECT = """
            SELECT s.id, s.public_id, s.employee_no,
                   concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name) AS display_name,
                   p.title_en AS position_title, u.code AS unit_code, u.name_en AS unit_name, s.official_email AS email,
                   s.office_phone, CASE WHEN s.mobile_visible THEN s.mobile_phone END AS mobile_phone, s.employment_status
              FROM dir.staff_member s
              JOIN dir.org_unit u ON u.id = s.org_unit_id
              LEFT JOIN dir.position p ON p.id = s.position_id
            """;

    record SummaryRow(long id, UUID publicId, String employeeNo, String displayName, String positionTitle, String unitCode,
                      String unitName, String email, String officePhone, String mobilePhone, String employmentStatus) {
    }

    @Transactional(readOnly = true)
    public PageResult<StaffSummary> search(String q, String unitCode, Integer page, Integer size) {
        int p = PageResult.clampPage(page), sz = PageResult.clampSize(size);
        StringBuilder where = new StringBuilder(" WHERE s.employment_status IN ('ACTIVE','ON_LEAVE')");
        Map<String, Object> params = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            where.append("""
                     AND (s.first_name ILIKE :q OR s.last_name ILIKE :q OR s.employee_no ILIKE :q OR s.official_email ILIKE :q
                          OR concat_ws(' ', s.first_name, s.last_name) ILIKE :q OR p.title_en ILIKE :q OR u.name_en ILIKE :q
                          OR EXISTS (SELECT 1 FROM dir.staff_expertise x WHERE x.staff_id = s.id AND x.tag ILIKE :q))
                    """);
            params.put("q", "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (unitCode != null && !unitCode.isBlank()) {
            where.append(" AND u.code = :unit");
            params.put("unit", unitCode);
        }
        long total = jdbc.sql("SELECT count(*) FROM dir.staff_member s JOIN dir.org_unit u ON u.id = s.org_unit_id LEFT JOIN dir.position p ON p.id = s.position_id" + where)
                .params(params).query(Long.class).single();
        List<SummaryRow> rows = jdbc.sql(SUMMARY_SELECT + where + " ORDER BY s.last_name, s.first_name LIMIT :lim OFFSET :off")
                .params(params).param("lim", sz).param("off", p * sz).query(SummaryRow.class).list();
        Map<Long, List<String>> tags = expertise(rows.stream().map(SummaryRow::id).toList());
        List<StaffSummary> items = rows.stream().map(r -> new StaffSummary(r.publicId(), r.employeeNo(), r.displayName(), r.positionTitle(),
                r.unitCode(), r.unitName(), r.email(), r.officePhone(), r.mobilePhone(), r.employmentStatus(), tags.getOrDefault(r.id(), List.of()))).toList();
        return new PageResult<>(items, total, p, sz);
    }

    record ProfileRow(long id, UUID publicId, String employeeNo, String title, String firstName, String middleName, String lastName,
                      String displayName, String positionTitle, String unitCode, String unitName, String unitNameSw, String email,
                      String officePhone, String mobilePhone, boolean mobileVisible, String dutyStation, String staffCategory,
                      String employmentStatus, String preferredLanguage, String bio, Long lineManagerId) {
    }

    @Transactional(readOnly = true)
    public StaffProfile profile(UUID publicId) {
        IccpPrincipal me = CurrentUser.get();
        ProfileRow r = jdbc.sql("""
                SELECT s.id, s.public_id, s.employee_no, s.title, s.first_name, s.middle_name, s.last_name,
                       concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name) AS display_name,
                       p.title_en AS position_title, u.code AS unit_code, u.name_en AS unit_name, u.name_sw AS unit_name_sw,
                       s.official_email AS email, s.office_phone, s.mobile_phone, s.mobile_visible, s.duty_station, s.staff_category,
                       s.employment_status, s.preferred_language, s.bio, s.line_manager_id
                  FROM dir.staff_member s
                  JOIN dir.org_unit u ON u.id = s.org_unit_id
                  LEFT JOIN dir.position p ON p.id = s.position_id
                 WHERE s.public_id = :id
                """).param("id", publicId).query(ProfileRow.class).optional().orElseThrow(ApiException::notFound);
        boolean self = r.id() == me.staffId();
        if (!self && !Set.of("ACTIVE", "ON_LEAVE").contains(r.employmentStatus())) {
            throw ApiException.notFound();
        }
        PersonLink manager = r.lineManagerId() == null ? null : link(r.lineManagerId());
        List<PersonLink> reports = jdbc.sql("""
                SELECT s.public_id, concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name) AS display_name, p.title_en AS position_title
                  FROM dir.staff_member s LEFT JOIN dir.position p ON p.id = s.position_id
                 WHERE s.line_manager_id = :id AND s.employment_status IN ('ACTIVE','ON_LEAVE')
                 ORDER BY s.last_name, s.first_name
                """).param("id", r.id()).query(PersonLink.class).list();
        List<ActingRole> acting = jdbc.sql("""
                SELECT p.title_en AS position_title, u.name_en AS unit_name, a.starts_on, a.ends_on
                  FROM dir.acting_appointment a JOIN dir.position p ON p.id = a.position_id JOIN dir.org_unit u ON u.id = p.org_unit_id
                 WHERE a.staff_id = :id AND a.ends_on >= current_date ORDER BY a.starts_on
                """).param("id", r.id()).query(ActingRole.class).list();
        String mobile = self || r.mobileVisible() ? r.mobilePhone() : null;
        return new StaffProfile(r.publicId(), r.employeeNo(), r.title(), r.firstName(), r.middleName(), r.lastName(), r.displayName(),
                r.positionTitle(), r.unitCode(), r.unitName(), r.unitNameSw(), r.email(), r.officePhone(), mobile, r.mobileVisible(),
                r.dutyStation(), r.staffCategory(), r.employmentStatus(), self ? r.preferredLanguage() : null, r.bio(),
                expertise(List.of(r.id())).getOrDefault(r.id(), List.of()), manager, reports, acting, self);
    }

    @Transactional
    public StaffProfile updateMe(ProfileUpdate u) {
        IccpPrincipal me = CurrentUser.get();
        if (u.bio() != null) {
            String bio = u.bio().strip();
            if (bio.length() > 1000) {
                throw ApiException.badRequest("ICCP-DIR-001", "dir.bioTooLong");
            }
            jdbc.sql("UPDATE dir.staff_member SET bio = :b WHERE id = :id").param("b", bio.isEmpty() ? null : bio).param("id", me.staffId()).update();
        }
        if (u.mobileVisible() != null) {
            jdbc.sql("UPDATE dir.staff_member SET mobile_visible = :v WHERE id = :id").param("v", u.mobileVisible()).param("id", me.staffId()).update();
        }
        if (u.preferredLanguage() != null) {
            if (!Set.of("en", "sw").contains(u.preferredLanguage())) {
                throw ApiException.badRequest("ICCP-DIR-002", "dir.language");
            }
            jdbc.sql("UPDATE dir.staff_member SET preferred_language = :l WHERE id = :id").param("l", u.preferredLanguage()).param("id", me.staffId()).update();
        }
        if (u.expertise() != null) {
            List<String> tags = u.expertise().stream().map(String::strip).filter(t -> !t.isEmpty() && t.length() <= 60).distinct().limit(20).toList();
            jdbc.sql("DELETE FROM dir.staff_expertise WHERE staff_id = :id").param("id", me.staffId()).update();
            for (String t : tags) {
                jdbc.sql("INSERT INTO dir.staff_expertise (staff_id, tag) VALUES (:id, :t)").param("id", me.staffId()).param("t", t).update();
            }
        }
        audit.record("DIR", "PROFILE_UPDATE", "staff_member", me.staffPublicId(), Map.of());
        return profile(me.staffPublicId());
    }

    record UnitRow(long id, Long parentId, String code, String nameEn, String nameSw, String unitType) {
    }

    record HeadRow(long unitId, UUID publicId, String displayName, String positionTitle, boolean acting) {
    }

    @Transactional(readOnly = true)
    public List<UnitNode> unitTree() {
        List<UnitRow> units = jdbc.sql("""
                SELECT id, parent_id, code, name_en, name_sw, unit_type FROM dir.org_unit
                 WHERE effective_to IS NULL OR effective_to >= current_date ORDER BY name_en
                """).query(UnitRow.class).list();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT org_unit_id, count(*) AS n FROM dir.staff_member WHERE employment_status IN ('ACTIVE','ON_LEAVE') GROUP BY org_unit_id")
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2))).list().forEach(e -> counts.put(e.getKey(), e.getValue()));
        // Acting appointments take precedence over the substantive holder for the period (BRL-21).
        Map<Long, HeadRow> heads = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT p.org_unit_id AS unit_id, s.public_id, concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name) AS display_name,
                       p.title_en AS position_title, false AS acting
                  FROM dir.position p JOIN dir.staff_member s ON s.position_id = p.id
                 WHERE p.is_head AND s.employment_status IN ('ACTIVE','ON_LEAVE')
                UNION ALL
                SELECT p.org_unit_id, s.public_id, concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name), p.title_en, true
                  FROM dir.acting_appointment a JOIN dir.position p ON p.id = a.position_id JOIN dir.staff_member s ON s.id = a.staff_id
                 WHERE p.is_head AND current_date BETWEEN a.starts_on AND a.ends_on
                """).query(HeadRow.class).list().forEach(h -> heads.merge(h.unitId(), h, (a, b) -> b.acting() ? b : a));
        Map<Long, List<UnitRow>> byParent = units.stream().filter(u -> u.parentId() != null).collect(Collectors.groupingBy(UnitRow::parentId));
        Set<Long> ids = units.stream().map(UnitRow::id).collect(Collectors.toSet());
        List<UnitNode> roots = new ArrayList<>();
        for (UnitRow u : units) {
            if (u.parentId() == null || !ids.contains(u.parentId())) {
                roots.add(node(u, byParent, counts, heads));
            }
        }
        return roots;
    }

    private UnitNode node(UnitRow u, Map<Long, List<UnitRow>> byParent, Map<Long, Integer> counts, Map<Long, HeadRow> heads) {
        List<UnitNode> children = byParent.getOrDefault(u.id(), List.of()).stream().map(c -> node(c, byParent, counts, heads)).toList();
        HeadRow h = heads.get(u.id());
        PersonLink head = h == null ? null : new PersonLink(h.publicId(), h.displayName(), h.positionTitle());
        return new UnitNode(u.id(), u.code(), u.nameEn(), u.nameSw(), u.unitType(), head, h != null && h.acting(),
                counts.getOrDefault(u.id(), 0), children);
    }

    // ------------------------------------------------------------------ acting appointments

    public record ActingAppointment(long id, String positionCode, String positionTitle, String unitName, UUID staffPublicId,
                                    String staffName, LocalDate startsOn, LocalDate endsOn, String reason, String approvedBy) {
    }

    public record ActingRequest(String positionCode, UUID staffPublicId, LocalDate startsOn, LocalDate endsOn, String reason, String approvedBy) {
    }

    @Transactional(readOnly = true)
    public List<ActingAppointment> actingAppointments() {
        return jdbc.sql("""
                SELECT a.id, p.code AS position_code, p.title_en AS position_title, u.name_en AS unit_name, s.public_id AS staff_public_id,
                       concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name) AS staff_name, a.starts_on, a.ends_on, a.reason, a.approved_by
                  FROM dir.acting_appointment a
                  JOIN dir.position p ON p.id = a.position_id JOIN dir.org_unit u ON u.id = p.org_unit_id
                  JOIN dir.staff_member s ON s.id = a.staff_id
                 WHERE a.ends_on >= current_date ORDER BY a.starts_on
                """).query(ActingAppointment.class).list();
    }

    @Transactional
    public ActingAppointment createActing(ActingRequest r) {
        if (r.positionCode() == null || r.staffPublicId() == null || r.startsOn() == null || r.endsOn() == null
                || r.approvedBy() == null || r.approvedBy().isBlank()) {
            throw ApiException.badRequest("ICCP-DIR-010", "dir.actingMissing");
        }
        if (r.endsOn().isBefore(r.startsOn())) {
            throw ApiException.badRequest("ICCP-DIR-011", "dir.actingDates");
        }
        Long positionId = jdbc.sql("SELECT id FROM dir.position WHERE code = :c").param("c", r.positionCode()).query(Long.class).optional()
                .orElseThrow(() -> ApiException.badRequest("ICCP-DIR-012", "dir.unknownPosition"));
        Long staffId = jdbc.sql("SELECT id FROM dir.staff_member WHERE public_id = :p AND employment_status = 'ACTIVE'").param("p", r.staffPublicId())
                .query(Long.class).optional().orElseThrow(() -> ApiException.badRequest("ICCP-DIR-013", "dir.unknownStaff"));
        boolean overlap = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM dir.acting_appointment WHERE position_id = :p AND starts_on <= :e AND ends_on >= :s)
                """).param("p", positionId).param("s", r.startsOn()).param("e", r.endsOn()).query(Boolean.class).single();
        if (overlap) {
            throw ApiException.conflict("ICCP-DIR-014", "dir.actingOverlap");
        }
        long id = jdbc.sql("""
                INSERT INTO dir.acting_appointment (position_id, staff_id, starts_on, ends_on, reason, approved_by)
                VALUES (:p, :s, :from, :to, :reason, :by) RETURNING id
                """).param("p", positionId).param("s", staffId).param("from", r.startsOn()).param("to", r.endsOn())
                .param("reason", r.reason()).param("by", r.approvedBy()).query(Long.class).single();
        audit.record("DIR", "ACTING_CREATE", "acting_appointment", id, Map.of("position", r.positionCode()));
        return actingAppointments().stream().filter(a -> a.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public void endActing(long id) {
        int n = jdbc.sql("""
                UPDATE dir.acting_appointment SET ends_on = greatest(starts_on, current_date - 1) WHERE id = :id AND starts_on < current_date
                """).param("id", id).update();
        if (n == 0) {
            n = jdbc.sql("DELETE FROM dir.acting_appointment WHERE id = :id").param("id", id).update();
        }
        if (n == 0) {
            throw ApiException.notFound();
        }
        audit.record("DIR", "ACTING_END", "acting_appointment", id, Map.of());
    }

    // ------------------------------------------------------------------ imports (HR officers)

    public record HrBatch(long id, String fileName, String status, int recordsTotal, int createdCount, int updatedCount,
                          int deactivatedCount, int exceptionCount, String createdBy, OffsetDateTime createdAt) {
    }

    public record HrException(long id, String employeeNo, String reasonCode, String payload, String status, String resolvedBy,
                              OffsetDateTime resolvedAt) {
    }

    @Transactional(readOnly = true)
    public List<HrBatch> batches() {
        return jdbc.sql("""
                SELECT id, file_name, status, records_total, created_count, updated_count, deactivated_count, exception_count, created_by, created_at
                  FROM dir.hr_sync_batch ORDER BY id DESC LIMIT 50
                """).query(HrBatch.class).list();
    }

    @Transactional(readOnly = true)
    public List<HrException> exceptions(long batchId) {
        return jdbc.sql("""
                SELECT id, employee_no, reason_code, payload::text AS payload, status, resolved_by, resolved_at
                  FROM dir.hr_sync_exception WHERE batch_id = :b ORDER BY id
                """).param("b", batchId).query(HrException.class).list();
    }

    @Transactional
    public void closeException(long id, String status) {
        if (!Set.of("RESOLVED", "IGNORED").contains(status)) {
            throw ApiException.badRequest("ICCP-DIR-020", "error.badRequest");
        }
        int n = jdbc.sql("""
                UPDATE dir.hr_sync_exception SET status = :s, resolved_by = :by, resolved_at = now() WHERE id = :id AND status = 'OPEN'
                """).param("s", status).param("by", CurrentUser.get().subject()).param("id", id).update();
        if (n == 0) {
            throw ApiException.notFound();
        }
        audit.record("DIR", "HR_EXCEPTION_" + status, "hr_sync_exception", id, Map.of());
    }

    // ------------------------------------------------------------------ helpers

    private PersonLink link(long staffId) {
        return jdbc.sql("""
                SELECT s.public_id, concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name) AS display_name, p.title_en AS position_title
                  FROM dir.staff_member s LEFT JOIN dir.position p ON p.id = s.position_id WHERE s.id = :id
                """).param("id", staffId).query(PersonLink.class).optional().orElse(null);
    }

    private Map<Long, List<String>> expertise(List<Long> staffIds) {
        if (staffIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT staff_id, tag FROM dir.staff_expertise WHERE staff_id IN (:ids) ORDER BY tag")
                .param("ids", staffIds)
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2))).list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }
}
