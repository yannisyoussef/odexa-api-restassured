package qa.odexa.auth;

import qa.odexa.config.Actor;

/** Intentionally contains no original exception, request, token, or identity-provider response. */
public final class AuthException extends RuntimeException {
  public enum Classification {
    INVALID_GRANT,
    INVALID_CLIENT,
    HTTP_FAILURE,
    INVALID_RESPONSE,
    TRANSPORT,
    INTERRUPTED
  }

  private final int status;
  private final Classification classification;

  AuthException(Actor actor, int status, Classification classification) {
    super(
        "Authentication failed: actor="
            + (actor == null ? "UNSPECIFIED" : actor.name())
            + ", status="
            + status
            + ", classification="
            + classification,
        null,
        false,
        true);
    this.status = status;
    this.classification = classification;
  }

  public int status() {
    return status;
  }

  public Classification classification() {
    return classification;
  }
}
