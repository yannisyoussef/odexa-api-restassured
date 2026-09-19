package qa.odexa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import qa.odexa.auth.ActorSessions;
import qa.odexa.auth.AuthenticatedSession;
import qa.odexa.client.CatalogClient;
import qa.odexa.client.InventoryClient;
import qa.odexa.client.OrderClient;
import qa.odexa.client.PaymentClient;
import qa.odexa.config.Actor;
import qa.odexa.config.TargetConfig;
import qa.odexa.fixture.FixtureMutationSafety;
import qa.odexa.http.ApiHttp;
import qa.odexa.http.ApiResponse;
import qa.odexa.model.Inventory;

/**
 * Per-class clients and transport, with no discovery-time configuration. Only the session of an
 * identity is shared across classes, so one fixture user is never authenticated concurrently.
 */
final class ApiContext {
  private final TargetConfig config;
  private final FixtureMutationSafety.FailureLatch mutationSafety;
  private final ApiHttp http;
  private final Map<Actor, ActorClients> actors;

  private ApiContext(TargetConfig config, Actor... requiredActors) {
    this.config = config;
    mutationSafety = FixtureMutationSafety.forTarget(config);
    http = new ApiHttp(config);
    Map<Actor, ActorClients> clients = new EnumMap<>(Actor.class);
    for (Actor actor : requiredActors) {
      // Test classes run in parallel: one shared session per identity keeps this suite from
      // authenticating the same fixture user concurrently. See ActorSessions.
      AuthenticatedSession session = ActorSessions.suite().forActor(config, actor);
      clients.put(
          actor,
          new ActorClients(
              new CatalogClient(http, session),
              new InventoryClient(http, session),
              new OrderClient(http, session, mutationSafety),
              new PaymentClient(http, session)));
    }
    actors = Map.copyOf(clients);
  }

  /** Call only from a test class's BeforeAll method. */
  static ApiContext load(Actor... actors) {
    TargetConfig config = qa.odexa.config.TargetRuntime.current();
    writeReportContext(config);
    return forConfig(config, actors);
  }

  static ApiContext forConfig(TargetConfig config, Actor... actors) {
    return new ApiContext(config, actors);
  }

  private static void writeReportContext(TargetConfig config) {
    String location = System.getProperty("allure.results.directory");
    if (location == null) return;
    java.nio.file.Path temporary = null;
    try {
      var directory = java.nio.file.Path.of(location);
      java.nio.file.Files.createDirectories(directory);
      var values = new java.util.Properties();
      values.setProperty("Environment", config.mode().name());
      values.setProperty("Odexa version", config.version());
      values.setProperty("API base URL", config.baseUri().toASCIIString());
      values.setProperty("Mutations enabled", Boolean.toString(config.allowMutation()));
      temporary = java.nio.file.Files.createTempFile(directory, "target-", ".tmp");
      try (var writer = java.nio.file.Files.newBufferedWriter(temporary)) {
        values.store(writer, "Validated non-secret target context");
      }
      java.nio.file.Files.move(
          temporary,
          directory.resolve("environment.properties"),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Could not write safe target report context");
    } finally {
      if (temporary != null) {
        try {
          java.nio.file.Files.deleteIfExists(temporary);
        } catch (java.io.IOException ignored) {
          /* Non-secret temporary context only. */
        }
      }
    }
  }

  TargetConfig config() {
    return config;
  }

  ApiHttp http() {
    return http;
  }

  ActorClients actor(Actor actor) {
    ActorClients clients = actors.get(actor);
    if (clients == null) {
      throw new IllegalArgumentException("Actor not included in this test class: " + actor.name());
    }
    return clients;
  }

  void requireMutation() {
    assumeTrue(
        config.allowMutation() && config.exclusiveFixtures(),
        "Mutations require ODEXA_ALLOW_MUTATION and ODEXA_EXCLUSIVE_FIXTURES");
    assumeTrue(
        !mutationSafety.isBlocked(),
        "Mutations blocked after uncertain fixture cleanup for this target and product");
  }

  void awaitInventory(InventoryClient merchantInventory, long onHand, long reserved, long version) {
    // Order and inventory are separate public projections: a terminal order is not a stock barrier.
    await("public inventory settlement")
        .pollDelay(Duration.ZERO)
        .pollInterval(config.pollInterval())
        .atMost(config.pollTimeout())
        .untilAsserted(
            () -> {
              ApiResponse response = merchantInventory.get(config.productId());
              response.response().then().statusCode(200).contentType(ContentType.JSON);
              Inventory stock = ApiChecks.inventory(response, config.productId());
              assertThat(stock.onHand()).as("settled on-hand stock").isEqualTo(onHand);
              assertThat(stock.reserved()).as("settled reservations").isEqualTo(reserved);
              assertThat(stock.available()).as("settled availability").isEqualTo(onHand - reserved);
              assertThat(stock.version()).as("settled inventory version").isEqualTo(version);
            });
  }

  record ActorClients(
      CatalogClient catalog,
      InventoryClient inventory,
      OrderClient orders,
      PaymentClient payments) {
    @Override
    public String toString() {
      return "ActorClients[public API clients]";
    }
  }
}
