package tz.go.tirdo.iccp.platform.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import tz.go.tirdo.iccp.platform.config.IccpProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Token validation. Production ({@code iccp.auth.mode=oidc}) trusts only Keycloak, discovered from
 * the issuer URL. Development ({@code dev}) uses locally signed HS256 tokens so the app can run
 * without Keycloak; it refuses to start under the {@code prod} profile.
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtDecoder jwtDecoder(IccpProperties props, Environment env) {
        IccpProperties.Auth auth = props.auth();
        if (auth.isDev()) {
            if (env.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("iccp.auth.mode=dev is not allowed with the prod profile");
            }
            return NimbusJwtDecoder.withSecretKey(devKey(auth)).macAlgorithm(MacAlgorithm.HS256).build();
        }
        if (auth.issuer().isBlank()) {
            throw new IllegalStateException("iccp.auth.issuer must be set when iccp.auth.mode=oidc");
        }
        // Lazy: the app starts even if Keycloak is briefly unavailable.
        return new SupplierJwtDecoder(() -> JwtDecoders.fromIssuerLocation(auth.issuer()));
    }

    /** Only used in dev mode to issue local tokens (see sec.web.DevAuthController). */
    @Bean
    public JwtEncoder devJwtEncoder(IccpProperties props) {
        if (!props.auth().isDev()) {
            return parameters -> {
                throw new IllegalStateException("Local tokens are only available in dev mode");
            };
        }
        return new NimbusJwtEncoder(new ImmutableSecret<>(devKey(props.auth())));
    }

    static SecretKey devKey(IccpProperties.Auth auth) {
        String secret = auth.devSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("iccp.auth.dev-secret must be at least 32 characters in dev mode");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
