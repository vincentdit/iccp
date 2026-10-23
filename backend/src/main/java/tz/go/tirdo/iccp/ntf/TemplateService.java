package tz.go.tirdo.iccp.ntf;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the latest PUBLISHED template for an event, channel and language (FR-NTF-03). Falls
 * back to English when no Kiswahili template exists. Placeholders are {@code {{name}}}; unknown
 * placeholders render as empty text. Templates are cached for five minutes.
 */
@Service
public class TemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");
    private static final Duration TTL = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TemplateService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Template(String subject, String body) {
    }

    public record Rendered(String subject, String body) {
    }

    private record Cached(Optional<Template> template, Instant at) {
    }

    public Optional<Rendered> render(String eventType, String channel, String language, Map<String, String> params) {
        Optional<Template> t = find(eventType, channel, language);
        if (t.isEmpty() && !"en".equals(language)) {
            t = find(eventType, channel, "en");
        }
        return t.map(tpl -> new Rendered(fill(tpl.subject(), params), fill(tpl.body(), params)));
    }

    public boolean exists(String eventType, String channel) {
        return find(eventType, channel, "en").isPresent();
    }

    private Optional<Template> find(String eventType, String channel, String language) {
        String key = eventType + "|" + channel + "|" + language;
        Cached c = cache.get(key);
        if (c != null && c.at().plus(TTL).isAfter(Instant.now())) {
            return c.template();
        }
        Optional<Template> t = jdbc.sql("""
                SELECT subject, body FROM ntf.template
                 WHERE event_type = :e AND channel = :c AND language = :l AND status = 'PUBLISHED'
                 ORDER BY tpl_version DESC LIMIT 1
                """).param("e", eventType).param("c", channel).param("l", language).query(Template.class).optional();
        cache.put(key, new Cached(t, Instant.now()));
        return t;
    }

    static String fill(String text, Map<String, String> params) {
        if (text == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = params == null ? null : params.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(out);
        return out.toString();
    }
}
