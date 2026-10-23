package tz.go.tirdo.iccp.platform.security;

import java.util.Set;
import java.util.UUID;

/**
 * The signed-in person, resolved from the token and ICCP's own records (SEC and DIR).
 * Roles come from ICCP role assignments plus roles derived from the directory (Doc 03 §2.2);
 * Keycloak only proves who the person is.
 */
public record IccpPrincipal(
        long accountId,
        long staffId,
        UUID staffPublicId,
        String subject,
        String employeeNo,
        String displayName,
        String email,
        long orgUnitId,
        String language,
        Set<String> roles) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasAnyRole(String... wanted) {
        for (String r : wanted) {
            if (roles.contains(r)) {
                return true;
            }
        }
        return false;
    }

    /** Finance roles see all financial rows (RLS fin_scope = ALL); everyone else sees only their own. */
    public boolean hasFinanceScope() {
        return hasAnyRole(Roles.FIN_OFFICER, Roles.FIN_REVIEWER);
    }
}
