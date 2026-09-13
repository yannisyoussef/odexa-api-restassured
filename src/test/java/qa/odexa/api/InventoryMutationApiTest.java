package qa.odexa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static qa.odexa.config.Actor.CUSTOMER_A;
import static qa.odexa.config.Actor.MERCHANT_A;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import qa.odexa.client.InventoryClient;
import qa.odexa.client.OrderClient;
import qa.odexa.fixture.StockFixture;
import qa.odexa.http.ApiResponse;
import qa.odexa.model.Inventory;

@Tag("api")
@Tag("mutation")
@Tag("inventory")
@Tag("optimistic-locking")
@Epic("Odexa")
@Feature("Inventory optimistic preconditions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(value = "odexa:tenant-a-inventory", mode = ResourceAccessMode.READ_WRITE)
class InventoryMutationApiTest {
  private ApiContext context;
  private InventoryClient merchantInventory;
  private OrderClient customerOrders;

  @BeforeAll
  void setUpMerchantAndCustomerCleanup() {
    context = ApiContext.load(MERCHANT_A, CUSTOMER_A);
    context.requireMutation();
    merchantInventory = context.actor(MERCHANT_A).inventory();
    customerOrders = context.actor(CUSTOMER_A).orders();
  }

  @Test
  @Story("Successful adjustment advances a strong ETag and rejects the previous version")
  void successfulFixtureAdjustmentMakesTheOriginalEtagStale() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 3)) {
      ApiResponse setup = fixture.setupResponse();
      setup.response().then().statusCode(200).contentType(ContentType.JSON);
      Inventory adjusted = ApiChecks.inventory(setup, context.config().productId());
      assertThat(adjusted.onHand()).as("merchant set absolute stock").isEqualTo(3);
      assertThat(adjusted.reserved()).isZero();
      // Comparing to the previous quoted version avoids parsing arbitrary header text.
      assertThat(fixture.originalEtag().equals("\"" + (adjusted.version() - 1) + "\""))
          .as("successful adjustment increments version exactly once")
          .isTrue();

      ApiResponse stale =
          merchantInventory.adjust(context.config().productId(), 3, fixture.originalEtag());
      stale.response().then().statusCode(412).contentType("application/problem+json");
      ApiChecks.problem(stale, 412, "STALE_VERSION");
      context.awaitInventory(merchantInventory, 3, 0, adjusted.version());
    }
  }

  @Test
  @Story("An inventory adjustment must include If-Match")
  void missingIfMatchReturnsPreconditionRequiredWithoutAdjustment() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 3)) {
      ApiResponse response = merchantInventory.adjust(context.config().productId(), 3, null);
      response.response().then().statusCode(428).contentType("application/problem+json");
      ApiChecks.problem(response, 428, "PRECONDITION_REQUIRED");
      context.awaitInventory(
          merchantInventory, 3, 0, fixture.setupResponse().as(Inventory.class).version());
    }
  }
}
