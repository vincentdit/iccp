package tz.go.tirdo.iccp.platform.security;

import org.springframework.security.core.AuthenticationException;

/** A valid token whose holder cannot use ICCP (no account, disabled, or left TIRDO). */
public class IccpAuthenticationException extends AuthenticationException {

    private final int status;
    private final String code;

    public IccpAuthenticationException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
