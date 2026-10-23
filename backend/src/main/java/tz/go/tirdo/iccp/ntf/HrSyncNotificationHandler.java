package tz.go.tirdo.iccp.ntf;

import org.springframework.stereotype.Component;
import tz.go.tirdo.iccp.dir.api.DirEvents;
import tz.go.tirdo.iccp.ntf.api.NotificationRequest;
import tz.go.tirdo.iccp.ntf.api.NtfCategory;
import tz.go.tirdo.iccp.platform.outbox.OutboxHandler;
import tz.go.tirdo.iccp.platform.outbox.OutboxMessage;
import tz.go.tirdo.iccp.platform.security.Roles;
import tz.go.tirdo.iccp.sec.api.RoleDirectory;

import java.util.Map;

/** Tells HR officers when an HR import left records in the exception queue (BP-DIR-01 step 3). */
@Component
public class HrSyncNotificationHandler implements OutboxHandler {

    private final NotificationService notifications;
    private final RoleDirectory roles;

    public HrSyncNotificationHandler(NotificationService notifications, RoleDirectory roles) {
        this.notifications = notifications;
        this.roles = roles;
    }

    @Override
    public String eventType() {
        return DirEvents.HR_SYNC_COMPLETED;
    }

    @Override
    public void handle(OutboxMessage m) {
        int exceptions = m.payload().path("exceptions").asInt();
        if (exceptions == 0) {
            return;
        }
        String file = m.payload().path("fileName").asText("HR file");
        notifications.notify(NotificationRequest.inApp(roles.staffWithRole(Roles.HR_OFFICER), "HrSyncExceptions", NtfCategory.DIRECTORY,
                Map.of("file", file, "count", String.valueOf(exceptions)),
                "/admin/hr-imports/" + m.payload().path("batchId").asLong(), "DIR", m.aggregateId()));
    }
}
