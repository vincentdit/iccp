package tz.go.tirdo.iccp.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tz.go.tirdo.iccp.platform.config.IccpProperties;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Exchanges the browser's authorization code over the server-side Docker
 * network. This avoids extension, CORS and browser privacy-mode interference
 * while preserving Authorization Code + PKCE.
 */
@RestController
@RequestMapping("/api/v1/public/oidc")
@ConditionalOnProperty(name = "iccp.auth.mode", havingValue = "oidc")
public class OidcTokenController {
    private final IccpProperties props;
    private final RestClient client;

    public OidcTokenController(IccpProperties props) {
        this.props = props;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(http))
                .build();
    }

    public record TokenRequest(@NotBlank String code, @NotBlank String codeVerifier,
                               @NotBlank String redirectUri) {
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode exchange(@Valid @RequestBody TokenRequest request) {
        String expected = props.app().publicUrl().replaceAll("/+$", "") + "/auth/callback";
        if (!expected.equals(request.redirectUri())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sign-in callback.");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", props.auth().clientId());
        form.add("code", request.code());
        form.add("redirect_uri", request.redirectUri());
        form.add("code_verifier", request.codeVerifier());
        try {
            return client.post()
                    .uri(tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The identity provider rejected the sign-in response.");
        }
    }

    private String tokenEndpoint() {
        String jwks = props.auth().jwkSetUri();
        return jwks == null || jwks.isBlank()
                ? props.auth().issuer().replaceAll("/+$", "") + "/protocol/openid-connect/token"
                : jwks.replaceFirst("/certs$", "/token");
    }
}
