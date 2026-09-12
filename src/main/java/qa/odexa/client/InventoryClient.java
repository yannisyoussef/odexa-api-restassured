package qa.odexa.client;

import io.restassured.http.Method;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import qa.odexa.auth.AuthenticatedSession;
import qa.odexa.http.ApiHttp;
import qa.odexa.http.ApiResponse;

/** The public adjustment is an absolute on-hand value, not a delta. */
public final class InventoryClient {
  private final ApiHttp http;
  private final AuthenticatedSession session;

  public InventoryClient(ApiHttp http, AuthenticatedSession session) {
    this.http = Objects.requireNonNull(http, "http");
    this.session = session;
  }

  public ApiResponse get(UUID productId) {
    return http.execute(session, Method.GET, path(productId), Map.of(), Map.of(), null);
  }

  public ApiResponse adjust(UUID productId, long onHand, String etag) {
    return http.execute(
        session,
        Method.PUT,
        path(productId),
        Map.of(),
        etag == null ? Map.of() : Map.of("If-Match", etag),
        Map.of("onHand", onHand));
  }

  private static String path(UUID productId) {
    return "/api/v1/inventory/" + Objects.requireNonNull(productId, "productId");
  }
}
