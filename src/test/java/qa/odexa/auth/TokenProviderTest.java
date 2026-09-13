package qa.odexa.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import qa.odexa.config.Actor;
import qa.odexa.config.TargetConfig;

@Timeout(15)
class TokenProviderTest {
  private static final String SECRET = "synthetic-auth-secret";
  private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
  private final List<String> forms = new CopyOnWriteArrayList<>();
  private final MutableClock clock = new MutableClock();
  private HttpServer server;
  private ExecutorService handlers;
  private TargetConfig config;
  private TokenProvider provider;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    handlers = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(handlers);
    server.createContext(
        "/token",
        exchange -> {
          forms.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          Reply reply = replies.poll();
          if (reply == null) {
            reply = new Reply(500, "{}");
          }
          byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(reply.status(), bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
    config = config("/token");
    provider =
        new TokenProvider(
            new AuthClient(config), config.credentials(Actor.CUSTOMER_A), clock, Actor.CUSTOMER_A);
  }

  @AfterEach
  void stop() {
    server.stop(0);
    handlers.shutdownNow();
  }

  @Test
  void cachesUntilProactiveRefreshBoundaryUsingMutableClock() {
    replies.add(token("access-one", "refresh-one"));
    replies.add(token("access-two", "refresh-two"));
    assertEquals("access-one", provider.accessToken());
    clock.advance(Duration.ofSeconds(89));
    assertEquals("access-one", provider.accessToken());
    assertEquals(1, forms.size());
    clock.advance(Duration.ofSeconds(1));
    assertEquals("access-two", provider.accessToken());
    assertEquals(2, forms.size());
    assertTrue(forms.get(0).contains("grant_type=password"));
    assertTrue(forms.get(1).contains("grant_type=refresh_token"));
    assertTrue(forms.get(1).contains("refresh_token=refresh-one"));
    assertFalse(forms.get(1).contains("password="));
  }

  @Test
  void parallelCallersShareOneGrantAndOneRefresh() throws Exception {
    replies.add(token("access-one", "refresh-one"));
    assertParallelToken("access-one");
    assertEquals(1, forms.size());
    replies.add(token("access-two", "refresh-two"));
    clock.advance(Duration.ofSeconds(100));
    assertParallelToken("access-two");
    assertEquals(2, forms.size());
  }

  @Test
  void refreshResponseWithoutRotationRetainsExistingRefreshToken() {
    replies.add(token("access-one", "refresh-one"));
    replies.add(
        new Reply(
            200, "{\"access_token\":\"access-two\",\"token_type\":\"Bearer\",\"expires_in\":100}"));
    replies.add(token("access-three", "refresh-three"));
    provider.accessToken();
    clock.advance(Duration.ofSeconds(100));
    assertEquals("access-two", provider.accessToken());
    clock.advance(Duration.ofSeconds(100));
    assertEquals("access-three", provider.accessToken());
    assertEquals(3, forms.size());
    assertTrue(forms.get(2).contains("grant_type=refresh_token"));
    assertTrue(forms.get(2).contains("refresh_token=refresh-one"));
  }

  @Test
  void oldRequestCannotInvalidateNewerToken() {
    replies.add(token("access-one", "refresh-one"));
    replies.add(token("access-two", "refresh-two"));
    AuthenticatedSession session = new AuthenticatedSession(Actor.CUSTOMER_A, provider);
    String previous = session.accessToken();
    session.invalidate(previous);
    assertEquals("access-two", session.accessToken());
    session.invalidate(previous);
    assertEquals("access-two", session.accessToken());
    assertEquals(2, forms.size());
    assertEquals(Actor.CUSTOMER_A, session.actor());
  }

  @Test
  void invalidRefreshGrantReauthenticatesExactlyOnce() {
    replies.add(token("access-one", "refresh-one"));
    replies.add(
        new Reply(400, "{\"error\":\"invalid_grant\",\"error_description\":\"" + SECRET + "\"}"));
    replies.add(token("access-two", "refresh-two"));
    provider.accessToken();
    clock.advance(Duration.ofSeconds(100));
    assertEquals("access-two", provider.accessToken());
    assertEquals(3, forms.size());
    assertTrue(forms.get(2).startsWith("grant_type=password"));
  }

  @Test
  void invalidClientNeverFallsBackAndSubsequentCallsDoNotRetry() {
    replies.add(token("access-one", "refresh-one"));
    replies.add(new Reply(401, "{\"error\":\"invalid_client\",\"echo\":\"" + SECRET + "\"}"));
    provider.accessToken();
    clock.advance(Duration.ofSeconds(100));
    for (int i = 0; i < 3; i++) {
      AuthException failure = assertThrows(AuthException.class, provider::accessToken);
      assertEquals(AuthException.Classification.INVALID_CLIENT, failure.classification());
      assertEquals(401, failure.status());
      assertSafe(failure);
    }
    assertEquals(2, forms.size());
  }

  @Test
  void wrongPasswordAndFailedFallbackAreBoundedAndSanitized() {
    replies.add(token("access-one", "refresh-one"));
    replies.add(new Reply(400, "{\"error\":\"invalid_grant\"}"));
    replies.add(new Reply(400, "{\"error\":\"invalid_grant\",\"echo\":\"" + SECRET + "\"}"));
    provider.accessToken();
    clock.advance(Duration.ofSeconds(100));
    assertSafe(assertThrows(AuthException.class, provider::accessToken));
    assertSafe(assertThrows(AuthException.class, provider::accessToken));
    assertEquals(3, forms.size());
  }

  @Test
  void malformedTokenJsonAndInvalidTokensNeverLeakParserCauses() {
    for (String body :
        List.of(
            "{\"access_token\":\"" + SECRET + "\",broken",
            "{\"access_token\":\"" + SECRET + "\",\"expires_in\":100}",
            "{\"access_token\":\"bad token\",\"token_type\":\"Bearer\",\"expires_in\":100}",
            "{\"access_token\":\"" + SECRET + "\",\"token_type\":\"Bearer\",\"expires_in\":-1}")) {
      replies.add(new Reply(200, body));
      AuthException failure = assertThrows(AuthException.class, provider::accessToken);
      assertEquals(AuthException.Classification.INVALID_RESPONSE, failure.classification());
      assertSafe(failure);
    }
  }

  @Test
  void diagnosticRepresentationsDoNotExposeCredentialsOrCachedTokens() {
    replies.add(token(SECRET, "refresh-" + SECRET));
    AuthClient client = new AuthClient(config);
    AuthClient.Token token =
        client.authenticate(config.credentials(Actor.CUSTOMER_A), Actor.CUSTOMER_A);
    replies.add(token(SECRET, "refresh-" + SECRET));
    provider.accessToken();
    for (Object object :
        List.of(
            token,
            client,
            config,
            config.credentials(Actor.CUSTOMER_A),
            provider,
            new AuthenticatedSession(Actor.CUSTOMER_A, provider))) {
      assertFalse(object.toString().contains(SECRET));
      assertFalse(object.toString().contains("customer-a"));
    }
  }

  @Test
  void separateActorProvidersNeverShareTokenState() {
    replies.add(token("actor-a-token", "refresh-a"));
    replies.add(token("actor-b-token", "refresh-b"));
    TokenProvider other =
        new TokenProvider(
            new AuthClient(config), config.credentials(Actor.CUSTOMER_B), clock, Actor.CUSTOMER_B);
    assertEquals("actor-a-token", provider.accessToken());
    assertEquals("actor-b-token", other.accessToken());
    assertEquals("actor-a-token", provider.accessToken());
    assertEquals(2, forms.size());
  }

  @Test
  void redirectsAreNotFollowedInTheAuthChannel() {
    AtomicInteger redirected = new AtomicInteger();
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/sink");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/sink",
        exchange -> {
          redirected.incrementAndGet();
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    TargetConfig redirectConfig = config("/redirect");
    TokenProvider redirectProvider =
        new TokenProvider(
            new AuthClient(redirectConfig), redirectConfig.credentials(Actor.CUSTOMER_A), clock);
    AuthException failure = assertThrows(AuthException.class, redirectProvider::accessToken);
    assertEquals(302, failure.status());
    assertSafe(failure);
    assertEquals(0, redirected.get());
  }

  @Test
  void authenticationTimeoutIsBoundedAndTransportFailureSanitized() {
    CountDownLatch release = new CountDownLatch(1);
    server.createContext(
        "/slow",
        exchange -> {
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    TargetConfig slowConfig = config("/slow");
    TokenProvider slow =
        new TokenProvider(
            new AuthClient(slowConfig), slowConfig.credentials(Actor.CUSTOMER_A), clock);
    try {
      AuthException failure = assertThrows(AuthException.class, slow::accessToken);
      assertEquals(AuthException.Classification.TRANSPORT, failure.classification());
      assertSafe(failure);
    } finally {
      release.countDown();
    }
  }

  private TargetConfig config(String path) {
    return TargetConfig.load(
        new Properties(),
        Map.of(
            "ODEXA_FIXTURE_PASSWORD",
            SECRET,
            "ODEXA_CLIENT_SECRET",
            SECRET,
            "ODEXA_TIMEOUT_SECONDS",
            "1",
            "ODEXA_TOKEN_URL",
            "http://127.0.0.1:" + server.getAddress().getPort() + path));
  }

  private void assertParallelToken(String expected) throws Exception {
    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < 24; i++) {
        futures.add(
            workers.submit(
                () -> {
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  return provider.accessToken();
                }));
      }
      start.countDown();
      for (Future<String> future : futures) {
        assertEquals(expected, future.get(5, TimeUnit.SECONDS));
      }
    }
  }

  private static Reply token(String access, String refresh) {
    return new Reply(
        200,
        "{\"access_token\":\""
            + access
            + "\",\"refresh_token\":\""
            + refresh
            + "\",\"token_type\":\"Bearer\",\"expires_in\":100}");
  }

  private static void assertSafe(AuthException failure) {
    assertNull(failure.getCause());
    assertEquals(0, failure.getSuppressed().length);
    StringWriter stack = new StringWriter();
    failure.printStackTrace(new PrintWriter(stack));
    assertFalse(stack.toString().contains(SECRET));
    assertFalse(stack.toString().contains("http://"));
    assertFalse(stack.toString().contains("customer-a"));
  }

  private record Reply(int status, String body) {
    @Override
    public String toString() {
      return "Reply[status=" + status + "]";
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    void advance(Duration duration) {
      now.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
