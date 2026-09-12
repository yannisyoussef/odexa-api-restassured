package qa.odexa.api;

import static qa.odexa.config.Actor.CUSTOMER_B;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import qa.odexa.http.ApiResponse;

@Tag("api")
@Tag("authorization")
@Tag("catalog")
@Tag("inventory")
@Epic("Odexa")
@Feature("Tenant isolation")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class TenantIsolationApiTest {
  private ApiContext context;

  @BeforeAll
  void setUpTenantBCustomer() {
    context = ApiContext.load(CUSTOMER_B);
  }

  @Test
  @Story("Tenant B cannot read tenant A product or inventory")
  void otherTenantSeesNotFoundForBothProductAndStock() {
    ApiResponse product = context.actor(CUSTOMER_B).catalog().get(context.config().productId());
    product.response().then().statusCode(404).contentType("application/problem+json");
    ApiChecks.problem(product, 404, "PRODUCT_NOT_FOUND");

    ApiResponse inventory = context.actor(CUSTOMER_B).inventory().get(context.config().productId());
    inventory.response().then().statusCode(404).contentType("application/problem+json");
    ApiChecks.problem(inventory, 404, "INVENTORY_NOT_FOUND");
  }
}
