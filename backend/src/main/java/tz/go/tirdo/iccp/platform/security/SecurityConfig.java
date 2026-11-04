package tz.go.tirdo.iccp.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tz.go.tirdo.iccp.platform.config.IccpProperties;
import tz.go.tirdo.iccp.platform.web.ApiError;
import tz.go.tirdo.iccp.platform.web.RequestIds;

import java.io.IOException;
import java.util.List;

/**
 * Stateless API security: bearer tokens only, no sessions or cookies, so CSRF protection is not
 * needed. Every request is authorised on the server (Doc 05 §7.2); method-level rules use
 * {@code @PreAuthorize} and service checks. The {@code /api/v1/dev/**} endpoints exist only when
 * {@code iccp.auth.mode=dev} (the controller is conditional).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain api(HttpSecurity http, PrincipalLoader loader, ObjectMapper mapper) throws Exception {
        AuthenticationEntryPoint entryPoint = (req, res, ex) -> writeAuthError(res, mapper, ex);
        http.csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h
                    .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                    .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/api/v1/public/**", "/api/v1/dev/**", "/api/v1/integrations/mms/directory",
                            "/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o
                    .jwt(j -> j.jwtAuthenticationConverter(jwt -> new IccpAuthentication(loader.load(jwt), jwt)))
                    .authenticationEntryPoint(entryPoint))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler((req, res, ex) -> write(res, mapper, 403, "ICCP-SEC-403", "You do not have permission to do this.")));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(IccpProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.auth().allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language", "If-Match", "X-Request-Id"));
        cfg.setExposedHeaders(List.of("X-Request-Id", "ETag", "Content-Disposition"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    private static void writeAuthError(HttpServletResponse res, ObjectMapper mapper, AuthenticationException ex) throws IOException {
        Throwable cause = ex;
        while (cause != null && !(cause instanceof IccpAuthenticationException)) {
            cause = cause.getCause();
        }
        if (cause instanceof IccpAuthenticationException e) {
            write(res, mapper, e.status(), e.code(), e.getMessage());
        } else {
            write(res, mapper, 401, "ICCP-SEC-401", "Sign in to continue.");
        }
    }

    private static void write(HttpServletResponse res, ObjectMapper mapper, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), new ApiError(status, code, message, List.of(), RequestIds.current()));
    }
}
