package qa.odexa.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.exception.NotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import qa.odexa.auth.ActorSessions;
import qa.odexa.config.Actor;
import qa.odexa.config.TargetConfig;

/** One real nine-container system per launcher session. HTTP remains the observation boundary. */
final class OdexaEnvironment implements SuiteTarget {
  private static final Duration READY_TIMEOUT = Duration.ofMinutes(4);
  private final Map<String, GenericContainer<?>> containers = new LinkedHashMap<>();
  private final List<String> images = new ArrayList<>();
  private final Map<String, Object> metadata = new LinkedHashMap<>();
  private final Path diagnostics;
  private PinnedRelease release;
  private Network network;
  private String networkId;
  private boolean closed;

  OdexaEnvironment(Path diagnostics) {
    this.diagnostics = diagnostics;
    metadata.put("mode", "testcontainers");
    metadata.put("version", PinnedRelease.VERSION);
    metadata.put("commit", PinnedRelease.COMMIT);
    metadata.put("result", "provisioning");
    metadata.put("cleanup", "not-started");
  }

  @Override
  public synchronized TargetConfig start(String version) {
    if (closed)
      throw new ProvisioningFailure(ProvisioningFailure.Stage.PROVISIONING, "closed-target");
    try {
      stage("PROVISIONING", "pinned-release");
      PinnedRelease.validateVersion(version);
      stage("PROVISIONING", "local-docker-required");
      requireLocalDocker(DockerClientFactory.instance().getTransportConfig().getDockerHost());
      stage("PROVISIONING", "pinned-release");
      release = PinnedRelease.acquire(version);
      var secrets = new EphemeralSecrets();
      network = Network.newNetwork();
      Map<String, String> productImages = buildImages();
      var postgres =
          own(
              "postgres",
              new PrivatePostgres()
                  .withDatabaseName("postgres")
                  .withUsername("odexa_admin")
                  .withPassword(secrets.get("POSTGRES_PASSWORD"))
                  .withCopyFileToContainer(
                      MountableFile.forHostPath(
                          release.path().resolve("infrastructure/postgres/01-databases.sh"), 0755),
                      "/docker-entrypoint-initdb.d/01-databases.sh"));
      postgres.setExposedPorts(List.of());
      // The module waits for the final PostgreSQL startup log, after the official init script.
      for (String role :
          List.of("CATALOG", "INVENTORY", "ORDER", "PAYMENT", "SIMULATOR", "KEYCLOAK")) {
        postgres.withEnv(role + "_DB_PASSWORD", secrets.get(role + "_DB_PASSWORD"));
      }
      var kafka = own("kafka", new PrivateKafka());
      kafka
          .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
          .withEnv("KAFKA_NUM_PARTITIONS", "3")
          .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m");
      var keycloak =
          own(
              "keycloak",
              new GenericContainer<>("quay.io/keycloak/keycloak:26.4.7")
                  .withCommand("start-dev", "--import-realm")
                  .withEnv("KC_DB", "postgres")
                  .withEnv("KC_DB_URL", "jdbc:postgresql://postgres:5432/keycloak")
                  .withEnv("KC_DB_USERNAME", "keycloak")
                  .withEnv("KC_DB_PASSWORD", secrets.get("KEYCLOAK_DB_PASSWORD"))
                  .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "local-admin")
                  .withEnv(
                      "KC_BOOTSTRAP_ADMIN_PASSWORD", secrets.get("KC_BOOTSTRAP_ADMIN_PASSWORD"))
                  // Stable internal issuer; host clients use the mapped token endpoint. JWKS stays
                  // private.
                  .withEnv("KC_HOSTNAME", "http://keycloak:8080")
                  .withEnv("KC_HTTP_ENABLED", "true")
                  .withEnv("KC_HEALTH_ENABLED", "true")
                  .withExposedPorts(8080)
                  .withCopyToContainer(
                      Transferable.of(
                          secrets.realm(
                              release
                                  .path()
                                  .resolve("infrastructure/keycloak/odexa-realm.template.json")),
                          0644),
                      "/opt/keycloak/data/import/odexa-realm.json")
                  .dependsOn(postgres)
                  .waitingFor(
                      Wait.forHttp("/realms/odexa/.well-known/openid-configuration")
                          .forStatusCode(200)
                          .withStartupTimeout(READY_TIMEOUT)));
      var simulator =
          service("payment-simulator", 8085, productImages)
              .withEnv("SPRING_PROFILES_ACTIVE", "local")
              .withEnv("DB_URL", "jdbc:postgresql://postgres:5432/simulator")
              .withEnv("DB_USER", "simulator")
              .withEnv("DB_PASSWORD", secrets.get("SIMULATOR_DB_PASSWORD"))
              .withEnv("PROVIDER_API_KEY", secrets.get("PROVIDER_API_KEY"))
              .dependsOn(postgres);
      var catalog =
          domain("catalog", 8081, "catalog", "CATALOG", productImages, secrets)
              .dependsOn(postgres, kafka, keycloak);
      var inventory =
          domain("inventory", 8082, "inventory", "INVENTORY", productImages, secrets)
              .dependsOn(postgres, kafka, keycloak);
      var order =
          domain("order", 8083, "orders", "ORDER", productImages, secrets)
              .withEnv("CATALOG_URL", "http://catalog:8081")
              .dependsOn(postgres, kafka, keycloak, catalog);
      var payment =
          domain("payment", 8084, "payment", "PAYMENT", productImages, secrets)
              .withEnv("SIMULATOR_URL", "http://payment-simulator:8085")
              .withEnv("PROVIDER_API_KEY", secrets.get("PROVIDER_API_KEY"))
              .dependsOn(postgres, kafka, keycloak, simulator);
      var gateway =
          service("gateway", 8080, productImages)
              .withEnv("CATALOG_URL", "http://catalog:8081")
              .withEnv("INVENTORY_URL", "http://inventory:8082")
              .withEnv("ORDER_URL", "http://order:8083")
              .withEnv("PAYMENT_URL", "http://payment:8084")
              .withExposedPorts(8080)
              .dependsOn(catalog, inventory, order, payment);

      stage("TARGET_READINESS", "service-graph");
      // Settle every independent batch before moving on or cleaning up. Testcontainers deepStart
      // fails fast, so using it directly would race sibling starts against teardown on failure.
      networkId = network.getId();
      startTogether(postgres, kafka);
      startTogether(keycloak, simulator);
      startTogether(catalog, inventory, payment);
      startTogether(order);
      startTogether(gateway);
      TargetConfig config = configuration(endpoint(gateway), endpoint(keycloak), secrets);
      stage("AUTHENTICATION_CONFIGURATION", "realm-token-flow");
      // Seed the SAME shared sessions as API tests; no second racing grant after readiness.
      for (Actor actor : Actor.values())
        ActorSessions.suite().forActor(config, actor).accessToken();
      metadata.put("api_base_uri", config.baseUri().toASCIIString());
      metadata.put("token_uri", config.tokenUri().toASCIIString());
      metadata.put("result", "ready");
      writeDiagnostics();
      return config;
    } catch (Exception failure) {
      metadata.put("result", "failed");
      captureStates();
      writeDiagnostics();
      String stage = (String) metadata.get("stage");
      throw new ProvisioningFailure(
          ProvisioningFailure.Stage.valueOf(stage), (String) metadata.get("role"));
    }
  }

  static void requireLocalDocker(URI dockerHost) {
    if (!"unix".equals(dockerHost.getScheme())
        || dockerHost.getPath() == null
        || !dockerHost.getPath().startsWith("/")) {
      throw new ProvisioningFailure(
          ProvisioningFailure.Stage.PROVISIONING, "local-docker-required");
    }
  }

  private Map<String, String> buildImages() {
    Map<String, String> built = new LinkedHashMap<>();
    String run = UUID.randomUUID().toString();
    // Keep expensive Gradle builds sequential; Docker reuses common immutable layers.
    for (String role :
        List.of("catalog", "inventory", "order", "payment", "payment-simulator", "gateway")) {
      stage("PROVISIONING", "image-" + role);
      String name = "odexa-qa-" + run + "/" + role + ":" + PinnedRelease.VERSION;
      images.add(name); // Register ownership before a build can partially succeed.
      var image =
          new ImageFromDockerfile(name, true)
              .withDockerfile(release.path().resolve("Dockerfile"))
              .withBuildArg("SERVICE", role);
      // Synchronous resolution ensures no orphaned Java build worker can outlive teardown.
      // The Gradle task/CI job supplies the outer execution deadline; Ryuk retains crash cleanup.
      built.put(role, image.get());
    }
    return built;
  }

  private void startTogether(GenericContainer<?>... batch) {
    StartupBatch.run(
        java.util.Arrays.stream(batch)
            .map(container -> (Runnable) container::start)
            .toArray(Runnable[]::new));
  }

  private <C extends GenericContainer<?>> C own(String role, C container) {
    container
        .withNetwork(network)
        .withNetworkAliases(role)
        .withReuse(false)
        .withStartupTimeout(READY_TIMEOUT);
    container.withCreateContainerCmdModifier(
        command -> {
          var bindings =
              container.getExposedPorts().stream()
                  .map(
                      port ->
                          new com.github.dockerjava.api.model.PortBinding(
                              com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"),
                              new com.github.dockerjava.api.model.ExposedPort(port)))
                  .toArray(com.github.dockerjava.api.model.PortBinding[]::new);
          command.getHostConfig().withPortBindings(bindings).withPublishAllPorts(false);
        });
    containers.put(role, container);
    return container;
  }

  private GenericContainer<?> service(String role, int port, Map<String, String> productImages) {
    return own(role, new GenericContainer<>(productImages.get(role)))
        .withEnv("SERVER_PORT", Integer.toString(port))
        .withCreateContainerCmdModifier(
            command ->
                command
                    .getHostConfig()
                    .withMemory(768L * 1024 * 1024)
                    .withReadonlyRootfs(true)
                    .withTmpFs(Map.of("/tmp", "rw,size=64m,mode=1777"))
                    .withCapDrop(com.github.dockerjava.api.model.Capability.ALL)
                    .withSecurityOpts(List.of("no-new-privileges:true")))
        .waitingFor(Wait.forHealthcheck().withStartupTimeout(READY_TIMEOUT));
  }

  private GenericContainer<?> domain(
      String role,
      int port,
      String database,
      String secret,
      Map<String, String> images,
      EphemeralSecrets secrets) {
    return service(role, port, images)
        .withEnv("SPRING_PROFILES_ACTIVE", "local")
        .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
        .withEnv("OIDC_ISSUER", "http://keycloak:8080/realms/odexa")
        .withEnv("OIDC_JWKS", "http://keycloak:8080/realms/odexa/protocol/openid-connect/certs")
        .withEnv("DB_URL", "jdbc:postgresql://postgres:5432/" + database)
        .withEnv("DB_USER", database)
        .withEnv("DB_PASSWORD", secrets.get(secret + "_DB_PASSWORD"));
  }

  static TargetConfig configuration(URI gateway, URI keycloak, EphemeralSecrets secrets) {
    return TargetConfig.load(
        new Properties(),
        Map.of(
            "ODEXA_TARGET_MODE",
            "testcontainers",
            "ODEXA_BASE_URL",
            gateway.toASCIIString(),
            "ODEXA_TOKEN_URL",
            keycloak.resolve("/realms/odexa/protocol/openid-connect/token").toASCIIString(),
            "ODEXA_VERSION",
            PinnedRelease.VERSION,
            "ODEXA_FIXTURE_PASSWORD",
            secrets.get("LOCAL_FIXTURE_PASSWORD"),
            "ODEXA_ALLOW_MUTATION",
            "true",
            "ODEXA_EXCLUSIVE_FIXTURES",
            "true"));
  }

  private static URI endpoint(GenericContainer<?> container) {
    try {
      return new URI(
          "http", null, container.getHost(), container.getMappedPort(8080), null, null, null);
    } catch (Exception ignored) {
      throw new ProvisioningFailure(ProvisioningFailure.Stage.PROVISIONING, "mapped-endpoint");
    }
  }

  private void stage(String stage, String role) {
    metadata.put("stage", stage);
    metadata.put("role", role);
    System.out.println("TESTCONTAINERS stage=" + stage + "; role=" + role);
    writeDiagnostics();
  }

  private void captureStates() {
    List<Map<String, Object>> states = new ArrayList<>();
    containers.forEach(
        (role, container) -> {
          Map<String, Object> state = new LinkedHashMap<>();
          state.put("role", role);
          try {
            if (container.getContainerId() != null) {
              var info =
                  container
                      .getDockerClient()
                      .inspectContainerCmd(container.getContainerId())
                      .exec();
              state.put("running", Boolean.TRUE.equals(info.getState().getRunning()));
              state.put("exit_code", info.getState().getExitCodeLong());
              String health =
                  info.getState().getHealth() == null
                      ? "none"
                      : info.getState().getHealth().getStatus();
              state.put(
                  "health",
                  List.of("healthy", "unhealthy", "starting", "none").contains(health)
                      ? health
                      : "unknown");
              state.put("image_id", info.getImageId());
            } else state.put("state", "not-started-or-removed");
          } catch (RuntimeException ignored) {
            state.put("state", "unavailable");
          }
          states.add(state);
        });
    metadata.put("containers", states);
  }

  private void writeDiagnostics() {
    try {
      Files.createDirectories(diagnostics);
      new ObjectMapper()
          .writerWithDefaultPrettyPrinter()
          .writeValue(diagnostics.resolve("testcontainers-target.json").toFile(), metadata);
    } catch (IOException ignored) {
      throw new ProvisioningFailure(ProvisioningFailure.Stage.PROVISIONING, "safe-diagnostics");
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    captureStates();
    boolean failed = false;
    List<GenericContainer<?>> reversed = new ArrayList<>(containers.values());
    java.util.Collections.reverse(reversed);
    for (GenericContainer<?> container : reversed) {
      String id = container.getContainerId();
      try {
        container.stop();
        // stop() is best-effort upstream: explicitly verify owned removal before reporting success.
        if (id != null) {
          try {
            container.getDockerClient().inspectContainerCmd(id).exec();
            failed = true;
          } catch (NotFoundException removed) {
            /* Confirmed absent. */
          }
        }
      } catch (RuntimeException ignored) {
        failed = true;
      }
    }
    if (network != null) {
      try {
        // Capture before close: getId() after close would create a new network.
        network.close();
        if (networkId != null) {
          try {
            DockerClientFactory.instance()
                .client()
                .inspectNetworkCmd()
                .withNetworkId(networkId)
                .exec();
            failed = true;
          } catch (NotFoundException removed) {
            /* Confirmed absent. */
          }
        }
      } catch (RuntimeException ignored) {
        failed = true;
      }
    }
    for (String image : images) {
      try {
        DockerClientFactory.instance().client().removeImageCmd(image).exec();
      } catch (NotFoundException absent) {
        /* Build may have failed before producing an image. */
      } catch (RuntimeException ignored) {
        failed = true;
      }
    }
    if (release != null) {
      try {
        release.close();
      } catch (RuntimeException ignored) {
        failed = true;
      }
    }
    metadata.put("cleanup", failed ? "failed" : "passed");
    writeDiagnostics();
    System.out.println("TESTCONTAINERS cleanup=" + metadata.get("cleanup"));
    if (failed) throw new ProvisioningFailure(ProvisioningFailure.Stage.CLEANUP, "owned-resources");
  }

  /** The module's default post-start hook formats a mapped JDBC URL even without a JDBC probe. */
  private static final class PrivatePostgres extends PostgreSQLContainer {
    PrivatePostgres() {
      super("postgres:17.6-bookworm");
    }

    @Override
    protected void containerIsStarted(
        com.github.dockerjava.api.command.InspectContainerResponse info) {
      // Official init script and final-startup log wait already completed; no host JDBC connection.
    }
  }

  /** Kafka's default external listener is replaced with its private alias; no broker host port. */
  private static final class PrivateKafka extends KafkaContainer {
    PrivateKafka() {
      super("apache/kafka:4.1.1");
      setExposedPorts(List.of());
    }

    @Override
    public String getBootstrapServers() {
      return "kafka:9092";
    }
  }
}
