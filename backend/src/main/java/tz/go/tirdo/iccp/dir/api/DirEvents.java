package tz.go.tirdo.iccp.dir.api;

import java.util.UUID;

/** Events DIR publishes through the outbox. Payload records are the published contract. */
public final class DirEvents {

    public static final String STAFF_DEACTIVATED = "StaffDeactivated";
    public static final String HR_SYNC_COMPLETED = "HrSyncCompleted";

    /** HR set the person to LEFT or SUSPENDED: SEC must revoke access within 1 hour (BRL-20). */
    public record StaffDeactivated(long staffId, UUID staffPublicId, String employmentStatus) {
    }

    public record HrSyncCompleted(long batchId, String fileName, int created, int updated, int deactivated, int exceptions) {
    }

    private DirEvents() {
    }
}
