package qa.odexa.auth;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qa.odexa.config.Actor;
import qa.odexa.config.TargetConfig;

@Timeout(15)
class AuthDeadlineTest {
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final CountDownLatch releaseBody = new CountDownLatch(1);
  private HttpServer server;
  private TargetConfig config;

  @AfterEach
  void close() throws InterruptedException {
    releaseBody.countDown();
    if (server != null) {
      server.stop(0);
    }
    workers.shutdownNow();
    assertTrue(workers.awaitTermination(3, SECONDS), "Local HTTP workers must terminate");
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 400})
  void deadlineIncludesStalledBodyAfterHeaders(int status) throws Exception {
    CountDownLatch headersSent = new CountDownLatch(1);
    AuthClient client = stalledResponse(headersSent, status, 2);
    Future<AuthException> result = workers.submit(() -> failure(client));

    assertTrue(headersSent.await(3, SECONDS), "Server must flush headers and a partial body");
    assertSanitized(result.get(4, SECONDS), 0, AuthException.Classification.TRANSPORT);
    assertEquals(1L, releaseBody.getCount(), "Deadline must not wait for the body to finish");
  }

  @Test
  void interruptionCancelsStalledBodyAndRestoresInterruptFlag() throws Exception {
    CountDownLatch headersSent = new CountDownLatch(1);
    AuthClient client = stalledResponse(headersSent, 200, 10);
    AtomicReference<Thread> caller = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Future<AuthException> result =
        workers.submit(
            () -> {
              caller.set(Thread.currentThread());
              AuthException failure = failure(client);
              interrupted.set(Thread.currentThread().isInterrupted());
              return failure;
            });

    assertTrue(headersSent.await(3, SECONDS), "Server must flush headers and a partial body");
    caller.get().interrupt();
    assertSanitized(result.get(3, SECONDS), 0, AuthException.Classification.INTERRUPTED);
    assertTrue(interrupted.get(), "Caller interrupt status must be preserved");
    assertEquals(1L, releaseBody.getCount());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void rejectsOversizeBeforeBodyCompletion(boolean fixedLength) throws Exception {
    // Keep the response open: checking length only after collecting the body is not sufficient.
    AuthClient client = responding(tokenResponse(64 * 1024 + 1), fixedLength, true);
    Future<AuthException> result = workers.submit(() -> failure(client));

    assertSanitized(result.get(4, SECONDS), 200, AuthException.Classification.INVALID_RESPONSE);
    assertEquals(1L, releaseBody.getCount());
  }

  @Test
  void limitCountsUtf8BytesNotCharacters() throws Exception {
    String response = tokenResponse(40 * 1024).replace(".", "é");
    assertTrue(response.length() < 64 * 1024);
    assertTrue(response.getBytes(UTF_8).length > 64 * 1024);
    AuthClient client = responding(response, false, false);

    assertSanitized(failure(client), 200, AuthException.Classification.INVALID_RESPONSE);
  }

  @Test
  void acceptsValidResponseAtExactly64KiB() throws Exception {
    String response = tokenResponse(64 * 1024);
    assertEquals(64 * 1024, response.getBytes(UTF_8).length);
    AuthClient client = responding(response, false, false);

    AuthClient.Token token =
        client.authenticate(config.credentials(Actor.CUSTOMER_A), Actor.CUSTOMER_A);

    assertEquals("fixture-access", token.accessToken);
    assertEquals(60, token.expiresIn);
    assertNull(token.refreshToken);
  }

  @Test
  void malformedResponseIsSanitizedWithoutParserCauseOrBody() throws Exception {
    AuthClient client =
        responding(
            "{\"access_token\":\"fixture-access\", malformed-fixture-response}", false, false);

    assertSanitized(failure(client), 200, AuthException.Classification.INVALID_RESPONSE);
  }

  private AuthException failure(AuthClient client) {
    return assertThrows(
        AuthException.class,
        () -> client.authenticate(config.credentials(Actor.CUSTOMER_A), Actor.CUSTOMER_A));
  }

  private static void assertSanitized(
      AuthException failure, int status, AuthException.Classification classification) {
    assertEquals(status, failure.status());
    assertEquals(classification, failure.classification());
    assertEquals(
        "Authentication failed: actor=CUSTOMER_A, status="
            + status
            + ", classification="
            + classification,
        failure.getMessage());
    assertNull(failure.getCause());
    assertEquals(0, failure.getSuppressed().length);
  }

  private AuthClient stalledResponse(CountDownLatch headersSent, int status, int timeoutSeconds)
      throws IOException {
    return client(
        exchange -> {
          try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            headersSent.countDown();
            awaitRelease();
          }
        },
        timeoutSeconds);
  }

  private AuthClient responding(String response, boolean fixedLength, boolean stall)
      throws IOException {
    byte[] bytes = response.getBytes(UTF_8);
    return client(
        exchange -> {
          try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            // Withhold one advertised byte when stalling a fixed-length response.
            exchange.sendResponseHeaders(200, fixedLength ? bytes.length + (stall ? 1 : 0) : 0);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().flush();
            if (stall) {
              awaitRelease();
            }
          }
        },
        10);
  }

  private void awaitRelease() {
    try {
      releaseBody.await(10, SECONDS);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private AuthClient client(HttpHandler handler, int timeoutSeconds) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(workers);
    server.createContext("/token", handler);
    server.start();
    Properties properties = new Properties();
    properties.setProperty(
        "odexa.tokenUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    properties.setProperty("odexa.timeoutSeconds", Integer.toString(timeoutSeconds));
    config = TargetConfig.load(properties, Map.of("ODEXA_FIXTURE_PASSWORD", "fixture-password"));
    return new AuthClient(config);
  }

  private static String tokenResponse(int size) {
    String prefix =
        "{\"access_token\":\"fixture-access\",\"token_type\":\"Bearer\",\"expires_in\":60,\"padding\":\"";
    return prefix + ".".repeat(size - prefix.length() - 2) + "\"}";
  }
}
