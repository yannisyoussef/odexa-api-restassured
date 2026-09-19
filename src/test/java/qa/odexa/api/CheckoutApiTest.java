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
import qa.odexa.client.PaymentClient;
import qa.odexa.data.OrderRequest;
import qa.odexa.fixture.StockFixture;
import qa.odexa.http.ApiResponse;
import qa.odexa.model.Inventory;
import qa.odexa.model.Order;
import qa.odexa.model.Product;
import qa.odexa.wait.OrderAwaiter;

@Tag("api")
@Tag("mutation")
@Tag("order")
@Epic("Odexa")
@Feature("Asynchronous checkout")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(value = "odexa:tenant-a-inventory", mode = ResourceAccessMode.READ_WRITE)
class CheckoutApiTest {
  private ApiContext context;
  private InventoryClient merchantInventory;
  private OrderClient customerOrders;
  private PaymentClient customerPayments;
  private OrderAwaiter orders;

  @BeforeAll
  void setUpCustomerAndMerchant() {
    context = ApiContext.load(CUSTOMER_A, MERCHANT_A);
    context.requireMutation();
    merchantInventory = context.actor(MERCHANT_A).inventory();
    customerOrders = context.actor(CUSTOMER_A).orders();
    customerPayments = context.actor(CUSTOMER_A).payments();
    orders = new OrderAwaiter(customerOrders, context.config());
  }

  @Test
  @Tag("payment")
  @Story("Approved checkout snapshots catalog price and consumes stock")
  void approvedPaymentConfirmsOrderAndCommitsOnlyItsQuantity() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 3)) {
      ApiResponse catalog = context.actor(CUSTOMER_A).catalog().get(context.config().productId());
      catalog.response().then().statusCode(200).contentType(ContentType.JSON);
      Product product = ApiChecks.product(catalog, context.config().productId());
      assertThat(product.active()).as("checkout fixture is active").isTrue();
      assertThat(product.unitPriceMinor()).as("checkout fixture has a positive price").isPositive();

      ApiResponse created =
          customerOrders.create(
              OrderRequest.forProduct(product.id()).withQuantity(2), UUID.randomUUID().toString());
      fixture.track(
          created); // Track before assertions so an assertion failure still settles the order.
      created.response().then().statusCode(201).contentType(ContentType.JSON);
      Order initial = ApiChecks.acceptedOrder(created, product.id(), 2);
      assertThat(initial.totalMinor())
          .as("authoritative catalog price multiplied by quantity")
          .isEqualTo(Math.multiplyExact(product.unitPriceMinor(), 2L));

      Order terminal = orders.untilStatus(initial.id(), "CONFIRMED");
      assertThatOrder(terminal).hasStatus("CONFIRMED").hasQuantity(2);
      ApiResponse read = customerOrders.get(initial.id());
      read.response().then().statusCode(200).contentType(ContentType.JSON);
      Order persisted = ApiChecks.order(read, product.id(), 2);
      assertThatOrder(persisted).hasStatus("CONFIRMED");
      assertThat(persisted.id()).isEqualTo(initial.id());
      assertThat(persisted.totalMinor())
          .as("immutable order total")
          .isEqualTo(initial.totalMinor());
      assertThat(persisted.createdAt())
          .as("immutable creation instant")
          .isEqualTo(initial.createdAt());

      ApiResponse payment = customerPayments.get(initial.id());
      payment.response().then().statusCode(200).contentType(ContentType.JSON);
      ApiChecks.payment(payment, persisted, "AUTHORIZED");
      context.awaitInventory(
          merchantInventory, 1, 0, fixture.setupResponse().as(Inventory.class).version() + 2);
    }
  }

  @Test
  @Tag("payment")
  @Story("A provider decline releases the reservation without consuming stock")
  void declinedPaymentFailsOrderAndReleasesStock() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 2)) {
      ApiResponse created =
          customerOrders.create(
              OrderRequest.forProduct(context.config().productId()).declined(),
              UUID.randomUUID().toString());
      fixture.track(created);
      created.response().then().statusCode(201).contentType(ContentType.JSON);
      Order initial = ApiChecks.acceptedOrder(created, context.config().productId(), 1);
      Order terminal = orders.untilStatus(initial.id(), "PAYMENT_FAILED");
      assertThatOrder(terminal).hasStatus("PAYMENT_FAILED").hasQuantity(1);
      assertThat(terminal.totalMinor())
          .as("decline preserves order total")
          .isEqualTo(initial.totalMinor());

      ApiResponse payment = customerPayments.get(initial.id());
      payment.response().then().statusCode(200).contentType(ContentType.JSON);
      ApiChecks.payment(payment, terminal, "DECLINED");
      context.awaitInventory(
          merchantInventory, 2, 0, fixture.setupResponse().as(Inventory.class).version() + 2);
    }
  }

  @Test
  @Tag("inventory")
  @Story("Zero available stock rejects checkout asynchronously without payment")
  void insufficientStockRejectsOrderWithoutChangingInventory() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 0)) {
      ApiResponse created =
          customerOrders.create(
              OrderRequest.forProduct(context.config().productId()), UUID.randomUUID().toString());
      fixture.track(created);
      created.response().then().statusCode(201).contentType(ContentType.JSON);
      Order initial = ApiChecks.acceptedOrder(created, context.config().productId(), 1);
      Order terminal = orders.untilStatus(initial.id(), "STOCK_REJECTED");
      assertThatOrder(terminal).hasStatus("STOCK_REJECTED").hasQuantity(1);

      ApiResponse payment = customerPayments.get(initial.id());
      payment.response().then().statusCode(404).contentType("application/problem+json");
      ApiChecks.problem(payment, 404);
      context.awaitInventory(
          merchantInventory, 0, 0, fixture.setupResponse().as(Inventory.class).version());
    }
  }

  @Test
  @Tag("validation")
  @Story("Quantity below the checkout range is rejected before reservation")
  void zeroQuantityReturnsInvalidRequestProblem() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 2)) {
      ApiResponse response =
          customerOrders.create(
              OrderRequest.forProduct(context.config().productId()).withQuantity(0),
              UUID.randomUUID().toString());
      fixture.track(response);
      response.response().then().statusCode(400).contentType("application/problem+json");
      ApiChecks.problem(response, 400, "INVALID_REQUEST");
      context.awaitInventory(
          merchantInventory, 2, 0, fixture.setupResponse().as(Inventory.class).version());
    }
  }

  @Test
  @Tag("idempotency")
  @Tag("validation")
  @Story("Every checkout requires an idempotency key")
  void missingIdempotencyKeyReturnsBadRequestProblem() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 2)) {
      ApiResponse response =
          customerOrders.create(OrderRequest.forProduct(context.config().productId()), null);
      fixture.track(response);
      response.response().then().statusCode(400).contentType("application/problem+json");
      // The pinned contract specifies 400 here, but does not pin a missing-key machine code.
      ApiChecks.problem(response, 400);
      context.awaitInventory(
          merchantInventory, 2, 0, fixture.setupResponse().as(Inventory.class).version());
    }
  }
}
