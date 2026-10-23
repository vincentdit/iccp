package tz.go.tirdo.iccp.sec.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.dir.api.StaffRef;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.sec.PrincipalService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * DEVELOPMENT ONLY — exists only when {@code iccp.auth.mode=dev}, which is refused under the
 * {@code prod} profile (see JwtConfig). Lets developers and UAT facilitators sign in as demo staff
 * without Keycloak. Production sign-in always goes through Keycloak with MFA.
 */
@RestController
@RequestMapping("/api/v1/dev")
@ConditionalOnProperty(name = "iccp.auth.mode", havingValue = "dev")
public class DevAuthController {

    private final JwtEncoder encoder;
    private final DirectoryApi directory;
    private final PrincipalService principals;
    private final JdbcClient jdbc;

    public DevAuthController(JwtEncoder encoder, DirectoryApi directory, PrincipalService principals, JdbcClient jdbc) {
        this.encoder = encoder;
        this.directory = directory;
        this.principals = principals;
        this.jdbc = jdbc;
    }

    public record DevUser(String employeeNo, UUID publicId, String displayName, String positionTitle, String unitName, List<String> roles) {
    }

    record Row(long id, String employeeNo) {
    }

    /** Staff to offer on the dev sign-in screen: people with assigned roles first, then a few others. */
    @GetMapping("/users")
    public List<DevUser> users() {
        List<Row> rows = jdbc.sql("""
                SELECT s.id, s.employee_no
                  FROM dir.staff_member s
                  LEFT JOIN sec.user_account a ON a.staff_id = s.id
                  LEFT JOIN sec.role_assignment ra ON ra.user_account_id = a.id
                 WHERE s.employment_status = 'ACTIVE'
                 GROUP BY s.id, s.employee_no
                 ORDER BY count(ra.id) DESC, s.employee_no
                 LIMIT 24
                """).query(Row.class).list();
        return rows.stream().map(r -> {
            StaffRef s = directory.findById(r.id()).orElseThrow();
            return new DevUser(s.employeeNo(), s.publicId(), s.displayName(), s.positionTitle(), s.orgUnitName(),
                    List.copyOf(principals.rolesOf(s.id())));
        }).toList();
    }

    public record LoginRequest(String employeeNo) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    @PostMapping("/login")
    public TokenResponse login(@RequestBody LoginRequest req) {
        StaffRef s = directory.findByEmployeeNo(req.employeeNo() == null ? "" : req.employeeNo().trim())
                .filter(StaffRef::isActive).orElseThrow(ApiException::notFound);
        Instant now = Instant.now();
        long ttl = 8 * 3600;
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("iccp-dev")
                .subject("dev:" + s.employeeNo())
                .issuedAt(now)
                .expiresAt(now.plus(ttl, ChronoUnit.SECONDS))
                .claim("email", s.email())
                .claim("employee_no", s.employeeNo())
                .claim("name", s.displayName())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", ttl);
    }
}
