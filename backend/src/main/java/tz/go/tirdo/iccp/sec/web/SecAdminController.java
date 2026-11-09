package tz.go.tirdo.iccp.sec.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.iccp.platform.web.PageResult;
import tz.go.tirdo.iccp.sec.SecAdminService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sec")
public class SecAdminController {

    private final SecAdminService service;

    public SecAdminController(SecAdminService service) {
        this.service = service;
    }
    @GetMapping("/users")
    @PreAuthorize("hasRole('SECURITY_ADMIN')")
    public PageResult<SecAdminService.UserAccount> users(@RequestParam(required = false) String q,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        return service.users(q, status, page, size);
    }

    @PostMapping("/users/{id}/status")
    @PreAuthorize("hasRole('SECURITY_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accountStatus(@PathVariable long id, @RequestBody SecAdminService.AccountStatusRequest request) {
        service.setAccountStatus(id, request);
    }


    @GetMapping("/roles")
    @PreAuthorize("hasAnyRole('SECURITY_ADMIN','AUDITOR')")
    public List<SecAdminService.RoleInfo> roles() {
        return service.roles();
    }

    @GetMapping("/role-assignments")
    @PreAuthorize("hasAnyRole('SECURITY_ADMIN','AUDITOR')")
    public List<SecAdminService.Assignment> assignments(@RequestParam(required = false) UUID staff, @RequestParam(required = false) String role) {
        return service.assignments(staff, role);
    }

    @PostMapping("/role-assignments")
    @PreAuthorize("hasRole('SECURITY_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public SecAdminService.Assignment assign(@RequestBody SecAdminService.AssignRequest request) {
        return service.assign(request);
    }

    @DeleteMapping("/role-assignments/{id}")
    @PreAuthorize("hasRole('SECURITY_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id) {
        service.revoke(id);
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('SECURITY_ADMIN','AUDITOR')")
    public PageResult<SecAdminService.AuditRow> audit(@RequestParam(required = false) String actor,
                                                      @RequestParam(required = false) String module,
                                                      @RequestParam(required = false) String action,
                                                      @RequestParam(required = false) String resourceId,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return service.searchAudit(actor, module, action, resourceId, from, to, page, size);
    }

    @PostMapping("/audit/verify")
    @PreAuthorize("hasAnyRole('SECURITY_ADMIN','AUDITOR')")
    public SecAdminService.ChainStatus verify() {
        return service.verifyChain();
    }
}
