package qa.odexa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static qa.odexa.config.Actor.CUSTOMER_A;
import static qa.odexa.config.Actor.CUSTOMER_B;
import static qa.odexa.config.Actor.MERCHANT_A;
import static qa.odexa.config.Actor.OTHER_CUSTOMER_A;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.http.ContentType;
import io.restassured.http.Method;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import qa.odexa.config.Actor;
import qa.odexa.http.ApiResponse;

@Tag("api")
@Tag("auth")
@Epic("Odexa")
@Feature("Gateway authentication")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class AuthenticationApiTest {
  private ApiContext context;

  @BeforeAll
  void setUpActors() {
    context = ApiContext.load(CUSTOMER_A, CUSTOMER_B, OTHER_CUSTOMER_A, MERCHANT_A);
  }

  @ParameterizedTest(name = "{0} can read its own tenant fixtures")
  @EnumSource(
      value = Actor.class,
      names = {"CUSTOMER_A", "CUSTOMER_B", "OTHER_CUSTOMER_A", "MERCHANT_A"})
  @Tag("catalog")
  @Tag("inventory")
  @Story("Every provisioned actor authenticates without a role bypass")
  void provisionedActorCanReadOwnTenant(Actor actor) {
    UUID productId =
        actor == CUSTOMER_B ? context.config().otherProductId() : context.config().productId();
    ApiResponse product = context.actor(actor).catalog().get(productId);
    product.response().then().statusCode(200).contentType(ContentType.JSON);
    ApiChecks.product(product, productId);

    ApiResponse inventory = context.actor(actor).inventory().get(productId);
    inventory.response().then().statusCode(200).contentType(ContentType.JSON);
    // Reads remain parallel: assert the representation invariant, never a mutable stock baseline.
    ApiChecks.inventory(inventory, productId);
  }

  @Test
  @Story("Missing bearer token is rejected")
  void missingTokenReturnsUnauthorizedProblem() {
    ApiResponse response =
        context
            .http()
            .execute(
                null,
                Method.GET,
                "/api/v1/products/" + context.config().productId(),
                Map.of(),
                Map.of(),
                null);
    response.response().then().statusCode(401).contentType("application/problem+json");
    ApiChecks.problem(response, 401);
    assertBearerChallenge(response);
  }

  @Test
  @Story("Malformed bearer token is rejected")
  void invalidTokenReturnsUnauthorizedProblem() {
    ApiResponse response =
        context
            .http()
            .execute(
                null,
                Method.GET,
                "/api/v1/products/" + context.config().productId(),
                Map.of(),
                Map.of("Authorization", "Bearer not-a-jwt"),
                null);
    response.response().then().statusCode(401).contentType("application/problem+json");
    ApiChecks.problem(response, 401);
    assertBearerChallenge(response);
  }

  private static void assertBearerChallenge(ApiResponse response) {
    String challenge = response.header("WWW-Authenticate");
    assertThat(
            challenge != null
                && (challenge.equalsIgnoreCase("Bearer")
                    || challenge.regionMatches(true, 0, "Bearer ", 0, 7)))
        .as("WWW-Authenticate offers a bearer challenge")
        .isTrue();
  }
}
