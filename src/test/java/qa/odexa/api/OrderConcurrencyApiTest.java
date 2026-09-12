package qa.odexa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static qa.odexa.assertion.OrderAssert.assertThatOrder;
import static qa.odexa.config.Actor.CUSTOMER_A;
import static qa.odexa.config.Actor.MERCHANT_A;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.http.ContentType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
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
@Tag("concurrency")
@Epic("Odexa")
@Feature("Concurrent checkout")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(value = "odexa:tenant-a-inventory", mode = ResourceAccessMode.READ_WRITE)
class OrderConcurrencyApiTest {
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
  @Tag("inventory")
  @Story("Eight distinct orders compete for five units without overselling")
  void eightOrdersAgainstFiveUnitsConfirmExactlyFive() throws InterruptedException {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 5)) {
      List<String> keys =
          IntStream.range(0, 8).mapToObj(index -> UUID.randomUUID().toString()).toList();
      List<ApiResponse> responses = race(fixture, keys);
      assertThat(responses.size()).as("all eight HTTP requests returned").isEqualTo(8);
      List<UUID> orderIds = new ArrayList<>();
      for (ApiResponse response : responses) {
        response.response().then().statusCode(201).contentType(ContentType.JSON);
        orderIds.add(ApiChecks.acceptedOrder(response, context.config().productId(), 1).id());
      }
      assertThat(orderIds.stream().distinct().count()).as("eight independent orders").isEqualTo(8L);
      assertThat(responses.stream().map(ApiResponse::correlationId).distinct().count())
          .as("fresh correlation per parallel request")
          .isEqualTo(8L);

      int confirmed = 0;
      int rejected = 0;
      for (UUID orderId : orderIds) {
        Order terminal = orders.untilTerminal(orderId);
        assertThat(terminal.id()).as("polled order identity").isEqualTo(orderId);
        assertThatOrder(terminal).hasQuantity(1);
        if ("CONFIRMED".equals(terminal.status())) {
          confirmed++;
        } else if ("STOCK_REJECTED".equals(terminal.status())) {
          rejected++;
        }
      }
      assertThat(confirmed).as("only the five available units are confirmed").isEqualTo(5);
      assertThat(rejected).as("three orders lose the stock race").isEqualTo(3);
      context.awaitInventory(
          merchantInventory, 0, 0, fixture.setupResponse().as(Inventory.class).version() + 10);
    }
  }

  @Test
  @Tag("idempotency")
  @Story("Four new submissions with one fresh key create exactly one order")
  void fourSimultaneousFirstSubmissionsShareOneOrder() throws InterruptedException {
    try (StockFixture fixture =
        StockFixture.acquire(context.config(), merchantInventory, customerOrders, 4)) {
      // No priming POST: every participant races to create this previously unused key.
      String freshKey = UUID.randomUUID().toString();
      List<ApiResponse> responses = race(fixture, Collections.nCopies(4, freshKey));
      assertThat(responses.size()).as("all four HTTP requests returned").isEqualTo(4);
      assertThat(responses.stream().filter(response -> response.status() == 201).count())
          .as("exactly one creator")
          .isEqualTo(1L);
      assertThat(responses.stream().filter(response -> response.status() == 200).count())
          .as("exactly three replays")
          .isEqualTo(3L);

      List<UUID> orderIds = new ArrayList<>();
      for (ApiResponse response : responses) {
        response
            .response()
            .then()
            .statusCode(anyOf(is(201), is(200)))
            .contentType(ContentType.JSON);
        orderIds.add(ApiChecks.acceptedOrder(response, context.config().productId(), 1).id());
      }
      assertThat(orderIds.stream().distinct().count())
          .as("one durable order identity")
          .isEqualTo(1L);
      assertThat(responses.stream().map(ApiResponse::correlationId).distinct().count())
          .as("replays still have independent request correlations")
          .isEqualTo(4L);
      assertThatOrder(orders.untilStatus(orderIds.getFirst(), "CONFIRMED"))
          .hasStatus("CONFIRMED")
          .hasQuantity(1);
      context.awaitInventory(
          merchantInventory, 3, 0, fixture.setupResponse().as(Inventory.class).version() + 2);
    }
  }

  private List<ApiResponse> race(StockFixture fixture, List<String> keys)
      throws InterruptedException {
    CountDownLatch ready = new CountDownLatch(keys.size());
    CountDownLatch start = new CountDownLatch(1);
    ConcurrentLinkedQueue<ApiResponse> returned = new ConcurrentLinkedQueue<>();
    OrderRequest request = OrderRequest.forProduct(context.config().productId());
    long requestTimeoutMillis = context.config().requestTimeout().toMillis();
    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ApiResponse>> futures = new ArrayList<>();
      try {
        for (String key : keys) {
          futures.add(
              workers.submit(
                  () -> {
                    ready.countDown();
                    if (!start.await(requestTimeoutMillis, TimeUnit.MILLISECONDS)) {
                      throw new AssertionError("Concurrent checkout start barrier timed out");
                    }
                    ApiResponse response = customerOrders.create(request, key);
                    returned.add(response);
                    return response;
                  }));
        }
        assertThat(ready.await(requestTimeoutMillis, TimeUnit.MILLISECONDS))
            .as("all concurrent checkout workers reached the start barrier")
            .isTrue();
        start.countDown();
        for (Future<ApiResponse> future : futures) {
          // Allows bounded authentication and request work, but never retries an ambiguous write.
          future.get(requestTimeoutMillis * 4 + 5000, TimeUnit.MILLISECONDS);
        }
      } catch (ExecutionException | TimeoutException failure) {
        // Never propagate worker causes, which could contain transport or authentication material.
        throw new AssertionError(
            "Concurrent checkout failed; inspect sanitized request diagnostics");
      } finally {
        start.countDown();
      }
    } finally {
      // Executor close joins all workers first. Track even if another worker or assertion failed.
      // StockFixture remains single-threaded and deduplicates all successful replay responses.
      for (ApiResponse response : returned) {
        fixture.track(response);
      }
    }
    return List.copyOf(returned);
  }
}
