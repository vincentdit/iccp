package tz.go.tirdo.iccp.platform.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns exceptions into {@link ApiError} bodies. Database rule violations raised by the Doc 06
 * triggers carry codes such as {@code ICCP-FIN-008}; they are returned as clear 409/403 errors
 * instead of raw database messages.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Pattern ICCP_CODE = Pattern.compile("(ICCP-[A-Z]+-\\d{3})");

    private final MessageSource messages;

    public GlobalExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e) {
        return body(e.status(), e.code(), text(e.getMessage(), e.args()), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        List<ApiError.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), messages.getMessage(f, LocaleContextHolder.getLocale())))
                .toList();
        return body(400, "ICCP-VAL-001", text("error.validation"), fields);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return body(405, "ICCP-405", text("error.method"), List.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ApiError> unreadable(Exception e) {
        return body(400, "ICCP-VAL-002", text("error.badRequest"), List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return body(413, "ICCP-FILE-001", text("error.fileTooLarge"), List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException e) {
        return body(403, "ICCP-SEC-403", text("error.forbidden"), List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException e) {
        return body(404, "ICCP-404", text("error.notFound"), List.of());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> duplicate(DuplicateKeyException e) {
        return body(409, "ICCP-DB-23505", text("error.duplicate"), List.of());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> database(DataAccessException e) {
        SQLException sql = findSql(e);
        String state = sql == null ? null : sql.getSQLState();
        String msg = sql == null ? "" : String.valueOf(sql.getMessage());
        Matcher m = ICCP_CODE.matcher(msg);
        if (m.find()) {
            String code = m.group(1);
            int status = "42501".equals(state) ? 403 : 409;
            return body(status, code, text("db." + code, ruleText(msg)), List.of());
        }
        if ("40001".equals(state)) {
            return body(409, "ICCP-CORE-001", text("error.conflict"), List.of());
        }
        if ("23514".equals(state) || "23503".equals(state) || "23502".equals(state)) {
            return body(409, "ICCP-DB-" + state, text("error.rule"), List.of());
        }
        if ("42501".equals(state)) {
            return body(403, "ICCP-SEC-403", text("error.forbidden"), List.of());
        }
        log.error("Database error", e);
        return body(500, "ICCP-500", text("error.server"), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception e) {
        log.error("Unhandled error", e);
        return body(500, "ICCP-500", text("error.server"), List.of());
    }

    private String ruleText(String sqlMessage) {
        // "ERROR: ICCP-FIN-008: allocations (...) would exceed ..." -> "allocations (...) would exceed ..."
        String first = sqlMessage.split("\\R")[0];
        int i = first.indexOf("ICCP-");
        String tail = i >= 0 ? first.substring(i) : first;
        int colon = tail.indexOf(": ");
        return colon >= 0 ? tail.substring(colon + 2) : tail;
    }

    private String text(String keyOrText, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        String fallback = args.length > 0 && args[0] instanceof String s && keyOrText.startsWith("db.") ? s : keyOrText;
        return messages.getMessage(keyOrText, args, fallback, locale);
    }

    private static SQLException findSql(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException s) {
                return s;
            }
            t = t.getCause();
        }
        return null;
    }

    private static ResponseEntity<ApiError> body(int status, String code, String message, List<ApiError.FieldError> fields) {
        return ResponseEntity.status(status).body(new ApiError(status, code, message, fields, RequestIds.current()));
    }
}
