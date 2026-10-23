package tz.go.tirdo.iccp.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Assigns each request a correlation id (from {@code X-Request-Id} when valid) and records the
 * client address, for logs, error bodies and audit events.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIds extends OncePerRequestFilter {

    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F-]{36}$");
    private static final ThreadLocal<String> CLIENT_IP = new ThreadLocal<>();

    public static String current() {
        return MDC.get("requestId");
    }

    public static String clientIp() {
        return CLIENT_IP.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String incoming = req.getHeader("X-Request-Id");
        String id = incoming != null && UUID_RE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put("requestId", id);
        // The protected reporting channel must never record client addresses (Doc 05 §7.4).
        CLIENT_IP.set(req.getRequestURI().startsWith("/api/v1/protected") ? null : req.getRemoteAddr());
        res.setHeader("X-Request-Id", id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("requestId");
            CLIENT_IP.remove();
        }
    }
}
