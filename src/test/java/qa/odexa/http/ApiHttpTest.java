package qa.odexa.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.restassured.http.Method;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import qa.odexa.auth.AuthenticatedSession;
import qa.odexa.config.Actor;
import qa.odexa.config.TargetConfig;

@Timeout(20)
class ApiHttpTest {
  private static final String SECRET = "synthetic-http-secret";
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicReference<Reply> reply =
      new AtomicReference<>(new Reply(200, "{\"ok\":true}"));
  private HttpServer server;
  private ExecutorService handlers;
  private TargetConfig config;
  private ApiHttp api;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    handlers = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(handlers);
    server.createContext(
        "/api/v1/",
        exchange -> {
          capture(exchange);
          Reply response = reply.get();
          send(exchange, response.status(), response.body());
        });
    server.createContext(
        "/token",
        exchange ->
            send(
                exchange,
                200,
                "{\"access_token\":\""
                    + SECRET
                    + "\",\"token_type\":\"Bearer\",\"expires_in\":60}"));
    server.start();
    config = config(3);
    api = new ApiHttp(config);
  }

  @AfterEach
  void stop() {
    server.stop(0);
    handlers.shutdownNow();
  }

  @Test
  void missingAndInvalidTokenRequestsRemainAvailableWithoutSession() {
    ApiResponse missing =
        api.execute(null, Method.GET, "/api/v1/products", Map.of(), Map.of(), null);
    missing.response().then().statusCode(200).contentType("application/json");
    assertNull(received.get(0).authorization());
    api.execute(
        null,
        Method.GET,
        "/api/v1/products",
        null,
        Map.of("Authorization", "Bearer intentionally-invalid"),
        null);
    assertEquals("Bearer intentionally-invalid", received.get(1).authorization());
    assertEquals("application/json", received.get(0).accept());
    assertTrue(received.get(0).contentType().startsWith("application/json"));
  }

  @Test
  void authenticatedSessionOwnsAuthorizationAndLaterRequestsAreFresh() {
    AuthenticatedSession session = AuthenticatedSession.create(Actor.CUSTOMER_A, config);
    api.execute(
        session,
        Method.POST,
        "/api/v1/orders",
        Map.of("privateQuery", SECRET),
        Map.of("aUtHoRiZaTiOn", "Bearer wrong", "X-Unit", "first"),
        "{\"private\":\"" + SECRET + "\"}");
    api.execute(null, Method.GET, "/api/v1/products", Map.of(), Map.of(), null);
    assertTrue(received.get(0).authorization().equals("Bearer " + SECRET));
    assertNull(received.get(1).authorization());
    assertNull(received.get(1).query());
    assertNull(received.get(1).unit());
    assertTrue(received.get(1).body().isEmpty());
    assertFalse(api.toString().contains(SECRET));
  }

  @Test
  void preservesRawNegativeJsonAndSerializesTypedObjectsWithJackson() {
    String malformed = "{\"private\":\"" + SECRET + "\",broken";
    api.execute(null, Method.POST, "/api/v1/orders", Map.of(), Map.of(), malformed);
    assertTrue(received.get(0).body().equals(malformed));
    Payload payload = new Payload(7, Instant.parse("2026-01-01T00:00:00Z"));
    api.execute(null, Method.POST, "/api/v1/orders", Map.of(), Map.of(), payload);
    assertTrue(received.get(1).body().contains("\"quantity\":7"));
    assertTrue(received.get(1).body().contains("2026-01-01T00:00:00Z"));
  }

  @Test
  void honorsCanonicalCorrelationOverrideAndRejectsUntrustedEchoes() {
    String override = UUID.randomUUID().toString();
    ApiResponse valid =
        api.execute(
            null,
            Method.GET,
            "/api/v1/products",
            Map.of(),
            Map.of("x-correlation-id", override),
            null);
    assertEquals(override, valid.correlationId());
    assertEquals(override, received.get(0).correlation());
    ApiResponse invalid =
        api.execute(
            null,
            Method.GET,
            "/api/v1/products",
            Map.of(),
            Map.of("X-Correlation-ID", SECRET),
            null);
    assertEquals(UUID.fromString(invalid.correlationId()).toString(), invalid.correlationId());
    assertEquals(invalid.correlationId(), received.get(1).correlation());
    assertFalse(invalid.toString().contains(SECRET));
  }

  @Test
  void parallelCallsHaveIndependentSpecificationsAndCorrelations() throws Exception {
    List<Future<ApiResponse>> futures = new ArrayList<>();
    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 16; i++) {
        String id = Integer.toString(i);
        futures.add(
            workers.submit(
                () ->
                    api.execute(
                        null,
                        Method.GET,
                        "/api/v1/products/" + id,
                        Map.of("unit", id),
                        Map.of("X-Unit", id),
                        null)));
      }
      HashSet<String> ids = new HashSet<>();
      for (Future<ApiResponse> future : futures) {
        ApiResponse response = future.get(10, TimeUnit.SECONDS);
        assertEquals(200, response.status());
        assertTrue(ids.add(UUID.fromString(response.correlationId()).toString()));
      }
    }
    assertEquals(16, received.size());
    for (Received request : received) {
      assertEquals("/api/v1/products/" + request.unit(), request.path());
      assertEquals("unit=" + request.unit(), request.query());
      assertNull(request.authorization());
    }
    assertEquals(16, received.stream().map(Received::correlation).distinct().count());
  }

  @Test
  void redirectsNeverForwardAnAuthorizationHeader() {
    AtomicInteger followed = new AtomicInteger();
    server.createContext(
        "/api/v1/redirect",
        exchange -> {
          capture(exchange);
          exchange.getResponseHeaders().set("Location", "/api/v1/sink");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    server.createContext(
        "/api/v1/sink",
        exchange -> {
          followed.incrementAndGet();
          send(exchange, 200, "{}");
        });
    ApiResponse response =
        api.execute(
            AuthenticatedSession.create(Actor.CUSTOMER_A, config),
            Method.POST,
            "/api/v1/redirect",
            Map.of(),
            Map.of(),
            "{}");
    assertEquals(307, response.status());
    assertEquals(0, followed.get());
  }

  @Test
  void absoluteNetworkTraversalEncodedPathsAndUnsafeHeadersAreRejectedBeforeNetwork() {
    for (String path :
        List.of(
            "https://example.test/api/v1/" + SECRET,
            "//example.test/api/v1/products",
            "/api/v1/../token",
            "/api/v1/%2e%2e/token",
            "/api/v1/products?password=" + SECRET,
            "/api/v1/products#" + SECRET,
            "/token",
            "/api/v1//token",
            "/api/v1/\\token")) {
      assertSafe(
          assertThrows(
              ApiException.class,
              () -> api.execute(null, Method.GET, path, Map.of(), Map.of(), null)));
    }
    for (Map<String, String> headers :
        List.of(
            Map.of("Host", "example.test"),
            Map.of("X-Unit", "\r\n" + SECRET),
            Map.of("Content-Length", "200"))) {
      assertSafe(
          assertThrows(
              ApiException.class,
              () -> api.execute(null, Method.GET, "/api/v1/products", Map.of(), headers, null)));
    }
    assertTrue(received.isEmpty());
  }

  @Test
  void timeoutIsBoundedAndAmbiguousWritesAreNotRetried() {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger attempts = new AtomicInteger();
    server.createContext(
        "/api/v1/slow",
        exchange -> {
          attempts.incrementAndGet();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    ApiHttp bounded = new ApiHttp(config(1));
    try {
      assertSafe(
          assertThrows(
              ApiException.class,
              () ->
                  bounded.execute(
                      null,
                      Method.POST,
                      "/api/v1/slow",
                      Map.of("secret", SECRET),
                      Map.of("Authorization", "Bearer " + SECRET),
                      SECRET)));
      assertEquals(1, attempts.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void serializationFailureDoesNotExposeObjectExceptionOrCause() {
    assertSafe(
        assertThrows(
            ApiException.class,
            () ->
                api.execute(
                    null,
                    Method.POST,
                    "/api/v1/orders",
                    Map.of(),
                    Map.of(),
                    new UnserializablePayload())));
    assertTrue(received.isEmpty());
  }

  @Test
  void maliciousFailureResponseRemainsMetadataOnly() {
    reply.set(new Reply(400, "{\"detail\":\"" + SECRET + "\",\"code\":\"" + SECRET + "\"}"));
    ApiResponse response =
        api.execute(
            null,
            Method.POST,
            "/api/v1/orders/" + SECRET,
            Map.of("secret", SECRET),
            Map.of("Authorization", "Bearer " + SECRET),
            SECRET);
    assertEquals(400, response.status());
    assertFalse(response.toString().contains(SECRET));
  }

  private void capture(HttpExchange exchange) throws IOException {
    received.add(
        new Received(
            exchange.getRequestURI().getPath(),
            exchange.getRequestURI().getRawQuery(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            exchange.getRequestHeaders().getFirst("X-Correlation-ID"),
            exchange.getRequestHeaders().getFirst("X-Unit"),
            exchange.getRequestHeaders().getFirst("Accept"),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    String correlation = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
    if (correlation != null) {
      exchange.getResponseHeaders().set("X-Correlation-ID", correlation);
    }
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private TargetConfig config(int timeoutSeconds) {
    Map<String, String> env = new HashMap<>();
    env.put("ODEXA_FIXTURE_PASSWORD", SECRET);
    env.put("ODEXA_TIMEOUT_SECONDS", Integer.toString(timeoutSeconds));
    env.put("ODEXA_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
    env.put("ODEXA_TOKEN_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    return TargetConfig.load(new Properties(), env);
  }

  private static void assertSafe(ApiException failure) {
    assertNull(failure.getCause());
    assertEquals(0, failure.getSuppressed().length);
    StringWriter stack = new StringWriter();
    failure.printStackTrace(new PrintWriter(stack));
    assertFalse(stack.toString().contains(SECRET));
    assertFalse(stack.toString().contains("http://"));
    assertFalse(stack.toString().contains("https://"));
  }

  public record Payload(int quantity, Instant createdAt) {}

  public static final class UnserializablePayload {
    public String getValue() {
      throw new IllegalStateException(SECRET);
    }
  }

  private record Received(
      String path,
      String query,
      String authorization,
      String correlation,
      String unit,
      String accept,
      String contentType,
      String body) {
    @Override
    public String toString() {
      return "Received[metadata omitted]";
    }
  }

  private record Reply(int status, String body) {
    @Override
    public String toString() {
      return "Reply[status=" + status + "]";
    }
  }
}
