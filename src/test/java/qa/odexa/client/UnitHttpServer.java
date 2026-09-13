package qa.odexa.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import qa.odexa.config.TargetConfig;
import qa.odexa.http.ApiHttp;

/**
 * Isolated loopback public-HTTP double; never reaches a product service or an identity provider.
 */
public final class UnitHttpServer implements AutoCloseable {
  public static final UUID PRODUCT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  public static final String CORRELATION = "12345678-1234-4234-8234-123456789abc";
  private static final ObjectMapper JSON =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final Function<Request, Reply> handler;

  public UnitHttpServer(Function<Request, Reply> handler) throws IOException {
    this.handler = handler;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  public TargetConfig config(boolean allowMutation, boolean exclusiveFixtures) {
    return config(allowMutation, exclusiveFixtures, PRODUCT);
  }

  public TargetConfig config(boolean allowMutation, boolean exclusiveFixtures, UUID productId) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("ODEXA_ENV", "local");
    env.put("ODEXA_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
    env.put("ODEXA_TOKEN_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    env.put("ODEXA_FIXTURE_PASSWORD", "unit-test-placeholder");
    env.put("ODEXA_PRODUCT_ID", productId.toString());
    env.put("ODEXA_ALLOW_MUTATION", Boolean.toString(allowMutation));
    env.put("ODEXA_EXCLUSIVE_FIXTURES", Boolean.toString(exclusiveFixtures));
    env.put("ODEXA_TIMEOUT_SECONDS", "2");
    // Allow cold Rest Assured/Groovy initialization under parallel CI without a timing race.
    env.put("ODEXA_POLL_TIMEOUT_SECONDS", "3");
    env.put("ODEXA_POLL_INTERVAL_MILLIS", "50");
    return TargetConfig.load(new Properties(), env);
  }

  public ApiHttp http() {
    return new ApiHttp(config(false, false));
  }

  public List<Request> requests() {
    return List.copyOf(requests);
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      byte[] bytes = exchange.getRequestBody().readNBytes(65536);
      Map<String, String> headers = new LinkedHashMap<>();
      exchange.getRequestHeaders().forEach((key, values) -> headers.put(key, values.getFirst()));
      Request request =
          new Request(
              exchange.getRequestMethod(),
              exchange.getRequestURI().getPath(),
              exchange.getRequestURI().getRawQuery(),
              Map.copyOf(headers),
              new String(bytes, StandardCharsets.UTF_8));
      requests.add(request);
      Reply reply = handler.apply(request);
      // Status zero is a test-only connection drop, exercising ambiguous transport writes.
      if (reply.status() == 0) {
        return;
      }
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.getResponseHeaders().set("X-Correlation-ID", CORRELATION);
      reply.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
      byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(reply.status(), reply.status() == 304 ? -1 : body.length);
      if (reply.status() != 304) {
        exchange.getResponseBody().write(body);
      }
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  public record Request(
      String method, String path, String query, Map<String, String> headers, String body) {
    public String header(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse(null);
    }

    public JsonNode json() throws IOException {
      return JSON.readTree(body);
    }

    @Override
    public String toString() {
      return "Request[method=" + method + "]";
    }
  }

  public record Reply(int status, String body, Map<String, String> headers) {
    public static Reply json(int status, Object body) {
      try {
        return raw(status, JSON.writeValueAsString(body));
      } catch (IOException ignored) {
        throw new IllegalArgumentException("Invalid unit-test response data");
      }
    }

    public static Reply raw(int status, String body) {
      return new Reply(status, body, Map.of());
    }

    public Reply header(String name, String value) {
      Map<String, String> updated = new LinkedHashMap<>(headers);
      updated.put(name, value);
      return new Reply(status, body, Map.copyOf(updated));
    }

    @Override
    public String toString() {
      return "Reply[status=" + status + "]";
    }
  }
}
