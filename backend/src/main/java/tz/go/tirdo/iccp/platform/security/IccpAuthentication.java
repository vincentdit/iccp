package tz.go.tirdo.iccp.platform.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.stream.Collectors;

/** Authentication carrying the {@link IccpPrincipal}; authorities are {@code ROLE_<code>}. */
public class IccpAuthentication extends AbstractAuthenticationToken {

    private final IccpPrincipal principal;
    private final Jwt jwt;

    public IccpAuthentication(IccpPrincipal principal, Jwt jwt) {
        super(principal.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).collect(Collectors.toSet()));
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public IccpPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.subject();
    }
}
