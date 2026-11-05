package tz.go.tirdo.iccp.ntf.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
}
