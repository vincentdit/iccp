package tz.go.tirdo.iccp.ntf.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tz.go.tirdo.iccp.platform.config.IccpProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * SMS gateway adapter (I-04). The HTTP mode targets a small provider bridge contract so a
 * government-approved gateway can be connected without coupling ICCP to a vendor-specific API.
 * Messages never contain amounts or confidential content (NTF-08).
 */
@Component
public class SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(SmsGateway.class);
    private final IccpProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public SmsGateway(IccpProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.notifications().smsConnectTimeoutMs()))
                .build();
    }

    public String send(String msisdn, String text, String idempotencyKey) throws Exception {
        if (!"http".equalsIgnoreCase(props.notifications().smsProvider())) {
            log.info("[sms:log] to={} key={}", mask(msisdn), idempotencyKey);
            return "log-" + idempotencyKey;
        }
        validateConfiguration();
        String body = mapper.writeValueAsString(Map.of(
                "to", msisdn,
                "message", text,
                "senderId", props.notifications().smsSenderId(),
                "clientReference", idempotencyKey));
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.notifications().smsUrl()))
                .timeout(Duration.ofMillis(props.notifications().smsReadTimeoutMs()))
                .header("Authorization", "Bearer " + props.notifications().smsApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("SMS gateway returned HTTP " + response.statusCode());
        }
        String reference = providerReference(response.body());
        log.info("[sms:http] accepted to={} key={} ref={}", mask(msisdn), idempotencyKey, reference);
        return reference == null || reference.isBlank() ? idempotencyKey : reference;
    }

    private void validateConfiguration() {
        if (props.notifications().smsUrl() == null || props.notifications().smsUrl().isBlank()) {
            throw new IllegalStateException("ICCP_SMS_URL is required when ICCP_SMS_PROVIDER=http");
        }
        if (props.notifications().smsApiKey() == null || props.notifications().smsApiKey().isBlank()) {
            throw new IllegalStateException("ICCP_SMS_API_KEY is required when ICCP_SMS_PROVIDER=http");
        }
    }

    private String providerReference(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode node = mapper.readTree(body);
            for (String field : new String[]{"messageId", "reference", "id"}) {
                if (node.hasNonNull(field)) return node.get(field).asText();
            }
        } catch (Exception ignored) {
            // A successful non-JSON response still counts; the stable client reference is retained.
        }
        return null;
    }

    private static String mask(String msisdn) {
        return msisdn == null || msisdn.length() < 4 ? "***" : "***" + msisdn.substring(msisdn.length() - 3);
    }
}
