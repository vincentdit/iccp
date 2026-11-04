package tz.go.tirdo.iccp.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Application settings under the {@code iccp.*} prefix (see application.yml).
 */
@ConfigurationProperties("iccp")
public record IccpProperties(
        @DefaultValue Auth auth,
        @DefaultValue App app,
        @DefaultValue Files files,
        @DefaultValue Notifications notifications,
        @DefaultValue Integrations integrations,
        @DefaultValue Demo demo) {

    /**
     * @param mode     {@code oidc} (Keycloak, production) or {@code dev} (local signed tokens for development only)
     * @param issuer   OIDC issuer URL, e.g. https://auth.tirdo.go.tz/realms/tirdo
     * @param clientId public client id used by the Angular app
     * @param devSecret HMAC secret for dev tokens (at least 32 characters); ignored in oidc mode
     */
    public record Auth(@DefaultValue("oidc") String mode,
                       @DefaultValue("") String issuer,
                       @DefaultValue("iccp-web") String clientId,
                       @DefaultValue("") String devSecret,
                       @DefaultValue("http://localhost:4200") List<String> allowedOrigins) {
        public boolean isDev() {
            return "dev".equalsIgnoreCase(mode);
        }
    }

    /** @param publicUrl base URL of the web app, used in notification links */
    public record App(@DefaultValue("http://localhost:4200") String publicUrl,
                      @DefaultValue("0.1.0") String version) {
    }

    /**
     * @param store    {@code local} (filesystem, development) or {@code minio}
     * @param localDir directory for the local store
     */
    public record Files(@DefaultValue("local") String store,
                        @DefaultValue("./var/files") String localDir,
                        @DefaultValue("") String minioEndpoint,
                        @DefaultValue("") String minioAccessKey,
                        @DefaultValue("") String minioSecretKey,
                        @DefaultValue("iccp") String minioBucket,
                        @DefaultValue("20971520") long maxUploadBytes) {
    }

    /**
     * @param emailFrom sender address
     * @param emailEnabled send real email through spring.mail; when false, emails are logged
     * @param smsProvider {@code log} until an approved SMS gateway is contracted
     */
    public record Notifications(@DefaultValue("ICCP <no-reply@tirdo.go.tz>") String emailFrom,
                                @DefaultValue("false") boolean emailEnabled,
                                @DefaultValue("log") String smsProvider,
                                @DefaultValue("Africa/Dar_es_Salaam") String timeZone) {
    }

    public record Integrations(@DefaultValue("http://localhost:4200") String mmsUrl,
                               @DefaultValue("true") boolean mmsEnabled,
                               @DefaultValue("false") boolean internalMeetingsEnabled,
                               @DefaultValue("") String mmsSyncSecret) {
    }

    /** @param enabled load fictional demo staff, finance data and notices on an empty database */
    public record Demo(@DefaultValue("false") boolean enabled) {
    }
}
