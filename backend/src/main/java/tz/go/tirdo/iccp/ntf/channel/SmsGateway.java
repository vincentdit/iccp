package tz.go.tirdo.iccp.ntf.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SMS gateway adapter (I-04). TIRDO has not yet contracted a gateway, so R1 ships a logging
 * implementation; replace {@link #send} with the approved provider's HTTPS API when it is chosen
 * (Doc 09 open items). Messages never contain amounts or confidential content (NTF-08).
 */
@Component
public class SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(SmsGateway.class);

    public String send(String msisdn, String text) {
        String masked = msisdn == null || msisdn.length() < 4 ? "***" : "***" + msisdn.substring(msisdn.length() - 3);
        log.info("[sms:log] to={} text=\"{}\"", masked, text);
        return "log-" + UUID.randomUUID();
    }
}
