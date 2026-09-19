package qa.odexa.provisioning;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qa.odexa.config.Actor;
import qa.odexa.config.TargetConfig;
import qa.odexa.config.TargetRuntime;

class ProvisioningPolicyTest {
  @TempDir Path temporary;

  @Test
  void remoteDockerIsRejectedBeforeCredentialsOrResourcesAreCreated() {
    OdexaEnvironment.requireLocalDocker(URI.create("unix:///var/run/docker.sock"));
    for (String host :
        new String[] {
          "tcp://localhost:2375", "tcp://remote.example.test:2376", "ssh://remote.example.test"
        }) {
      var failure =
          assertThrows(
              ProvisioningFailure.class,
              () -> OdexaEnvironment.requireLocalDocker(URI.create(host)));
      assertEquals("PROVISIONING FAILURE: local-docker-required", failure.getMessage());
    }
  }

  @Test
  void onlyVerifiedReleaseAndExactCommitAreAccepted() {
    PinnedRelease.validateVersion("v0.1.0");
    PinnedRelease.validateCommit("fac40f94377901419da4e1f26b99148ff5f550bf");
    for (String version : new String[] {"main", "latest", "v0.1.1", "v0.1.0;echo private"}) {
      var failure =
          assertThrows(ProvisioningFailure.class, () -> PinnedRelease.validateVersion(version));
      assertEquals("PROVISIONING FAILURE: pinned-release", failure.getMessage());
      assertNull(failure.getCause());
    }
    assertThrows(ProvisioningFailure.class, () -> PinnedRelease.validateCommit("0".repeat(40)));
    assertThrows(
        ProvisioningFailure.class, () -> PinnedRelease.validateCommit(PinnedRelease.COMMIT + "\n"));
  }

  @Test
  void generatedTargetHasDynamicEndpointsExclusiveFixturesAndSeparateSecrets() {
    var secrets = new EphemeralSecrets();
    var config =
        OdexaEnvironment.configuration(
            URI.create("http://localhost:49101"), URI.create("http://127.0.0.1:49102"), secrets);
    assertEquals(TargetConfig.Mode.TESTCONTAINERS, config.mode());
    assertEquals(49101, config.baseUri().getPort());
    assertEquals(49102, config.tokenUri().getPort());
    assertEquals("/realms/odexa/protocol/openid-connect/token", config.tokenUri().getPath());
    assertTrue(config.allowMutation());
    assertTrue(config.exclusiveFixtures());
    for (Actor actor : Actor.values()) {
      assertTrue(
          config.credentials(actor).password().equals(secrets.get("LOCAL_FIXTURE_PASSWORD")));
    }
    var passwords = new HashSet<String>();
    for (String key :
        new String[] {
          "POSTGRES_PASSWORD",
          "CATALOG_DB_PASSWORD",
          "INVENTORY_DB_PASSWORD",
          "ORDER_DB_PASSWORD",
          "PAYMENT_DB_PASSWORD",
          "SIMULATOR_DB_PASSWORD",
          "KEYCLOAK_DB_PASSWORD",
          "KC_BOOTSTRAP_ADMIN_PASSWORD",
          "LOCAL_FIXTURE_PASSWORD",
          "PROVIDER_API_KEY"
        }) {
      assertTrue(passwords.add(secrets.get(key)));
      assertTrue(secrets.get(key).matches("[A-Za-z0-9_-]{43}"));
      assertFalse(config.toString().contains(secrets.get(key)));
      assertFalse(secrets.toString().contains(secrets.get(key)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OdexaEnvironment.configuration(
                URI.create("http://public.example.test"),
                URI.create("http://localhost:1234"),
                secrets));
  }

  @Test
  void realmRenderingPreservesProductSecurityAndNeverEditsTemplate() throws Exception {
    var secrets = new EphemeralSecrets();
    Path template = temporary.resolve("realm.json");
    String user =
        "{\"credentials\":[{\"type\":\"password\",\"value\":\"__LOCAL_FIXTURE_PASSWORD__\"}]}";
    String original =
        "{\"bruteForceProtected\":true,\"users\":["
            + String.join(",", user, user, user, user)
            + "]}";
    Files.writeString(template, original);
    byte[] rendered = secrets.realm(template);
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rendered);
    assertTrue(json.path("bruteForceProtected").asBoolean());
    assertTrue(
        json.path("users")
            .get(0)
            .path("credentials")
            .get(0)
            .path("value")
            .asText()
            .equals(secrets.get("LOCAL_FIXTURE_PASSWORD")));
    assertEquals(original, Files.readString(template));
    Files.writeString(
        template, original.replace("__LOCAL_FIXTURE_PASSWORD__", "sensitive-sentinel"));
    var failure = assertThrows(ProvisioningFailure.class, () -> secrets.realm(template));
    assertEquals("PROVISIONING FAILURE: realm-template", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void suiteBindingRejectsOverlapAndCanBeReinstalledAfterClose() throws Exception {
    var config =
        OdexaEnvironment.configuration(
            URI.create("http://localhost:49101"),
            URI.create("http://localhost:49102"),
            new EphemeralSecrets());
    AutoCloseable binding = TargetRuntime.install(config);
    try {
      assertSame(config, TargetRuntime.current());
      assertThrows(IllegalStateException.class, () -> TargetRuntime.install(config));
    } finally {
      binding.close();
    }
    try (var next = TargetRuntime.install(config)) {
      assertSame(config, TargetRuntime.current());
    }
  }

  @Test
  void nonTestcontainersListenerDoesNotProbeDockerOrReadTargetConfiguration() {
    assertNotEquals("testcontainers", System.getProperty("odexa.targetMode"));
    var listener = new OdexaLauncherSession();
    listener.launcherSessionOpened(null);
    listener.launcherSessionClosed(null);
  }
}
