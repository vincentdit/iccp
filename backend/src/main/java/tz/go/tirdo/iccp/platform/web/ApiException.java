package tz.go.tirdo.iccp.platform.web;

/**
 * Business error with an HTTP status and a stable code. The message key is resolved in the
 * caller's language by {@link GlobalExceptionHandler}; {@code args} fill its placeholders.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Object[] args;

    public ApiException(int status, String code, String messageKeyOrText, Object... args) {
        super(messageKeyOrText);
        this.status = status;
        this.code = code;
        this.args = args;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object[] args() {
        return args;
    }

    /** 404 is also used for records the caller may not see, so their existence is not revealed (Doc 03 §1.2). */
    public static ApiException notFound() {
        return new ApiException(404, "ICCP-404", "error.notFound");
    }

    public static ApiException forbidden() {
        return new ApiException(403, "ICCP-SEC-403", "error.forbidden");
    }

    public static ApiException unauthorized() {
        return new ApiException(401, "ICCP-SEC-401", "error.unauthorized");
    }

    public static ApiException badRequest(String code, String key, Object... args) {
        return new ApiException(400, code, key, args);
    }

    public static ApiException conflict(String code, String key, Object... args) {
        return new ApiException(409, code, key, args);
    }
}
