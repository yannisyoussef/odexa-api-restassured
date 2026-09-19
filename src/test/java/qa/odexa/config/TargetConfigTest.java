package qa.odexa.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TargetConfigTest {
  @Test
  void emptyLocalActorPlaceholderUsesTheExplicitFixtureFallback() {
    var env = new HashMap<String, String>();
    env.put("ODEXA_FIXTURE_PASSWORD", "unit-only-fixture");
    env.put("ODEXA_CUSTOMER_A_PASSWORD", "");
    var config = TargetConfig.load(new Properties(), env);
    assertTrue(config.credentials(Actor.CUSTOMER_A).password().equals("unit-only-fixture"));
  }

  private static final String SECRET = "synthetic-config-secret";

  @Test
  void localDefaultsAreSafeAndOnlyProvisionedActorsExist() {
    TargetConfig config = TargetConfig.load(new Properties(), local());
    assertEquals(TargetConfig.Mode.COMPOSE, config.mode());
    assertEquals("http://localhost:8080", config.baseUri().toString());
    assertEquals(
        "http://localhost:8180/realms/odexa/protocol/openid-connect/token",
        config.tokenUri().toString());
    assertEquals("odexa-cli", config.clientId());
    assertEquals("v0.1.0", config.version());
    assertEquals(Duration.ofSeconds(10), config.requestTimeout());
    assertEquals(Duration.ofSeconds(90), config.pollTimeout());
    assertEquals(Duration.ofMillis(250), config.pollInterval());
    assertFalse(config.allowMutation());
    assertFalse(config.exclusiveFixtures());
    assertFalse(config.verbose());
    assertEquals(4, Actor.values().length);
    for (Actor actor : Actor.values()) {
      assertEquals(actor == Actor.MERCHANT_A ? "MERCHANT_ADMIN" : "CUSTOMER", actor.role());
      assertTrue(config.credentials(actor).password().equals(SECRET));
    }
    assertEquals("customer-other-a", config.credentials(Actor.OTHER_CUSTOMER_A).username());
  }

  @Test
  void propertiesOverrideNonsecretEnvironmentOnly() {
    Map<String, String> env = local();
    env.put("ODEXA_TIMEOUT_SECONDS", "3");
    env.put("ODEXA_CUSTOMER_A_USERNAME", "synthetic-user");
    env.put("ODEXA_CLIENT_SECRET", SECRET);
    Properties props = new Properties();
    props.setProperty("odexa.timeoutSeconds", "7");
    props.setProperty("odexa.clientSecret", "must-not-win");
    props.setProperty("odexa.customerAUsername", "must-not-win");
    props.setProperty("odexa.customerAPassword", "must-not-win");
    TargetConfig config = TargetConfig.load(props, env);
    assertEquals(Duration.ofSeconds(7), config.requestTimeout());
    assertTrue(config.clientSecret().equals(SECRET));
    assertTrue(config.credentials(Actor.CUSTOMER_A).username().equals("synthetic-user"));
    assertTrue(config.credentials(Actor.CUSTOMER_A).password().equals(SECRET));
    assertFalse(config.toString().contains(SECRET));
    assertFalse(config.credentials(Actor.CUSTOMER_A).toString().contains(SECRET));
    assertFalse(config.credentials(Actor.CUSTOMER_A).toString().contains("synthetic-user"));
  }

  @Test
  void remoteRequiresEveryExplicitEndpointFixtureAndActor() {
    Map<String, String> env = remote();
    TargetConfig config = TargetConfig.load(new Properties(), env);
    assertEquals(TargetConfig.Mode.REMOTE, config.mode());
    assertFalse(config.allowMutation());
    for (String key : env.keySet()) {
      if (key.equals("ODEXA_TARGET_MODE")) {
        continue;
      }
      Map<String, String> missing = new HashMap<>(env);
      missing.remove(key);
      missing.put("ODEXA_FIXTURE_PASSWORD", SECRET);
      IllegalArgumentException failure =
          assertThrows(
              IllegalArgumentException.class, () -> TargetConfig.load(new Properties(), missing));
      assertTrue(failure.getMessage().contains(key));
      assertFalse(failure.getMessage().contains(SECRET));
      assertNull(failure.getCause());
    }
  }

  @Test
  void credentialsHaveNoDefaultAndJvmPasswordsAreIgnored() {
    Properties props = new Properties();
    props.setProperty("odexa.fixturePassword", SECRET);
    props.setProperty("odexa.customerAPassword", SECRET);
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> TargetConfig.load(props, Map.of()));
    assertTrue(failure.getMessage().contains("ODEXA_CUSTOMER_A_PASSWORD"));
    assertFalse(failure.getMessage().contains(SECRET));
  }

  @Test
  void endpointsRejectCredentialExfiltrationAndUnsafeUrisWithoutEchoing() {
    for (String url :
        new String[] {
          "http://example.test",
          "//example.test",
          "file:///tmp/token",
          "https://user:" + SECRET + "@example.test",
          "https://example.test?password=" + SECRET,
          "https://example.test#" + SECRET,
          "https://example.test:0",
          "https://example.test:99999",
          "http://localhost.evil.test",
          "http://127.999.0.1",
          "bad " + SECRET
        }) {
      for (String key : new String[] {"ODEXA_BASE_URL", "ODEXA_TOKEN_URL"}) {
        Map<String, String> env = local();
        env.put(key, url);
        IllegalArgumentException failure =
            assertThrows(
                IllegalArgumentException.class, () -> TargetConfig.load(new Properties(), env));
        assertEquals("Missing or invalid configuration: " + key, failure.getMessage());
        assertNull(failure.getCause());
      }
    }
    Map<String, String> env = remote();
    env.put("ODEXA_TOKEN_URL", "http://localhost:8180/token");
    assertThrows(IllegalArgumentException.class, () -> TargetConfig.load(new Properties(), env));
  }

  @Test
  void loopbackHttpAndExplicitHttpsArePermitted() {
    for (String url :
        new String[] {
          "http://127.0.0.1:1234",
          "http://[::1]:1234",
          "http://localhost:1234",
          "https://target.example.test"
        }) {
      Map<String, String> env = local();
      env.put("ODEXA_BASE_URL", url);
      assertEquals(url, TargetConfig.load(new Properties(), env).baseUri().toString());
    }
  }

  @Test
  void validatesTimeoutBoundsUuidsBooleansAndMutationGate() {
    Map<String, String[]> invalid =
        Map.of(
            "ODEXA_TIMEOUT_SECONDS", new String[] {"0", "31", SECRET},
            "ODEXA_POLL_TIMEOUT_SECONDS", new String[] {"0", "181"},
            "ODEXA_POLL_INTERVAL_MILLIS", new String[] {"49", "1001"},
            "ODEXA_PRODUCT_ID", new String[] {"1-1-1-1-1", SECRET},
            "ODEXA_ALLOW_MUTATION", new String[] {"true", SECRET},
            "ODEXA_VERBOSE", new String[] {SECRET},
            "ODEXA_TARGET_MODE", new String[] {SECRET});
    invalid.forEach(
        (key, values) -> {
          for (String value : values) {
            Map<String, String> env = local();
            env.put(key, value);
            IllegalArgumentException failure =
                assertThrows(
                    IllegalArgumentException.class, () -> TargetConfig.load(new Properties(), env));
            assertTrue(failure.getMessage().contains(key));
            assertFalse(failure.getMessage().contains(SECRET));
            assertNull(failure.getCause());
          }
        });
    Map<String, String> env = local();
    env.put("ODEXA_ALLOW_MUTATION", "true");
    env.put("ODEXA_EXCLUSIVE_FIXTURES", "true");
    assertTrue(TargetConfig.load(new Properties(), env).allowMutation());
  }

  @Test
  void legacyModeCannotSilentlyDowngradeRemoteSafety() {
    Map<String, String> env = local();
    env.put("ODEXA_ENV", "remote");
    assertThrows(IllegalArgumentException.class, () -> TargetConfig.load(new Properties(), env));
    env.remove("ODEXA_ENV");
    for (String mode : new String[] {"compose", "testcontainers"}) {
      env.put("ODEXA_TARGET_MODE", mode);
      assertEquals(
          mode.toUpperCase(java.util.Locale.ROOT),
          TargetConfig.load(new Properties(), env).mode().name());
    }
    env.put("ODEXA_TARGET_MODE", "local");
    assertThrows(IllegalArgumentException.class, () -> TargetConfig.load(new Properties(), env));
  }

  @Test
  void remoteAndFrameworkClasspathContainsNoContainerRuntime() {
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("org.testcontainers.DockerClientFactory"));
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("qa.odexa.provisioning.OdexaLauncherSession"));
  }

  private static Map<String, String> local() {
    return new HashMap<>(Map.of("ODEXA_FIXTURE_PASSWORD", SECRET));
  }

  private static Map<String, String> remote() {
    Map<String, String> env = new HashMap<>();
    env.put("ODEXA_TARGET_MODE", "remote");
    env.put("ODEXA_BASE_URL", "https://api.example.test");
    env.put("ODEXA_TOKEN_URL", "https://identity.example.test/token");
    env.put("ODEXA_CLIENT_ID", "explicit-client");
    env.put("ODEXA_PRODUCT_ID", "11111111-1111-4111-8111-111111111111");
    env.put("ODEXA_OTHER_PRODUCT_ID", "22222222-2222-4222-8222-222222222222");
    env.put("ODEXA_TENANT_A", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    env.put("ODEXA_TENANT_B", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    for (Actor actor : Actor.values()) {
      env.put("ODEXA_" + actor.name() + "_USERNAME", "synthetic-user-" + actor.name());
      env.put("ODEXA_" + actor.name() + "_PASSWORD", SECRET);
    }
    return env;
  }
}
