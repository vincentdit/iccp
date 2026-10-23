package tz.go.tirdo.iccp.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tz.go.tirdo.iccp.platform.web.ApiException;

import java.util.Optional;

/** Access to the signed-in {@link IccpPrincipal} from services and controllers. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<IccpPrincipal> optional() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof IccpPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    public static IccpPrincipal get() {
        return optional().orElseThrow(() -> ApiException.unauthorized());
    }

    public static void require(String... anyOfRoles) {
        if (!get().hasAnyRole(anyOfRoles)) {
            throw ApiException.forbidden();
        }
    }
}
