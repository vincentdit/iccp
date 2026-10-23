package tz.go.tirdo.iccp.platform.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves a validated token to an ICCP principal (implemented by the SEC module).
 * Throws {@link tz.go.tirdo.iccp.platform.web.ApiException} when the person has no active account.
 */
public interface PrincipalLoader {

    IccpPrincipal load(Jwt jwt);
}
