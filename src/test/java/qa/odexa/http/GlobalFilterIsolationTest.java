package qa.odexa.http;

import static org.junit.jupiter.api.Assertions.*;

import io.restassured.RestAssured;
import io.restassured.http.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import qa.odexa.client.UnitHttpServer;

@Isolated("Exercises third-party global Rest Assured filter contamination")
class GlobalFilterIsolationTest {
  @Test
  void executableRequestCannotInheritCredentialLoggingFilters() throws Exception {
    var previous = List.copyOf(RestAssured.filters());
    var invocations = new AtomicInteger();
    try (var server = new UnitHttpServer(request -> UnitHttpServer.Reply.raw(200, "{}"))) {
      RestAssured.filters(
          (request, response, context) -> {
            invocations.incrementAndGet();
            return context.next(request, response);
          });
      var response =
          server
              .http()
              .execute(
                  null,
                  Method.GET,
                  "/api/v1/products",
                  Map.of(),
                  Map.of("Authorization", "Bearer unit-regression-only"),
                  null);
      assertEquals(200, response.status());
      assertEquals(0, invocations.get(), "Global filters must not see credential-bearing requests");
      assertNotNull(server.requests().getFirst().header("Authorization"));
    } finally {
      RestAssured.replaceFiltersWith(previous);
    }
  }
}
