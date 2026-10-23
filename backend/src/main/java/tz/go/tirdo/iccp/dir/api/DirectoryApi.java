package tz.go.tirdo.iccp.dir.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Published interface of the DIR module (shared kernel, Doc 06 §2). Other modules use this — never
 * the dir.* tables — to look up staff, contacts, reporting lines and audiences.
 */
public interface DirectoryApi {

    Optional<StaffRef> findById(long staffId);

    Optional<StaffRef> findByPublicId(UUID publicId);

    Optional<StaffRef> findByEmployeeNo(String employeeNo);

    Optional<StaffRef> findByEmail(String email);

    /** Contact details for notifications; mobile is included even when hidden from the directory. */
    List<StaffContact> contacts(Collection<Long> staffIds);

    Optional<Long> lineManagerOf(long staffId);

    boolean hasDirectReports(long staffId);

    /** Units the person heads today, substantively or by acting appointment (BRL-21). */
    List<UnitRef> unitsHeadedBy(long staffId);

    /** Active staff matching any of the rules (ALL, ORG_UNIT, ORG_UNIT_TREE, STAFF_CATEGORY, DUTY_STATION, STAFF). ROLE rules are resolved by the caller through SEC. */
    Set<Long> resolveAudience(List<AudienceRule> rules);

    /** Active staff who have at least one active direct report. */
    Set<Long> lineManagers();

    /** Active staff who head (substantively or acting today) a unit of one of the given types; all types if empty. */
    Set<Long> unitHeads(Set<String> unitTypes);

    /** The unit and all units beneath it. */
    Set<Long> unitSubtree(long unitId);

    Optional<UnitRef> findUnitByCode(String code);

    void updatePreferredLanguage(long staffId, String language);
}
