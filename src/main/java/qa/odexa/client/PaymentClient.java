package qa.odexa.client;

import io.restassured.http.Method;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import qa.odexa.auth.AuthenticatedSession;
import qa.odexa.http.ApiHttp;
import qa.odexa.http.ApiResponse;

/** Payments are read by order ID; v0.1.0 exposes no payment creation operation. */
public final class PaymentClient {
  private final ApiHttp http;
  private final AuthenticatedSession session;

  public PaymentClient(ApiHttp http, AuthenticatedSession session) {
    this.http = Objects.requireNonNull(http, "http");
    this.session = session;
  }

  public ApiResponse get(UUID orderId) {
    return http.execute(
        session,
        Method.GET,
        "/api/v1/payments/" + Objects.requireNonNull(orderId, "orderId"),
        Map.of(),
        Map.of(),
        null);
  }
}
