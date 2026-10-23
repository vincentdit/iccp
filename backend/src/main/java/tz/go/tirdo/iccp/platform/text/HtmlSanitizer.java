package tz.go.tirdo.iccp.platform.text;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict allow-list sanitiser for announcement bodies (FR-COM-01 "rich text").
 *
 * <p>Only a few formatting tags survive, without attributes, except {@code href} on links, which
 * must be https, http or mailto. Everything else is escaped, so the stored HTML is safe to render.
 * Comments, scripts, styles and event handlers cannot survive because no attributes other than a
 * checked {@code href} are ever emitted.
 */
public final class HtmlSanitizer {

    private static final Set<String> TAGS = Set.of("p", "br", "strong", "b", "em", "i", "u", "ul", "ol", "li", "h3", "h4", "blockquote", "a");
    private static final Set<String> VOID = Set.of("br");
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^<>]*)>");
    private static final Pattern HREF = Pattern.compile("(?i)\\bhref\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern SAFE_URL = Pattern.compile("(?i)^(https?://|mailto:)[^\\s\"'<>`]+$");
    private static final Pattern DROP_BLOCK = Pattern.compile("(?is)<(script|style|iframe|object|embed|svg|math|template)\\b.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");

    private HtmlSanitizer() {
    }

    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String input = COMMENT.matcher(html).replaceAll("");
        input = DROP_BLOCK.matcher(input).replaceAll("");
        StringBuilder out = new StringBuilder(input.length());
        Matcher m = TAG.matcher(input);
        int last = 0;
        while (m.find()) {
            out.append(escapeText(input.substring(last, m.start())));
            String closing = m.group(1);
            String name = m.group(2).toLowerCase(Locale.ROOT);
            if (TAGS.contains(name)) {
                if (!closing.isEmpty()) {
                    if (!VOID.contains(name)) {
                        out.append("</").append(name).append('>');
                    }
                } else if ("a".equals(name)) {
                    String href = href(m.group(3));
                    if (href != null) {
                        out.append("<a href=\"").append(escapeAttr(href)).append("\" rel=\"noopener noreferrer\" target=\"_blank\">");
                    } else {
                        out.append("<a>");
                    }
                } else {
                    out.append('<').append(name).append('>');
                }
            }
            last = m.end();
        }
        out.append(escapeText(input.substring(last)));
        return out.toString();
    }

    /** Plain text for search, notifications and previews. */
    public static String toPlainText(String html) {
        if (html == null) {
            return "";
        }
        // Block tags separate words; inline tags (strong, em, a) must not add spaces before punctuation.
        String text = html.replaceAll("(?i)</?(p|br|li|ul|ol|h3|h4|blockquote)\\b[^>]*>", " ");
        text = TAG.matcher(text).replaceAll("");
        text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String href(String attrs) {
        Matcher h = HREF.matcher(attrs);
        if (!h.find()) {
            return null;
        }
        String url = h.group(2) != null ? h.group(2) : h.group(3) != null ? h.group(3) : h.group(4);
        url = url.trim();
        return SAFE_URL.matcher(url).matches() ? url : null;
    }

    private static String escapeText(String s) {
        // Keep existing entities such as &amp; intact; escape bare < and >.
        return s.replace("<", "&lt;").replace(">", "&gt;").replaceAll("&(?![a-zA-Z]{2,8};|#\\d{1,6};)", "&amp;");
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
