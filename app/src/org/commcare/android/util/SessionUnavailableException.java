package org.commcare.android.util;

/**
 * Signals the expiration of the session created on login that controls
 * liveness of user database and key pool.
 *
 * @author ctsims
 */
public class SessionUnavailableException extends Exception {
    public SessionUnavailableException() {
        super();
    }

    public SessionUnavailableException(String message) {
        super(message);
    }

    public SessionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public SessionUnavailableException(Throwable cause) {
        super(cause);
    }
}
