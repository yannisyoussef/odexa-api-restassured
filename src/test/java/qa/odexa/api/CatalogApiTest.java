package qa.odexa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static qa.odexa.config.Actor.CUSTOMER_A;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import qa.odexa.client.CatalogClient;
import qa.odexa.http.ApiResponse;
import qa.odexa.model.Product;
import qa.odexa.model.ProductPage;

@Tag("api")
@Tag("catalog")
@Epic("Odexa")
@Feature("Tenant catalog reads")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class CatalogApiTest {
  private ApiContext context;
  private CatalogClient customerCatalog;

  @BeforeAll
  void setUpCustomer() {
    context = ApiContext.load(CUSTOMER_A);
    customerCatalog = context.actor(CUSTOMER_A).catalog();
  }

  @Test
  @Story("List returns a bounded product page and opaque cursor")
  void listReturnsContractPage() {
    ApiResponse response = customerCatalog.list(Map.of("limit", 1));
    response.response().then().statusCode(200).contentType(ContentType.JSON);
    ApiChecks.correlation(response);
    assertThat(response.json().path("items").isArray()).as("required items array").isTrue();
    assertThat(response.json().has("nextCursor")).as("required nullable cursor").isTrue();
    assertThat(
            response.json().path("nextCursor").isNull()
                || response.json().path("nextCursor").isTextual())
        .as("cursor is a string or null")
        .isTrue();
    ProductPage page = response.as(ProductPage.class);
    assertThat(page.items().size()).as("seeded tenant page respects limit").isEqualTo(1);
    page.items().forEach(ApiChecks::productValues);
    assertThat(page.nextCursor() == null || page.nextCursor().matches("[A-Za-z0-9_-]{48}"))
        .as("cursor is absent or opaque contract format")
        .isTrue();
  }

  @Test
  @Story("Strong ETag supports a bodyless conditional read")
  void getAndConditionalGetUseStrongEtagAndEchoCorrelation() {
    String correlation = UUID.randomUUID().toString();
    ApiResponse first =
        customerCatalog.get(context.config().productId(), Map.of("X-Correlation-ID", correlation));
    first.response().then().statusCode(200).contentType(ContentType.JSON);
    Product product = ApiChecks.product(first, context.config().productId());
    assertThat(correlation.equals(first.header("X-Correlation-ID")))
        .as("caller-supplied correlation is preserved")
        .isTrue();

    ApiResponse conditional =
        customerCatalog.get(product.id(), Map.of("If-None-Match", first.header("ETag")));
    conditional.response().then().statusCode(304);
    assertThat(conditional.response().asByteArray().length).as("304 has no body").isZero();
    ApiChecks.strongEtag(conditional, product.version());
    ApiChecks.correlation(conditional);
  }

  @Test
  @Story("Unknown product does not disclose a representation")
  void unknownProductReturnsNotFoundProblem() {
    ApiResponse response = customerCatalog.get(UUID.randomUUID());
    response.response().then().statusCode(404).contentType("application/problem+json");
    ApiChecks.problem(response, 404, "PRODUCT_NOT_FOUND");
  }

  @Test
  @Story("Limit below the public range is rejected")
  void zeroLimitReturnsInvalidQueryProblem() {
    ApiResponse response = customerCatalog.list(Map.of("limit", 0));
    response.response().then().statusCode(400).contentType("application/problem+json");
    ApiChecks.problem(response, 400, "INVALID_QUERY");
  }
}
