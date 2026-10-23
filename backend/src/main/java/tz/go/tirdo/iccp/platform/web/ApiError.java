package tz.go.tirdo.iccp.platform.web;

import java.util.List;

/**
 * Error body returned by every API (Doc 03 §4.1, Doc 05 §12.3).
 *
 * @param code      stable code, e.g. ICCP-FIN-008
 * @param message   message in the caller's language
 * @param fieldErrors validation problems per field
 * @param requestId correlation id, also in logs and audit
 */
public record ApiError(int status, String code, String message, List<FieldError> fieldErrors, String requestId) {

    public record FieldError(String field, String message) {
    }
}
