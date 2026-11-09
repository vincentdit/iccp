package tz.go.tirdo.iccp.sec.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.dir.api.StaffRef;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;
import tz.go.tirdo.iccp.platform.security.Roles;
import tz.go.tirdo.iccp.sec.PrincipalService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The signed-in person: identity, roles and what the UI may offer them. */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final DirectoryApi directory;
    private final PrincipalService principals;

    public MeController(DirectoryApi directory, PrincipalService principals) {
        this.directory = directory;
        this.principals = principals;
    }

    public record Me(UUID staffPublicId, String employeeNo, String displayName, String email, String positionTitle,
                     String unitCode, String unitName, String language, List<String> roles, Map<String, Boolean> can) {
    }

    @GetMapping
    public Me me() {
        IccpPrincipal p = CurrentUser.get();
        StaffRef s = directory.findById(p.staffId()).orElseThrow();
        Map<String, Boolean> can = new LinkedHashMap<>();
        can.put("publishAnnouncements", p.hasAnyRole(Roles.COMMS_PUBLISHER, Roles.EMERGENCY_APPROVER));
        can.put("approveAnnouncements", p.hasAnyRole(Roles.COMMS_APPROVER));
        can.put("emergencyAlerts", p.hasRole(Roles.EMERGENCY_APPROVER));
        can.put("viewReach", p.hasAnyRole(Roles.COMMS_PUBLISHER, Roles.COMMS_APPROVER, Roles.DIRECTOR, Roles.EXECUTIVE));
        can.put("financeWorkspace", p.hasAnyRole(Roles.FIN_OFFICER, Roles.FIN_REVIEWER));
        can.put("financeImport", p.hasAnyRole(Roles.FIN_OFFICER, Roles.FIN_REVIEWER));
        can.put("financeReview", p.hasRole(Roles.FIN_REVIEWER));
        can.put("hrAdmin", p.hasRole(Roles.HR_OFFICER));
        can.put("securityAdmin", p.hasRole(Roles.SECURITY_ADMIN));
        can.put("systemAdmin", p.hasRole(Roles.SYS_ADMIN));
        can.put("auditSearch", p.hasAnyRole(Roles.SECURITY_ADMIN, Roles.AUDITOR));
        can.put("manageMeetings", p.hasAnyRole(Roles.MEETING_SECRETARY, Roles.MEETING_CHAIR, Roles.DIRECTOR, Roles.EXECUTIVE));
        return new Me(p.staffPublicId(), p.employeeNo(), s.displayName(), s.email(), s.positionTitle(), s.orgUnitCode(),
                s.orgUnitName(), s.language(), List.copyOf(p.roles()), can);
    }

    public record LanguageRequest(String language) {
    }

    @PutMapping("/language")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void language(@RequestBody LanguageRequest req) {
        IccpPrincipal p = CurrentUser.get();
        directory.updatePreferredLanguage(p.staffId(), req.language());
        principals.evict(p.subject());
    }
}
