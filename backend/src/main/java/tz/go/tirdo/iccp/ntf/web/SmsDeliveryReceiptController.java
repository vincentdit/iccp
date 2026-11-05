package tz.go.tirdo.iccp.ntf.web;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tz.go.tirdo.iccp.platform.config.IccpProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Authenticated callback used by the approved SMS provider bridge. */
@RestController
@RequestMapping("/api/v1/integrations/sms")
public class SmsDeliveryReceiptController {
    private final JdbcClient jdbc;
    private final IccpProperties props;

    public SmsDeliveryReceiptController(JdbcClient jdbc, IccpProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public record Receipt(String providerReference, String status, String error) {}
    record Attempt(long notificationId, int attemptNo) {}

    @PostMapping("/delivery-receipt")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void receipt(@RequestHeader("X-ICCP-Receipt-Secret") String secret, @RequestBody Receipt body) {
        String configured = props.notifications().smsReceiptSecret();
        if (configured == null || configured.isBlank() || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (body.providerReference() == null || body.providerReference().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "providerReference is required");
        }
        if (!"DELIVERED".equalsIgnoreCase(body.status()) && !"FAILED".equalsIgnoreCase(body.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be DELIVERED or FAILED");
        }
        String status = body.status().toUpperCase();
        Attempt attempt = jdbc.sql("""
                SELECT notification_id, attempt_no FROM ntf.delivery_attempt
                 WHERE channel = 'SMS' AND provider_ref = :ref ORDER BY id DESC LIMIT 1
                """).param("ref", body.providerReference()).query(Attempt.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        int inserted = jdbc.sql("""
                INSERT INTO ntf.delivery_receipt (provider_ref, status, error) VALUES (:ref, :status, :error)
                ON CONFLICT (provider_ref, status) DO NOTHING
                """).param("ref", body.providerReference()).param("status", status).param("error", body.error()).update();
        if (inserted == 0) return;
        jdbc.sql("""
                INSERT INTO ntf.delivery_attempt (notification_id, channel, attempt_no, status, provider_ref, error)
                VALUES (:notification, 'SMS', :attempt, :status, :ref, :error)
                """).param("notification", attempt.notificationId()).param("attempt", attempt.attemptNo())
                .param("status", status).param("ref", body.providerReference()).param("error", body.error()).update();
    }
}
