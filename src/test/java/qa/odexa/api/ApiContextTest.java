package qa.odexa.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static qa.odexa.client.UnitHttpServer.PRODUCT;
import static qa.odexa.config.Actor.CUSTOMER_A;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import qa.odexa.client.InventoryClient;
import qa.odexa.client.OrderClient;
import qa.odexa.client.UnitHttpServer;
import qa.odexa.config.TargetConfig;
import qa.odexa.fixture.StockFixture;
import qa.odexa.http.ApiHttp;
import qa.odexa.model.Inventory;

class ApiContextTest {
  private static final UUID OTHER_PRODUCT = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Test
  void uncertainFixtureStopsIndependentContextsOnlyForTheSameTargetAndProduct() throws Exception {
    try (UnitHttpServer affected = new UnitHttpServer(ApiContextTest::failSetup);
        UnitHttpServer otherTarget =
            new UnitHttpServer(request -> UnitHttpServer.Reply.raw(404, "{}"))) {
      TargetConfig affectedConfig = affected.config(true, true);
      ApiContext first = ApiContext.forConfig(affectedConfig, CUSTOMER_A);
      ApiContext alreadyCreated = ApiContext.forConfig(affected.config(true, true), CUSTOMER_A);
      ApiContext differentProduct =
          ApiContext.forConfig(affected.config(true, true, OTHER_PRODUCT), CUSTOMER_A);
      ApiContext differentTarget = ApiContext.forConfig(otherTarget.config(true, true), CUSTOMER_A);

      assertDoesNotThrow(first::requireMutation);
      ApiHttp fixtureHttp = new ApiHttp(affectedConfig);
      assertThrows(
          IllegalStateException.class,
          () ->
              StockFixture.acquire(
                  affectedConfig,
                  new InventoryClient(fixtureHttp, null),
                  new OrderClient(fixtureHttp, null),
                  5));

      int requestsAfterFailure = affected.requests().size();
      assertThrows(TestAbortedException.class, alreadyCreated::requireMutation);
      assertThrows(
          IllegalStateException.class,
          () -> alreadyCreated.actor(CUSTOMER_A).orders().create(Map.of(), "blocked"));
      assertThrows(
          TestAbortedException.class,
          () -> ApiContext.forConfig(affected.config(true, true)).requireMutation());
      assertEquals(requestsAfterFailure, affected.requests().size());
      assertDoesNotThrow(differentProduct::requireMutation);
      assertDoesNotThrow(differentTarget::requireMutation);
    }
  }

  private static UnitHttpServer.Reply failSetup(UnitHttpServer.Request request) {
    if (request.path().equals("/api/v1/inventory/" + PRODUCT) && request.method().equals("GET")) {
      return UnitHttpServer.Reply.json(200, new Inventory(PRODUCT, 20, 0, 20, 10))
          .header("ETag", "\"10\"");
    }
    if (request.path().equals("/api/v1/inventory/" + PRODUCT) && request.method().equals("PUT")) {
      return UnitHttpServer.Reply.raw(503, "{}");
    }
    return UnitHttpServer.Reply.raw(404, "{}");
  }
}
