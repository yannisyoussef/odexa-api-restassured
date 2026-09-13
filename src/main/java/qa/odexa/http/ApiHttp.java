package qa.odexa.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.restassured.authentication.NoAuthScheme;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.Method;
import io.restassured.internal.RequestSpecificationImpl;
import io.restassured.internal.ResponseParserRegistrar;
import io.restassured.internal.ResponseSpecificationImpl;
import io.restassured.internal.TestSpecificationImpl;
import io.restassured.internal.log.LogRepository;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.http.impl.client.DefaultHttpClient;
import qa.odexa.auth.AuthException;
import qa.odexa.auth.AuthenticatedSession;
import qa.odexa.config.Actor;
import qa.odexa.config.TargetConfig;
import qa.odexa.diagnostics.SafeDiagnostics;
import qa.odexa.diagnostics.SafeMetadataFilter;

/** Stateless gateway transport. Every invocation owns its spec, correlation ID and HTTP client. */
public final class ApiHttp {
  private static final String CORRELATION_HEADER = "X-Correlation-ID";
  private final TargetConfig config;
  private final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public ApiHttp(TargetConfig config) {
    this.config = config;
  }

  public ApiResponse execute(
      AuthenticatedSession sessionOrNull,
      Method method,
      String path,
      Map<String, ?> query,
      Map<String, String> headers,
      Object bodyOrNull) {
    Actor actor = sessionOrNull == null ? null : sessionOrNull.actor();
    String correlation = UUID.randomUUID().toString();
    try {
      validatePath(path);
      if (method == null) {
        throw new ApiException("HTTP method is required");
      }
      if (headers != null) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
          validateHeader(header.getKey(), header.getValue());
          if (CORRELATION_HEADER.equalsIgnoreCase(header.getKey())) {
            String candidate = SafeDiagnostics.correlation(header.getValue());
            if (!candidate.equals("UNAVAILABLE")) {
              correlation = candidate;
            }
          }
        }
      }
      int timeoutMillis = Math.toIntExact(config.requestTimeout().toMillis());
      RestAssuredConfig requestConfig =
          RestAssuredConfig.config()
              .redirect(RedirectConfig.redirectConfig().followRedirects(false))
              .httpClient(
                  HttpClientConfig.httpClientConfig()
                      .httpClientFactory(ApiHttp::noRetryClient)
                      .setParam("http.connection.timeout", timeoutMillis)
                      .setParam("http.socket.timeout", timeoutMillis)
                      .setParam("http.connection-manager.timeout", (long) timeoutMillis));
      int port =
          config.baseUri().getPort() >= 0
              ? config.baseUri().getPort()
              : ("https".equalsIgnoreCase(config.baseUri().getScheme()) ? 443 : 80);
      LogRepository logRepository = new LogRepository();
      RequestSpecification request =
          new RequestSpecificationImpl(
                  config.baseUri().toString(),
                  port,
                  "",
                  new NoAuthScheme(),
                  List.of(),
                  null,
                  true,
                  requestConfig,
                  logRepository,
                  null,
                  true,
                  false)
              .contentType("application/json")
              .accept("application/json");
      ResponseSpecification responseSpec =
          new ResponseSpecificationImpl(
              "", null, new ResponseParserRegistrar(), requestConfig, logRepository);
      if (query != null && !query.isEmpty()) {
        request.queryParams(query);
      }
      if (headers != null) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
          if (!CORRELATION_HEADER.equalsIgnoreCase(header.getKey())
              && !(sessionOrNull != null && "Authorization".equalsIgnoreCase(header.getKey()))) {
            request.header(header.getKey(), header.getValue());
          }
        }
      }
      request.header(CORRELATION_HEADER, correlation);
      if (sessionOrNull != null) {
        request.header("Authorization", "Bearer " + sessionOrNull.accessToken());
      }
      if (bodyOrNull != null) {
        request.body(
            bodyOrNull instanceof String raw ? raw : mapper.writeValueAsString(bodyOrNull));
      }
      // No reactive retries: a network failure on a write has an ambiguous outcome.
      request.filter(
          new SafeMetadataFilter(method.name(), path, actor, correlation, config.verbose()));
      // Wire the two detached specs directly; RestAssured's builders snapshot mutable globals.
      Response response = new TestSpecificationImpl(request, responseSpec).request(method, path);
      return new ApiResponse(response, correlation);
    } catch (AuthException safe) {
      throw safe;
    } catch (Exception ignored) {
      String safeMethod = method == null ? "UNKNOWN" : method.name();
      SafeDiagnostics.attach(safeMethod, path, actor, 0, correlation, config.verbose());
      throw new ApiException(
          "HTTP execution failed: "
              + SafeDiagnostics.metadata(safeMethod, path, actor, 0, correlation));
    }
  }

  private static void validatePath(String path) {
    // Public routes contain only literal segments/UUIDs. Query values belong in the query map.
    if (path == null
        || !path.startsWith("/api/v1/")
        || !path.matches("/[A-Za-z0-9_/-]+")
        || path.contains("//")) {
      throw new ApiException("Only relative /api/v1/ routes are permitted");
    }
  }

  private static void validateHeader(String name, String value) {
    if (name == null
        || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        || value == null
        || value.chars().anyMatch(c -> c < 32 || c == 127)
        || "Host".equalsIgnoreCase(name)
        || "Content-Length".equalsIgnoreCase(name)
        || "Transfer-Encoding".equalsIgnoreCase(name)) {
      throw new ApiException("Invalid HTTP header");
    }
  }

  @SuppressWarnings(
      "deprecation") // RA 6 requires AbstractHttpClient rather than HttpClientBuilder.
  private static DefaultHttpClient noRetryClient() {
    DefaultHttpClient client = new DefaultHttpClient();
    client.setHttpRequestRetryHandler((failure, executionCount, context) -> false);
    return client;
  }

  @Override
  public String toString() {
    return "ApiHttp[mode=" + config.mode() + "]";
  }
}
