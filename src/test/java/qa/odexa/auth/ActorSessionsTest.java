package qa.odexa.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import qa.odexa.config.Actor;
import qa.odexa.config.TargetConfig;

/**
 * The pinned product realm enables Keycloak brute-force protection, which denies overlapping
 * password grants for one identity. These tests pin the invariant that removes that overlap: an
 * identity authenticates once per suite, however many classes use it.
 */
@Timeout(15)
class ActorSessionsTest {
  private static final String SECRET = "synthetic-actor-session-secret";
  private final List<String> grants = new CopyOnWriteArrayList<>();
  private final ConcurrentMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
  private final AtomicInteger overlaps = new AtomicInteger();
  private HttpServer server;
  private ExecutorService handlers;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    handlers = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(handlers);
    server.createContext(
        "/token",
        exchange -> {
          String form =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          grants.add(form);
          String identity = identity(form);
          AtomicInteger concurrent =
              inFlight.computeIfAbsent(identity, ignored -> new AtomicInteger());
          try {
            if (concurrent.incrementAndGet() > 1) {
              overlaps.incrementAndGet();
            }
            byte[] body =
                ("{\"access_token\":\"token-for-"
                        + identity
                        + "\",\"refresh_token\":\"refresh-"
                        + identity
                        + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            }
          } finally {
            concurrent.decrementAndGet();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    handlers.shutdownNow();
  }

  @Test
  void independentContextsShareOneSessionAndOneGrantPerActor() throws Exception {
    ActorSessions sessions = new ActorSessions();
    TargetConfig config = config("/token");
    List<AuthenticatedSession> perClass = new ArrayList<>();
    for (int testClass = 0; testClass < 6; testClass++) {
      // Every class loads its own configuration, exactly as ApiContext.load does.
      perClass.add(sessions.forActor(config("/token"), Actor.CUSTOMER_A));
    }
    for (AuthenticatedSession session : perClass) {
      assertSame(perClass.getFirst(), session);
    }
    assertSame(perClass.getFirst(), sessions.forActor(config, Actor.CUSTOMER_A));

    assertEquals(
        List.of("token-for-customer-a"), List.copyOf(tokensInParallel(perClass)), "shared token");
    assertEquals(1, grants.size(), "one identity authenticates exactly once");
    assertTrue(grants.getFirst().contains("grant_type=password"));
    assertEquals(0, overlaps.get(), "no overlapping grant for one identity");
  }

  @Test
  void actorsAndTargetsRemainIndependentIdentities() throws Exception {
    ActorSessions sessions = new ActorSessions();
    TargetConfig config = config("/token");
    AuthenticatedSession customerA = sessions.forActor(config, Actor.CUSTOMER_A);
    AuthenticatedSession customerB = sessions.forActor(config, Actor.CUSTOMER_B);
    AuthenticatedSession otherEndpoint = sessions.forActor(config("/other"), Actor.CUSTOMER_A);

    assertNotSame(customerA, customerB);
    assertNotSame(customerA, otherEndpoint);
    assertEquals(Actor.CUSTOMER_A, customerA.actor());
    assertEquals(Actor.CUSTOMER_B, customerB.actor());
    assertEquals("token-for-customer-a", customerA.accessToken());
    assertEquals("token-for-customer-b", customerB.accessToken());
    assertEquals(2, grants.size());
    assertNotSame(new ActorSessions().forActor(config, Actor.CUSTOMER_A), customerA);
  }

  @Test
  void suiteRegistryIsTheSameRegistryForEveryCaller() {
    assertSame(ActorSessions.suite(), ActorSessions.suite());
    assertNotSame(ActorSessions.suite(), new ActorSessions());
  }

  @Test
  void failedAuthenticationIsNotAttributedToAnUnspecifiedActor() {
    ActorSessions sessions = new ActorSessions();
    AuthException failure =
        assertThrows(
            AuthException.class,
            () -> sessions.forActor(config("/missing"), Actor.MERCHANT_A).accessToken());
    assertTrue(failure.getMessage().contains("actor=MERCHANT_A"), failure.getMessage());
    assertTrue(failure.toString().contains("MERCHANT_A"));
  }

  private List<String> tokensInParallel(List<AuthenticatedSession> sessions) throws Exception {
    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<String>> futures = new ArrayList<>();
      for (int caller = 0; caller < 24; caller++) {
        AuthenticatedSession session = sessions.get(caller % sessions.size());
        futures.add(
            workers.submit(
                () -> {
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  return session.accessToken();
                }));
      }
      start.countDown();
      List<String> tokens = new ArrayList<>();
      for (Future<String> future : futures) {
        String token = future.get(5, TimeUnit.SECONDS);
        if (!tokens.contains(token)) {
          tokens.add(token);
        }
      }
      return tokens;
    }
  }

  private static String identity(String form) {
    for (String field : form.split("&")) {
      if (field.startsWith("username=")) {
        return field.substring("username=".length());
      }
    }
    return "unknown";
  }

  private TargetConfig config(String path) {
    return TargetConfig.load(
        new Properties(),
        Map.of(
            "ODEXA_FIXTURE_PASSWORD",
            SECRET,
            "ODEXA_TIMEOUT_SECONDS",
            "2",
            "ODEXA_TOKEN_URL",
            "http://127.0.0.1:" + server.getAddress().getPort() + path));
  }
}
