package qa.odexa.assertion;

import static org.junit.jupiter.api.Assertions.*;
import static qa.odexa.assertion.ProblemAssert.assertThatProblem;
import static qa.odexa.client.UnitHttpServer.PRODUCT;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qa.odexa.client.CatalogClient;
import qa.odexa.client.UnitHttpServer;

class ProblemAssertTest {
  @Test
  void checksProblemMediaTypeStatusMachineCodeTypeAndCorrelation() throws Exception {
    try (UnitHttpServer server = new UnitHttpServer(request -> problemReply(problem()))) {
      var response = new CatalogClient(server.http(), null).get(PRODUCT);
      assertThatProblem(response).hasStatus(404).hasCode("PRODUCT_NOT_FOUND").hasTypeForCode();
    }
  }

  @Test
  void correlationBodyExtensionIsOptionalAsInOrderContract() throws Exception {
    Map<String, Object> body = problem();
    body.remove("correlationId");
    try (UnitHttpServer server = new UnitHttpServer(request -> problemReply(body))) {
      assertThatProblem(new CatalogClient(server.http(), null).get(PRODUCT)).hasStatus(404);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"status", "title", "type", "correlationId"})
  void invalidCoreFieldsFailWithoutRenderingUntrustedBody(String field) throws Exception {
    Map<String, Object> body = problem();
    body.put(field, field.equals("title") || field.equals("type") ? "" : "private-marker");
    body.put("detail", "private-marker");
    try (UnitHttpServer server = new UnitHttpServer(request -> problemReply(body))) {
      var response = new CatalogClient(server.http(), null).get(PRODUCT);
      AssertionError error = assertThrows(AssertionError.class, () -> assertThatProblem(response));
      assertFalse(error.toString().contains("private-marker"));
      assertNull(error.getCause());
    }
  }

  @Test
  void incorrectStatusCodeAndTypeAssertionsDoNotEchoActualValues() throws Exception {
    Map<String, Object> body = problem();
    body.put("code", "private-marker");
    body.put("type", "https://example.invalid/private-marker");
    try (UnitHttpServer server = new UnitHttpServer(request -> problemReply(body))) {
      var response = new CatalogClient(server.http(), null).get(PRODUCT);
      var assertion = assertThatProblem(response);
      for (AssertionError error :
          java.util.List.of(
              assertThrows(AssertionError.class, () -> assertion.hasStatus(400)),
              assertThrows(AssertionError.class, () -> assertion.hasCode("PRODUCT_NOT_FOUND")),
              assertThrows(AssertionError.class, assertion::hasTypeForCode))) {
        assertFalse(error.toString().contains("private-marker"));
        assertNull(error.getCause());
      }
    }
  }

  @Test
  void malformedJsonAndIncorrectContentTypeHaveSafeFailures() throws Exception {
    try (UnitHttpServer server =
        new UnitHttpServer(
            request ->
                UnitHttpServer.Reply.raw(404, "private-marker")
                    .header("Content-Type", "application/problem+json"))) {
      var response = new CatalogClient(server.http(), null).get(PRODUCT);
      var error = assertThrows(AssertionError.class, () -> assertThatProblem(response));
      assertFalse(error.toString().contains("private-marker"));
      assertNull(error.getCause());
    }
    try (UnitHttpServer server =
        new UnitHttpServer(
            request -> problemReply(problem()).header("Content-Type", "text/private-marker"))) {
      var response = new CatalogClient(server.http(), null).get(PRODUCT);
      var error = assertThrows(AssertionError.class, () -> assertThatProblem(response));
      assertFalse(error.toString().contains("private-marker"));
    }
  }

  @Test
  void mismatchedHttpStatusAndCorrelationAreRejected() throws Exception {
    Map<String, Object> body = problem();
    body.put("status", 400);
    try (UnitHttpServer server = new UnitHttpServer(request -> problemReply(body))) {
      var response = new CatalogClient(server.http(), null).get(PRODUCT);
      assertThrows(AssertionError.class, () -> assertThatProblem(response));
    }
    body.put("status", 404);
    body.put("correlationId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    try (UnitHttpServer server = new UnitHttpServer(request -> problemReply(body))) {
      var response = new CatalogClient(server.http(), null).get(PRODUCT);
      assertThrows(AssertionError.class, () -> assertThatProblem(response));
    }
  }

  private static Map<String, Object> problem() {
    return new LinkedHashMap<>(
        Map.of(
            "type",
            "https://odexa.cc/problems/product_not_found",
            "title",
            "Not found",
            "status",
            404,
            "code",
            "PRODUCT_NOT_FOUND",
            "detail",
            "private-marker",
            "correlationId",
            UnitHttpServer.CORRELATION));
  }

  private static UnitHttpServer.Reply problemReply(Map<String, Object> body) {
    return UnitHttpServer.Reply.json(404, body)
        .header("Content-Type", "application/problem+json; charset=UTF-8");
  }
}
