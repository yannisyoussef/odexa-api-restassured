package qa.odexa.api;

import static qa.odexa.assertion.OrderAssert.assertThatOrder;
import static qa.odexa.config.Actor.CUSTOMER_A;
import static qa.odexa.config.Actor.CUSTOMER_B;
import static qa.odexa.config.Actor.MERCHANT_A;
import static qa.odexa.config.Actor.OTHER_CUSTOMER_A;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import qa.odexa.client.InventoryClient;
import qa.odexa.client.OrderClient;
import qa.odexa.config.Actor;
import qa.odexa.data.OrderRequest;
import qa.odexa.fixture.StockFixture;
import qa.odexa.http.ApiResponse;
import qa.odexa.model.Order;
import qa.odexa.wait.OrderAwaiter;

@Tag("api")
@Tag("mutation")
@Tag("authorization")
@Tag("order")
@Tag("payment")
@Epic("Odexa")
@Feature("Customer ownership")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(value = "odexa:tenant-a-inventory", mode = ResourceAccessMode.READ_WRITE)
class OwnershipApiTest {
  private ApiContext context;
  private InventoryClient merchantInventory;
  private OrderClient customerOrders;

  @BeforeAll
  void setUpOwnerOtherCustomerOtherTenantAndMerchant() {
    context = ApiContext.load(CUSTOMER_A, OTHER_CUSTOMER_A, CUSTOMER_B, MERCHANT_A);
    context.requireMutation();
    merchantInventory = context.actor(MERCHANT_A).inventory();
    customerOrders = context.actor(CUSTOMER_A).orders();
  }

  @Test
  @Story("Same-tenant and cross-tenant nonowners both see order and payment as absent")
  void otherCustomersCannotReadAnExistingOwnersOrderOrPayment() {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 1)) {
      ApiResponse created =
          customerOrders.create(
              OrderRequest.forProduct(context.config().productId()), UUID.randomUUID().toString());
      fixture.track(created);
      created.response().then().statusCode(201).contentType(ContentType.JSON);
      Order initial = ApiChecks.acceptedOrder(created, context.config().productId(), 1);
      new OrderAwaiter(customerOrders, context.config()).untilStatus(initial.id(), "CONFIRMED");

      // Positive controls establish that both resources really exist before checking isolation.
      ApiResponse ownerRead = customerOrders.get(initial.id());
      ownerRead.response().then().statusCode(200).contentType(ContentType.JSON);
      Order owned = ApiChecks.order(ownerRead, context.config().productId(), 1);
      assertThatOrder(owned).hasStatus("CONFIRMED");
      ApiResponse ownerPayment = context.actor(CUSTOMER_A).payments().get(initial.id());
      ownerPayment.response().then().statusCode(200).contentType(ContentType.JSON);
      ApiChecks.payment(ownerPayment, owned, "AUTHORIZED");

      for (Actor nonowner : List.of(OTHER_CUSTOMER_A, CUSTOMER_B)) {
        ApiResponse hiddenOrder = context.actor(nonowner).orders().get(initial.id());
        hiddenOrder.response().then().statusCode(404).contentType("application/problem+json");
        ApiChecks.problem(hiddenOrder, 404, "ORDER_NOT_FOUND");

        ApiResponse hiddenPayment = context.actor(nonowner).payments().get(initial.id());
        hiddenPayment.response().then().statusCode(404).contentType("application/problem+json");
        ApiChecks.problem(hiddenPayment, 404);
      }
    }
  }
}
