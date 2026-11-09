package tz.go.tirdo.iccp.ntf.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.iccp.ntf.NotificationOperationsService;

@RestController
@RequestMapping("/api/v1/ntf/admin")
public class NotificationAdminController {
    private final NotificationOperationsService operations;

    public NotificationAdminController(NotificationOperationsService operations) { this.operations = operations; }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('SECURITY_ADMIN','AUDITOR')")
    public NotificationOperationsService.Summary summary() { return operations.summary(); }

    @PostMapping("/dead-letters/{notificationId}/{channel}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SECURITY_ADMIN')")
    public void retry(@PathVariable long notificationId, @PathVariable String channel) {
        operations.retry(notificationId, channel.toUpperCase());
    }
}
