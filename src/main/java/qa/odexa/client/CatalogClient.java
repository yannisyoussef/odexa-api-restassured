package qa.odexa.client;

import io.restassured.http.Method;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import qa.odexa.auth.AuthenticatedSession;
import qa.odexa.http.ApiHttp;
import qa.odexa.http.ApiResponse;

/** Gateway operations only; response/business expectations belong to the caller. */
public final class CatalogClient {
  private final ApiHttp http;
  private final AuthenticatedSession session;

  public CatalogClient(ApiHttp http, AuthenticatedSession session) {
    this.http = Objects.requireNonNull(http, "http");
    this.session = session;
  }

  public ApiResponse get(UUID id) {
    return get(id, Map.of());
  }

  public ApiResponse get(UUID id, Map<String, String> headers) {
    return http.execute(session, Method.GET, path(id), Map.of(), headers, null);
  }

  public ApiResponse list(Map<String, ?> query) {
    return http.execute(session, Method.GET, "/api/v1/products", query, Map.of(), null);
  }

  public ApiResponse create(Object body) {
    return http.execute(session, Method.POST, "/api/v1/products", Map.of(), Map.of(), body);
  }

  public ApiResponse replace(UUID id, Object body, String etag) {
    return http.execute(
        session,
        Method.PUT,
        path(id),
        Map.of(),
        etag == null ? Map.of() : Map.of("If-Match", etag),
        body);
  }

  private static String path(UUID id) {
    return "/api/v1/products/" + Objects.requireNonNull(id, "id");
  }
}
