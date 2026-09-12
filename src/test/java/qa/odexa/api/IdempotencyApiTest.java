package qa.odexa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static qa.odexa.assertion.OrderAssert.assertThatOrder;
import static qa.odexa.config.Actor.CUSTOMER_A;
import static qa.odexa.config.Actor.MERCHANT_A;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.http.ContentType;
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
import qa.odexa.model.Order;
import qa.odexa.wait.OrderAwaiter;

@Tag("api")
@Tag("mutation")
@Tag("order")
@Tag("idempotency")
@Epic("Odexa")
@Feature("Checkout idempotency")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(value = "odexa:tenant-a-inventory", mode = ResourceAccessMode.READ_WRITE)
class IdempotencyApiTest {
  private ApiContext context;
  private InventoryClient merchantInventory;
  private OrderClient customerOrders;
  private OrderAwaiter orders;

  @BeforeAll
  void setUpCustomerAndMerchant() {
    context = ApiContext.load(CUSTOMER_A, MERCHANT_A);
    context.requireMutation();
    merchantInventory = context.actor(MERCHANT_A).inventory();
    customerOrders = context.actor(CUSTOMER_A).orders();
    orders = new OrderAwaiter(customerOrders, context.config());
  }

  @Test
  @Story("Exact retry returns the same order and Location without consuming twice")
  void exactRetryReturns200AndTheOriginalOrder() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 3)) {
      String key = UUID.randomUUID().toString();
      OrderRequest request = OrderRequest.forProduct(context.config().productId());
      ApiResponse created = customerOrders.create(request, key);
      fixture.track(created);
      created.response().then().statusCode(201).contentType(ContentType.JSON);
      Order original = ApiChecks.acceptedOrder(created, context.config().productId(), 1);

      ApiResponse replay = customerOrders.create(request, key);
      fixture.track(replay);
      replay.response().then().statusCode(200).contentType(ContentType.JSON);
      Order repeated = ApiChecks.acceptedOrder(replay, context.config().productId(), 1);
      assertThat(repeated.id()).as("idempotent order identity").isEqualTo(original.id());
      assertThat(created.header("Location").equals(replay.header("Location")))
          .as("replayed Location is unchanged")
          .isTrue();
      assertThat(repeated.totalMinor())
          .as("replayed price snapshot")
          .isEqualTo(original.totalMinor());
      assertThat(repeated.createdAt())
          .as("replayed creation instant")
          .isEqualTo(original.createdAt());
      assertThatOrder(orders.untilStatus(original.id(), "CONFIRMED"))
          .hasStatus("CONFIRMED")
          .hasQuantity(1);
      context.awaitInventory(
          merchantInventory, 2, 0, fixture.setupResponse().as(Inventory.class).version() + 2);
    }
  }

  @Test
  @Story("Reusing a key for a different quantity rejects the new intent")
  void changedQuantityReturnsIdempotencyConflict() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 3)) {
      String key = UUID.randomUUID().toString();
      OrderRequest request = OrderRequest.forProduct(context.config().productId());
      ApiResponse created = customerOrders.create(request, key);
      fixture.track(created);
      created.response().then().statusCode(201).contentType(ContentType.JSON);
      Order original = ApiChecks.acceptedOrder(created, context.config().productId(), 1);

      ApiResponse conflict = customerOrders.create(request.withQuantity(2), key);
      fixture.track(conflict);
      conflict.response().then().statusCode(409).contentType("application/problem+json");
      ApiChecks.problem(conflict, 409, "IDEMPOTENCY_CONFLICT");
      Order terminal = orders.untilStatus(original.id(), "CONFIRMED");
      assertThatOrder(terminal).hasStatus("CONFIRMED").hasQuantity(1);
      assertThat(terminal.totalMinor())
          .as("conflict leaves original total intact")
          .isEqualTo(original.totalMinor());
      context.awaitInventory(
          merchantInventory, 2, 0, fixture.setupResponse().as(Inventory.class).version() + 2);
    }
  }
}
