package tz.go.tirdo.iccp.platform.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.iccp.platform.config.IccpProperties;

import java.util.List;

/** Unauthenticated settings the web app needs before sign-in. */
@RestController
@RequestMapping("/api/v1/public")
public class PublicConfigController {

    private final IccpProperties props;

    public PublicConfigController(IccpProperties props) {
        this.props = props;
    }

    public record PublicConfig(String authMode, String issuer, String clientId, String version, List<String> languages,
                               String timeZone, String mmsUrl, boolean mmsEnabled, boolean internalMeetingsEnabled) {
    }

    @GetMapping("/config")
    public PublicConfig config() {
        return new PublicConfig(props.auth().isDev() ? "dev" : "oidc", props.auth().issuer(), props.auth().clientId(),
                props.app().version(), List.of("en", "sw"), props.notifications().timeZone(),
                props.integrations().mmsUrl(), props.integrations().mmsEnabled(), props.integrations().internalMeetingsEnabled());
    }
}
