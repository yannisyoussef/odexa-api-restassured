package qa.odexa.auth;

import java.time.Clock;
import java.util.Objects;
import qa.odexa.config.Actor;
import qa.odexa.config.TargetConfig;

public final class AuthenticatedSession {
  private final Actor actor;
  private final TokenProvider tokens;

  public AuthenticatedSession(Actor actor, TokenProvider tokens) {
    this.actor = Objects.requireNonNull(actor);
    this.tokens = Objects.requireNonNull(tokens);
  }

  public static AuthenticatedSession create(Actor actor, TargetConfig config) {
    return new AuthenticatedSession(
        actor,
        new TokenProvider(
            new AuthClient(config), config.credentials(actor), Clock.systemUTC(), actor));
  }

  public Actor actor() {
    return actor;
  }

  public String accessToken() {
    return tokens.accessToken();
  }

  public void invalidate(String previouslyUsedToken) {
    tokens.invalidate(previouslyUsedToken);
  }

  @Override
  public String toString() {
    return "AuthenticatedSession[actor=" + actor.name() + "]";
  }
}
