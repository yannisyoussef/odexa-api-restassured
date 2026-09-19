package qa.odexa.config;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Immutable target configuration, loaded explicitly by API-test setup, never at discovery time. */
public final class TargetConfig {
  public enum Mode {
    COMPOSE,
    TESTCONTAINERS,
    REMOTE
  }

  private final Mode mode;
  private final URI baseUri;
  private final URI tokenUri;
  private final String clientId;
  private final String clientSecret;
  private final Map<Actor, Credentials> credentials;
  private final UUID productId;
  private final UUID otherProductId;
  private final UUID tenantA;
  private final UUID tenantB;
  private final String version;
  private final Duration requestTimeout;
  private final Duration pollTimeout;
  private final Duration pollInterval;
  private final boolean allowMutation;
  private final boolean exclusiveFixtures;
  private final boolean verbose;

  private TargetConfig(Properties props, Map<String, String> env) {
    if (props.containsKey("odexa.env") || env.containsKey("ODEXA_ENV")) {
      throw invalid("ODEXA_ENV was replaced by ODEXA_TARGET_MODE");
    }
    String environment = value(props, env, "targetMode", "ODEXA_TARGET_MODE", "compose");
    try {
      mode = Mode.valueOf(environment.toUpperCase(Locale.ROOT));
    } catch (RuntimeException ignored) {
      throw invalid("ODEXA_TARGET_MODE");
    }
    boolean local = mode != Mode.REMOTE;
    baseUri =
        endpoint(
            value(props, env, "baseUrl", "ODEXA_BASE_URL", local ? "http://localhost:8080" : null),
            "ODEXA_BASE_URL",
            local,
            true);
    tokenUri =
        endpoint(
            value(
                props,
                env,
                "tokenUrl",
                "ODEXA_TOKEN_URL",
                local ? "http://localhost:8180/realms/odexa/protocol/openid-connect/token" : null),
            "ODEXA_TOKEN_URL",
            local,
            false);
    clientId =
        required(
            value(props, env, "clientId", "ODEXA_CLIENT_ID", local ? "odexa-cli" : null),
            "ODEXA_CLIENT_ID");
    // Secrets and actor credentials are environment-only, including in tests of this loader.
    clientSecret = env.get("ODEXA_CLIENT_SECRET");
    productId =
        uuid(
            value(
                props,
                env,
                "productId",
                "ODEXA_PRODUCT_ID",
                local ? "11111111-1111-4111-8111-111111111111" : null),
            "ODEXA_PRODUCT_ID");
    otherProductId =
        uuid(
            value(
                props,
                env,
                "otherProductId",
                "ODEXA_OTHER_PRODUCT_ID",
                local ? "22222222-2222-4222-8222-222222222222" : null),
            "ODEXA_OTHER_PRODUCT_ID");
    tenantA =
        uuid(
            value(
                props,
                env,
                "tenantA",
                "ODEXA_TENANT_A",
                local ? "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" : null),
            "ODEXA_TENANT_A");
    tenantB =
        uuid(
            value(
                props,
                env,
                "tenantB",
                "ODEXA_TENANT_B",
                local ? "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" : null),
            "ODEXA_TENANT_B");
    version = required(value(props, env, "version", "ODEXA_VERSION", "v0.1.0"), "ODEXA_VERSION");
    if (!version.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) {
      throw invalid("ODEXA_VERSION");
    }
    requestTimeout =
        Duration.ofSeconds(
            number(props, env, "timeoutSeconds", "ODEXA_TIMEOUT_SECONDS", 10, 1, 30));
    pollTimeout =
        Duration.ofSeconds(
            number(props, env, "pollTimeoutSeconds", "ODEXA_POLL_TIMEOUT_SECONDS", 90, 1, 180));
    pollInterval =
        Duration.ofMillis(
            number(props, env, "pollIntervalMillis", "ODEXA_POLL_INTERVAL_MILLIS", 250, 50, 1000));
    allowMutation = flag(props, env, "allowMutation", "ODEXA_ALLOW_MUTATION");
    exclusiveFixtures = flag(props, env, "exclusiveFixtures", "ODEXA_EXCLUSIVE_FIXTURES");
    verbose = flag(props, env, "verbose", "ODEXA_VERBOSE");
    if (allowMutation && !exclusiveFixtures) {
      throw invalid("ODEXA_ALLOW_MUTATION requires ODEXA_EXCLUSIVE_FIXTURES");
    }
    EnumMap<Actor, Credentials> actors = new EnumMap<>(Actor.class);
    for (Actor actor : Actor.values()) {
      String prefix = "ODEXA_" + actor.name();
      String defaultUsername =
          switch (actor) {
            case CUSTOMER_A -> "customer-a";
            case CUSTOMER_B -> "customer-b";
            case OTHER_CUSTOMER_A -> "customer-other-a";
            case MERCHANT_A -> "merchant-a";
          };
      String username =
          required(
              env.getOrDefault(prefix + "_USERNAME", local ? defaultUsername : null),
              prefix + "_USERNAME");
      String password = env.get(prefix + "_PASSWORD");
      if ((password == null || password.isBlank()) && local) {
        password = env.get("ODEXA_FIXTURE_PASSWORD");
      }
      actors.put(actor, new Credentials(username, required(password, prefix + "_PASSWORD")));
    }
    credentials = Map.copyOf(actors);
  }

  public static TargetConfig fromEnvironment() {
    return load(System.getProperties(), System.getenv());
  }

  public static TargetConfig load(Properties props, Map<String, String> env) {
    return new TargetConfig(props, env);
  }

  private static String value(
      Properties props, Map<String, String> env, String property, String key, String fallback) {
    return props.getProperty("odexa." + property, env.getOrDefault(key, fallback));
  }

  private static String required(String value, String key) {
    if (value == null || value.isBlank()) {
      throw invalid(key);
    }
    return value;
  }

  private static IllegalArgumentException invalid(String key) {
    return new IllegalArgumentException("Missing or invalid configuration: " + key);
  }

  private static UUID uuid(String value, String key) {
    required(value, key);
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) {
        throw invalid(key);
      }
      return id;
    } catch (RuntimeException ignored) {
      throw invalid(key);
    }
  }

  private static URI endpoint(String value, String key, boolean local, boolean base) {
    required(value, key);
    try {
      URI uri = URI.create(value);
      String host = uri.getHost();
      boolean https = "https".equalsIgnoreCase(uri.getScheme());
      boolean loopback =
          host != null
              && (host.equalsIgnoreCase("localhost")
                  || host.equals("[::1]")
                  || host.equals("::1")
                  || host.equals("[0:0:0:0:0:0:0:1]")
                  || host.matches("127(?:\\.(?:[0-9]{1,3})){3}") && validIpv4(host));
      if (host == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535
          || (!https && !(local && loopback && "http".equalsIgnoreCase(uri.getScheme())))
          || (base && !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))) {
        throw invalid(key);
      }
      return uri;
    } catch (RuntimeException ignored) {
      throw invalid(key);
    }
  }

  private static boolean validIpv4(String host) {
    for (String part : host.split("\\.")) {
      if (Integer.parseInt(part) > 255 || (part.length() > 1 && part.startsWith("0"))) {
        return false;
      }
    }
    return true;
  }

  private static long number(
      Properties props,
      Map<String, String> env,
      String property,
      String key,
      long fallback,
      long minimum,
      long maximum) {
    try {
      long result = Long.parseLong(value(props, env, property, key, Long.toString(fallback)));
      if (result < minimum || result > maximum) {
        throw invalid(key);
      }
      return result;
    } catch (RuntimeException ignored) {
      throw invalid(key);
    }
  }

  private static boolean flag(
      Properties props, Map<String, String> env, String property, String key) {
    String result = value(props, env, property, key, "false");
    if (!"true".equalsIgnoreCase(result) && !"false".equalsIgnoreCase(result)) {
      throw invalid(key);
    }
    return Boolean.parseBoolean(result);
  }

  public Mode mode() {
    return mode;
  }

  public URI baseUri() {
    return baseUri;
  }

  public URI tokenUri() {
    return tokenUri;
  }

  public String clientId() {
    return clientId;
  }

  public String clientSecret() {
    return clientSecret;
  }

  public Credentials credentials(Actor actor) {
    return credentials.get(actor);
  }

  public UUID productId() {
    return productId;
  }

  public UUID otherProductId() {
    return otherProductId;
  }

  public UUID tenantA() {
    return tenantA;
  }

  public UUID tenantB() {
    return tenantB;
  }

  public String version() {
    return version;
  }

  public Duration requestTimeout() {
    return requestTimeout;
  }

  public Duration pollTimeout() {
    return pollTimeout;
  }

  public Duration pollInterval() {
    return pollInterval;
  }

  public boolean allowMutation() {
    return allowMutation;
  }

  public boolean exclusiveFixtures() {
    return exclusiveFixtures;
  }

  public boolean verbose() {
    return verbose;
  }

  @Override
  public String toString() {
    return "TargetConfig[mode="
        + mode
        + ", allowMutation="
        + allowMutation
        + ", exclusiveFixtures="
        + exclusiveFixtures
        + "]";
  }
}
