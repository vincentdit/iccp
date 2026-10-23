package tz.go.tirdo.iccp.ntf.channel;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import tz.go.tirdo.iccp.platform.config.IccpProperties;

import java.util.UUID;

/**
 * Sends notification email through GMS or the TIRDO mail relay (I-03). When
 * {@code iccp.notifications.email-enabled=false} (development), messages are logged instead.
 */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final ObjectProvider<JavaMailSender> mail;
    private final IccpProperties props;

    public EmailSender(ObjectProvider<JavaMailSender> mail, IccpProperties props) {
        this.mail = mail;
        this.props = props;
    }

    /** @return provider reference for the delivery record */
    public String send(String to, String subject, String body) throws Exception {
        JavaMailSender sender = mail.getIfAvailable();
        if (!props.notifications().emailEnabled() || sender == null) {
            log.info("[email:log] to={} subject=\"{}\"", mask(to), subject);
            return "log-" + UUID.randomUUID();
        }
        MimeMessage msg = sender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, false, "UTF-8");
        h.setFrom(new InternetAddress(props.notifications().emailFrom()));
        h.setTo(to);
        h.setSubject(subject);
        h.setText(body + "\n\n— TIRDO ICCP. This is an automated message; please do not reply.", false);
        sender.send(msg);
        return msg.getMessageID();
    }

    static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
