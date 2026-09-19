package qa.odexa.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import qa.odexa.config.Actor;
import qa.odexa.config.Credentials;

/** A per-actor synchronized cache; the lock includes grants to prevent refresh stampedes. */
public final class TokenProvider {
  private final AuthClient client;
  private final Credentials credentials;
  private final Clock clock;
  private final Actor actor;
  private AuthClient.Token token;
  private Instant refreshAt;
  private boolean invalidated;
  private AuthException terminalFailure;

  public TokenProvider(AuthClient client, Credentials credentials, Clock clock) {
    this(client, credentials, clock, null);
  }

  public TokenProvider(AuthClient client, Credentials credentials, Clock clock, Actor actor) {
    this.client = Objects.requireNonNull(client);
    this.credentials = Objects.requireNonNull(credentials);
    this.clock = Objects.requireNonNull(clock);
    this.actor = actor;
  }

  public synchronized String accessToken() {
    if (terminalFailure != null) {
      throw new AuthException(actor, terminalFailure.status(), terminalFailure.classification());
    }
    Instant requestedAt = clock.instant();
    if (token != null && !invalidated && requestedAt.isBefore(refreshAt)) {
      return token.accessToken;
    }
    try {
      AuthClient.Token replacement;
      if (token != null && token.refreshToken != null) {
        try {
          replacement = client.refresh(token.refreshToken, actor);
        } catch (AuthException failure) {
          if (failure.classification() != AuthException.Classification.INVALID_GRANT) {
            throw failure;
          }
          // Exactly one password grant for an expired/revoked refresh token.
          replacement = client.authenticate(credentials, actor);
        }
      } else {
        replacement = client.authenticate(credentials, actor);
      }
      token = replacement;
      Duration lifetime = Duration.ofSeconds(token.expiresIn);
      Duration skew = lifetime.dividedBy(10);
      if (skew.compareTo(Duration.ofSeconds(30)) > 0) {
        skew = Duration.ofSeconds(30);
      }
      refreshAt = requestedAt.plus(lifetime).minus(skew);
      invalidated = false;
      return token.accessToken;
    } catch (AuthException failure) {
      // Configuration/password failures cannot be cured by a parallel retry storm.
      if (failure.classification() == AuthException.Classification.INVALID_CLIENT
          || failure.classification() == AuthException.Classification.INVALID_GRANT) {
        terminalFailure = failure;
        token = null;
      }
      throw failure;
    }
  }

  public synchronized void invalidate(String previouslyUsedToken) {
    if (token != null && token.accessToken.equals(previouslyUsedToken)) {
      invalidated = true;
    }
  }

  @Override
  public String toString() {
    return "TokenProvider[actor=" + (actor == null ? "UNSPECIFIED" : actor.name()) + "]";
  }
}
