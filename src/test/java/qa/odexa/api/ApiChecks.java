package qa.odexa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static qa.odexa.assertion.ProblemAssert.assertThatProblem;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import qa.odexa.http.ApiResponse;
import qa.odexa.model.Inventory;
import qa.odexa.model.Order;
import qa.odexa.model.Payment;
import qa.odexa.model.Product;

/** Selected pinned-contract checks, not a general JSON Schema validator. Never prints raw JSON. */
final class ApiChecks {
  private static final Set<String> ORDER_STATES =
      Set.of("CREATED", "PENDING_PAYMENT", "CONFIRMED", "STOCK_REJECTED", "PAYMENT_FAILED");

  private ApiChecks() {}

  static void correlation(ApiResponse response) {
    String header = response.header("X-Correlation-ID");
    assertThat(
            header != null
                && header.matches(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
        .as("response correlation header is a UUID")
        .isTrue();
    assertThat(Objects.equals(header, response.correlationId()))
        .as("request correlation is echoed")
        .isTrue();
  }

  static void strongEtag(ApiResponse response, long version) {
    assertThat(version).as("positive entity version").isPositive();
    assertThat(Objects.equals(response.header("ETag"), "\"" + version + "\""))
        .as("strong ETag quotes the representation version")
        .isTrue();
  }

  static Product product(ApiResponse response, UUID expectedId) {
    JsonNode body = response.json();
    textFields(body, "id", "name", "description", "currency");
    integerFields(body, "unitPriceMinor", "version");
    assertThat(body.path("active").isBoolean()).as("active is a required boolean").isTrue();
    Product product = response.as(Product.class);
    assertThat(product.id()).as("product identity").isEqualTo(expectedId);
    productValues(product);
    strongEtag(response, product.version());
    correlation(response);
    return product;
  }

  static void productValues(Product product) {
    assertThat(product.id()).as("product UUID").isNotNull();
    assertThat(
            product.name() != null && !product.name().isBlank() && product.name().length() <= 200)
        .as("bounded nonblank product name")
        .isTrue();
    assertThat(product.description() != null && product.description().length() <= 2000)
        .as("bounded product description")
        .isTrue();
    assertThat(product.unitPriceMinor()).as("price in minor units").isNotNegative();
    assertThat("USD".equals(product.currency())).as("contract currency").isTrue();
    assertThat(product.version()).as("product version").isPositive();
  }

  static Inventory inventory(ApiResponse response, UUID expectedProduct) {
    JsonNode body = response.json();
    textFields(body, "productId");
    integerFields(body, "onHand", "reserved", "available", "version");
    Inventory stock = response.as(Inventory.class);
    assertThat(stock.productId()).as("inventory product identity").isEqualTo(expectedProduct);
    assertThat(stock.onHand()).as("on-hand stock").isNotNegative();
    assertThat(stock.reserved())
        .as("reservations within physical stock")
        .isBetween(0L, stock.onHand());
    assertThat(stock.available())
        .as("availability excludes reservations")
        .isEqualTo(stock.onHand() - stock.reserved());
    strongEtag(response, stock.version());
    correlation(response);
    return stock;
  }

  static Order order(ApiResponse response, UUID productId, int quantity) {
    JsonNode body = response.json();
    textFields(body, "id", "productId", "currency", "status", "createdAt");
    integerFields(body, "quantity", "totalMinor", "version");
    assertThat(body.has("paymentMethod") || body.has("customerId"))
        .as("order excludes payment method and customer identity")
        .isFalse();
    Order order = response.as(Order.class);
    assertThat(order.id()).as("order UUID").isNotNull();
    assertThat(order.productId()).as("ordered product").isEqualTo(productId);
    assertThat(order.quantity()).as("ordered quantity").isEqualTo(quantity);
    assertThat(order.totalMinor()).as("positive checkout total in minor units").isPositive();
    assertThat("USD".equals(order.currency())).as("order currency").isTrue();
    assertThat(order.status() != null && ORDER_STATES.contains(order.status()))
        .as("documented public order state")
        .isTrue();
    assertThat(order.version()).as("order version").isNotNegative();
    assertThat(order.createdAt()).as("order creation instant").isNotNull();
    correlation(response);
    return order;
  }

  static Order acceptedOrder(ApiResponse response, UUID productId, int quantity) {
    Order order = order(response, productId, quantity);
    assertThat(Objects.equals(response.header("Location"), "/api/v1/orders/" + order.id()))
        .as("relative Location identifies the accepted order")
        .isTrue();
    return order;
  }

  static Payment payment(ApiResponse response, Order order, String expectedStatus) {
    JsonNode body = response.json();
    textFields(body, "id", "orderId", "currency", "status", "createdAt", "updatedAt");
    integerFields(body, "amountMinor");
    Payment payment = response.as(Payment.class);
    assertThat(payment.id()).as("payment UUID").isNotNull();
    assertThat(payment.orderId()).as("payment belongs to the order").isEqualTo(order.id());
    assertThat(payment.amountMinor())
        .as("payment equals the order total")
        .isEqualTo(order.totalMinor());
    assertThat(Objects.equals(payment.currency(), order.currency()))
        .as("payment uses the snapshotted currency")
        .isTrue();
    assertThat(expectedStatus.equals(payment.status())).as("expected payment outcome").isTrue();
    assertThat(payment.createdAt()).as("payment creation instant").isNotNull();
    assertThat(payment.updatedAt()).as("payment update instant").isNotNull();
    assertThat(!payment.updatedAt().isBefore(payment.createdAt()))
        .as("payment update does not precede creation")
        .isTrue();
    correlation(response);
    return payment;
  }

  static void problem(ApiResponse response, int status) {
    assertThatProblem(response).hasStatus(status);
    textFields(response.json(), "type", "title");
    integerFields(response.json(), "status");
    correlation(response);
  }

  static void problem(ApiResponse response, int status, String code) {
    problem(response, status);
    assertThatProblem(response).hasCode(code);
    assertThat(
            response
                .json()
                .path("type")
                .asText()
                .matches("https://odexa\\.cc/problems/[a-z0-9_-]+"))
        .as("business problem type belongs to the documented lowercase namespace")
        .isTrue();
  }

  private static void textFields(JsonNode body, String... fields) {
    for (String field : fields) {
      assertThat(body.path(field).isTextual()).as("required string field: %s", field).isTrue();
    }
  }

  private static void integerFields(JsonNode body, String... fields) {
    for (String field : fields) {
      assertThat(body.path(field).isIntegralNumber())
          .as("required integer field: %s", field)
          .isTrue();
    }
  }
}
