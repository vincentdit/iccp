package tz.go.tirdo.iccp.dir;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.dir.api.AudienceRule;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.dir.api.StaffContact;
import tz.go.tirdo.iccp.dir.api.StaffRef;
import tz.go.tirdo.iccp.dir.api.UnitRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class DirectoryService implements DirectoryApi {

    /** Shared SELECT for {@link StaffRef}. */
    static final String STAFF_REF = """
            SELECT s.id, s.public_id, s.employee_no,
                   concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name) AS display_name,
                   s.official_email AS email, s.org_unit_id, u.code AS org_unit_code, u.name_en AS org_unit_name,
                   p.title_en AS position_title, s.employment_status, s.preferred_language AS language,
                   s.line_manager_id
              FROM dir.staff_member s
              JOIN dir.org_unit u ON u.id = s.org_unit_id
              LEFT JOIN dir.position p ON p.id = s.position_id
            """;

    private final JdbcClient jdbc;

    public DirectoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StaffRef> findById(long staffId) {
        return jdbc.sql(STAFF_REF + " WHERE s.id = :id").param("id", staffId).query(StaffRef.class).optional();
    }

    @Override
    public Optional<StaffRef> findByPublicId(UUID publicId) {
        return jdbc.sql(STAFF_REF + " WHERE s.public_id = :id").param("id", publicId).query(StaffRef.class).optional();
    }

    @Override
    public Optional<StaffRef> findByEmployeeNo(String employeeNo) {
        return jdbc.sql(STAFF_REF + " WHERE s.employee_no = :no").param("no", employeeNo).query(StaffRef.class).optional();
    }

    @Override
    public Optional<StaffRef> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql(STAFF_REF + " WHERE lower(s.official_email) = lower(:email)").param("email", email).query(StaffRef.class).optional();
    }

    @Override
    public List<StaffContact> contacts(Collection<Long> staffIds) {
        if (staffIds.isEmpty()) {
            return List.of();
        }
        List<StaffContact> out = new ArrayList<>();
        List<Long> ids = new ArrayList<>(staffIds);
        for (int i = 0; i < ids.size(); i += 1000) {
            out.addAll(jdbc.sql("""
                    SELECT id AS staff_id, concat_ws(' ', nullif(title, ''), first_name, last_name) AS display_name,
                           official_email AS email, mobile_phone, preferred_language AS language,
                           employment_status IN ('ACTIVE','ON_LEAVE') AS active
                      FROM dir.staff_member WHERE id IN (:ids)
                    """).param("ids", ids.subList(i, Math.min(i + 1000, ids.size()))).query(StaffContact.class).list());
        }
        return out;
    }

    @Override
    public Optional<Long> lineManagerOf(long staffId) {
        return jdbc.sql("SELECT line_manager_id FROM dir.staff_member WHERE id = :id AND line_manager_id IS NOT NULL")
                .param("id", staffId).query(Long.class).optional();
    }

    @Override
    public boolean hasDirectReports(long staffId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dir.staff_member WHERE line_manager_id = :id AND employment_status IN ('ACTIVE','ON_LEAVE'))")
                .param("id", staffId).query(Boolean.class).single();
    }

    @Override
    public List<UnitRef> unitsHeadedBy(long staffId) {
        return jdbc.sql("""
                SELECT DISTINCT u.id, u.code, u.name_en, u.name_sw, u.unit_type
                  FROM dir.position p
                  JOIN dir.org_unit u ON u.id = p.org_unit_id
                 WHERE p.is_head
                   AND (p.id = (SELECT position_id FROM dir.staff_member WHERE id = :id)
                        OR p.id IN (SELECT position_id FROM dir.acting_appointment
                                     WHERE staff_id = :id AND current_date BETWEEN starts_on AND ends_on))
                """).param("id", staffId).query(UnitRef.class).list();
    }

    @Override
    public Set<Long> resolveAudience(List<AudienceRule> rules) {
        Set<Long> ids = new LinkedHashSet<>();
        String active = " AND s.employment_status IN ('ACTIVE','ON_LEAVE')";
        for (AudienceRule r : rules) {
            String v = r.value() == null ? "" : r.value().trim();
            switch (r.type()) {
                case "ALL" -> ids.addAll(jdbc.sql("SELECT s.id FROM dir.staff_member s WHERE true" + active).query(Long.class).list());
                case "ORG_UNIT" -> ids.addAll(jdbc.sql("SELECT s.id FROM dir.staff_member s JOIN dir.org_unit u ON u.id = s.org_unit_id WHERE u.code = :v" + active)
                        .param("v", v).query(Long.class).list());
                case "ORG_UNIT_TREE" -> findUnitByCode(v).ifPresent(u -> ids.addAll(
                        jdbc.sql("SELECT s.id FROM dir.staff_member s WHERE s.org_unit_id IN (:units)" + active)
                                .param("units", List.copyOf(unitSubtree(u.id()))).query(Long.class).list()));
                case "STAFF_CATEGORY" -> ids.addAll(jdbc.sql("SELECT s.id FROM dir.staff_member s WHERE lower(s.staff_category) = lower(:v)" + active)
                        .param("v", v).query(Long.class).list());
                case "DUTY_STATION" -> ids.addAll(jdbc.sql("SELECT s.id FROM dir.staff_member s WHERE lower(s.duty_station) = lower(:v)" + active)
                        .param("v", v).query(Long.class).list());
                case "STAFF" -> {
                    try {
                        ids.addAll(jdbc.sql("SELECT s.id FROM dir.staff_member s WHERE s.public_id = :v" + active)
                                .param("v", UUID.fromString(v)).query(Long.class).list());
                    } catch (IllegalArgumentException ignored) {
                        // not a valid id: matches nobody
                    }
                }
                default -> { /* ROLE handled by the caller */ }
            }
        }
        return ids;
    }

    @Override
    public Set<Long> lineManagers() {
        return new HashSet<>(jdbc.sql("""
                SELECT DISTINCT m.id FROM dir.staff_member m
                  JOIN dir.staff_member s ON s.line_manager_id = m.id AND s.employment_status IN ('ACTIVE','ON_LEAVE')
                 WHERE m.employment_status IN ('ACTIVE','ON_LEAVE')
                """).query(Long.class).list());
    }

    @Override
    public Set<Long> unitHeads(Set<String> unitTypes) {
        List<String> types = unitTypes == null || unitTypes.isEmpty()
                ? List.of("DIRECTORATE", "DEPARTMENT", "SECTION", "UNIT", "LABORATORY", "OFFICE") : List.copyOf(unitTypes);
        return new HashSet<>(jdbc.sql("""
                SELECT s.id FROM dir.staff_member s JOIN dir.position p ON p.id = s.position_id JOIN dir.org_unit u ON u.id = p.org_unit_id
                 WHERE p.is_head AND u.unit_type IN (:types) AND s.employment_status IN ('ACTIVE','ON_LEAVE')
                UNION
                SELECT a.staff_id FROM dir.acting_appointment a JOIN dir.position p ON p.id = a.position_id JOIN dir.org_unit u ON u.id = p.org_unit_id
                 WHERE p.is_head AND u.unit_type IN (:types) AND current_date BETWEEN a.starts_on AND a.ends_on
                """).param("types", types).query(Long.class).list());
    }

    @Override
    public Set<Long> unitSubtree(long unitId) {
        return new HashSet<>(jdbc.sql("""
                WITH RECURSIVE t AS (
                    SELECT id FROM dir.org_unit WHERE id = :id
                    UNION ALL
                    SELECT u.id FROM dir.org_unit u JOIN t ON u.parent_id = t.id
                )
                SELECT id FROM t
                """).param("id", unitId).query(Long.class).list());
    }

    @Override
    public Optional<UnitRef> findUnitByCode(String code) {
        return jdbc.sql("SELECT id, code, name_en, name_sw, unit_type FROM dir.org_unit WHERE code = :c")
                .param("c", code).query(UnitRef.class).optional();
    }

    @Override
    @Transactional
    public void updatePreferredLanguage(long staffId, String language) {
        if (!"en".equals(language) && !"sw".equals(language)) {
            throw new IllegalArgumentException("language");
        }
        jdbc.sql("UPDATE dir.staff_member SET preferred_language = :l WHERE id = :id").param("l", language).param("id", staffId).update();
    }
}
