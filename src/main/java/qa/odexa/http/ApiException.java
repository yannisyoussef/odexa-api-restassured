package qa.odexa.http;

/**
 * Sanitized failure with no original cause or suppressed exceptions that could contain payloads.
 */
public final class ApiException extends RuntimeException {
  ApiException(String safeMessage) {
    super(safeMessage, null, false, true);
  }
}
