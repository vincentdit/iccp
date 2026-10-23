package tz.go.tirdo.iccp.dir.api;

import java.util.UUID;

/** A staff member as other modules see them. */
public record StaffRef(
        long id,
        UUID publicId,
        String employeeNo,
        String displayName,
        String email,
        long orgUnitId,
        String orgUnitCode,
        String orgUnitName,
        String positionTitle,
        String employmentStatus,
        String language,
        Long lineManagerId) {

    public boolean isActive() {
        return "ACTIVE".equals(employmentStatus) || "ON_LEAVE".equals(employmentStatus);
    }
}
