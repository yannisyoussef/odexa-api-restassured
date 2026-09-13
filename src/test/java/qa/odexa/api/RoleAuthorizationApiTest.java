package qa.odexa.api;

import static qa.odexa.config.Actor.CUSTOMER_A;
import static qa.odexa.config.Actor.MERCHANT_A;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import qa.odexa.client.InventoryClient;
import qa.odexa.client.OrderClient;
import qa.odexa.data.OrderRequest;
import qa.odexa.fixture.StockFixture;
import qa.odexa.http.ApiResponse;
import qa.odexa.model.Inventory;

@Tag("api")
@Tag("mutation")
@Tag("authorization")
@Epic("Odexa")
@Feature("Role boundaries")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(value = "odexa:tenant-a-inventory", mode = ResourceAccessMode.READ_WRITE)
class RoleAuthorizationApiTest {
  private ApiContext context;
  private InventoryClient merchantInventory;
  private OrderClient customerOrders;

  @BeforeAll
  void setUpCustomerAndMerchant() {
    context = ApiContext.load(CUSTOMER_A, MERCHANT_A);
    context.requireMutation();
    merchantInventory = context.actor(MERCHANT_A).inventory();
    customerOrders = context.actor(CUSTOMER_A).orders();
  }

  @Test
  @Tag("order")
  @Story("Merchant administration does not confer customer checkout rights")
  void merchantCannotCreateCustomerOrder() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 1)) {
      ApiResponse response =
          context
              .actor(MERCHANT_A)
              .orders()
              .create(
                  OrderRequest.forProduct(context.config().productId()),
                  UUID.randomUUID().toString());
      fixture.track(response);
      response.response().then().statusCode(403).contentType("application/problem+json");
      ApiChecks.problem(response, 403);
      context.awaitInventory(
          merchantInventory, 1, 0, fixture.setupResponse().as(Inventory.class).version());
    }
  }

  @Test
  @Tag("inventory")
  @Story("A customer cannot adjust stock even with a current version")
  void customerCannotAdjustInventory() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 1)) {
      ApiResponse response =
          context
              .actor(CUSTOMER_A)
              .inventory()
              .adjust(context.config().productId(), 1, fixture.setupResponse().header("ETag"));
      response.response().then().statusCode(403).contentType("application/problem+json");
      ApiChecks.problem(response, 403);
      context.awaitInventory(
          merchantInventory, 1, 0, fixture.setupResponse().as(Inventory.class).version());
    }
  }
}
