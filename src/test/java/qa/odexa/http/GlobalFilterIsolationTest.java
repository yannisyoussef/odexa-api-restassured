package qa.odexa.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.Method;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import qa.odexa.client.UnitHttpServer;

@Isolated("Exercises third-party global Rest Assured contamination")
class GlobalFilterIsolationTest {
  @Test
  void executableRequestCannotInheritMutableRestAssuredDefaults() throws Exception {
    var previousFilters = List.copyOf(RestAssured.filters());
    var previousRequestSpec = RestAssured.requestSpecification;
    var previousResponseSpec = RestAssured.responseSpecification;
    var previousAuthentication = RestAssured.authentication;
    var previousProxy = RestAssured.proxy;
    var filterInvocations = new AtomicInteger();
    var proxyInvocations = new AtomicInteger();
    var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    proxy.createContext(
        "/",
        exchange -> {
          proxyInvocations.incrementAndGet();
          exchange.sendResponseHeaders(502, -1);
          exchange.close();
        });
    proxy.start();
    try (var server = new UnitHttpServer(request -> UnitHttpServer.Reply.raw(200, "{}"))) {
      RestAssured.replaceFiltersWith(List.of());
      RestAssured.requestSpecification = null;
      RestAssured.responseSpecification = null;
      RestAssured.authentication = RestAssured.DEFAULT_AUTH;
      RestAssured.proxy = null;
      RestAssured.requestSpecification =
          new RequestSpecBuilder()
              .addHeader("X-Global", "inherited")
              .addCookie("global-cookie", "inherited")
              .addQueryParam("global-param", "inherited")
              .build();
      RestAssured.responseSpecification = new ResponseSpecBuilder().expectStatusCode(418).build();
      RestAssured.authentication = RestAssured.preemptive().basic("global-user", "global-password");
      RestAssured.proxy("127.0.0.1", proxy.getAddress().getPort());
      RestAssured.filters(
          (request, response, context) -> {
            filterInvocations.incrementAndGet();
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
      var unauthenticated =
          server.http().execute(null, Method.GET, "/api/v1/products", Map.of(), Map.of(), null);

      assertEquals(200, response.status(), "Global response specs must not be applied");
      assertEquals(200, unauthenticated.status(), "Global response specs must not be applied");
      assertEquals(0, filterInvocations.get(), "Global filters must not observe requests");
      assertEquals(0, proxyInvocations.get(), "Global proxies must not receive requests");
      assertEquals(2, server.requests().size());
      var request = server.requests().getLast();
      assertNull(request.header("X-Global"));
      assertNull(request.header("Cookie"));
      assertNull(request.header("Authorization"));
      assertNull(request.query());
    } finally {
      proxy.stop(0);
      RestAssured.replaceFiltersWith(previousFilters);
      RestAssured.requestSpecification = previousRequestSpec;
      RestAssured.responseSpecification = previousResponseSpec;
      RestAssured.authentication = previousAuthentication;
      RestAssured.proxy = previousProxy;
    }
  }
}
