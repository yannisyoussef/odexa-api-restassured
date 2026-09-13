package qa.odexa.auth;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import qa.odexa.config.Actor;
import qa.odexa.config.TargetConfig;

/**
 * One session per identity provider identity, shared by every test class in the suite.
 *
 * <p>An identity provider may treat overlapping password grants for a single identity as a
 * credential attack rather than as legitimate parallel work. Odexa v0.1.0 enables Keycloak
 * brute-force protection, whose concurrent-login check temporarily disables the account and answers
 * {@code invalid_grant}; independently authenticating test classes therefore denied each other. A
 * real client authenticates an identity once and reuses the token, so the suite does the same:
 * {@link TokenProvider} serializes its own grants, so sharing one provider per identity keeps at
 * most one authentication in flight for that identity.
 *
 * <p>The scope is the identity as the provider sees it: token endpoint, OAuth client and actor.
 * Distinct actors and distinct targets stay independent, no token crosses actors, and no Rest
 * Assured or HTTP state is shared. Parallel execution is unaffected.
 */
public final class ActorSessions {
  private static final ActorSessions SUITE = new ActorSessions();

  private final ConcurrentMap<Scope, AuthenticatedSession> sessions = new ConcurrentHashMap<>();

  /** Isolated registries are for unit tests; API tests must share the suite registry. */
  public ActorSessions() {}

  /** The registry shared by every API test class in this JVM. */
  public static ActorSessions suite() {
    return SUITE;
  }

  /** Returns the session for this target and actor, authenticating lazily and at most once. */
  public AuthenticatedSession forActor(TargetConfig config, Actor actor) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(actor, "actor");
    Scope scope = new Scope(config.tokenUri().normalize(), config.clientId(), actor);
    return sessions.computeIfAbsent(scope, ignored -> AuthenticatedSession.create(actor, config));
  }

  private record Scope(URI tokenUri, String clientId, Actor actor) {}

  @Override
  public String toString() {
    return "ActorSessions[identities=" + sessions.size() + "]";
  }
}
