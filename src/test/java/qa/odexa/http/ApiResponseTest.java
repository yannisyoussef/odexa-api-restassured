package qa.odexa.http;

import static org.junit.jupiter.api.Assertions.*;

import io.restassured.builder.ResponseBuilder;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiResponseTest {
  private static final String SECRET = "synthetic-parser-secret";

  @Test
  void exposesRestAssuredAndUsesJacksonJavaTimeForTypedResponses() {
    String correlation = UUID.randomUUID().toString();
    var raw =
        new ResponseBuilder()
            .setStatusCode(201)
            .setContentType("application/json")
            .setHeader("ETag", "\"1\"")
            .setBody("{\"quantity\":3,\"createdAt\":\"2026-01-01T00:00:00Z\"}")
            .build();
    ApiResponse response = new ApiResponse(raw, correlation);
    assertSame(raw, response.response());
    assertEquals(201, response.status());
    assertEquals("\"1\"", response.header("ETag"));
    assertEquals(correlation, response.correlationId());
    assertEquals(3, response.json().path("quantity").intValue());
    Payload payload = response.as(Payload.class);
    assertEquals(3, payload.quantity());
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), payload.createdAt());
  }

  @Test
  void parserAndMappingErrorsNeverRetainMaliciousEchoOrCause() {
    for (String body : new String[] {"{\"value\":\"" + SECRET + "\",broken", SECRET, "null", ""}) {
      ApiResponse response = response(body);
      assertSafe(assertThrows(ApiException.class, response::json));
      assertSafe(assertThrows(ApiException.class, () -> response.as(Payload.class)));
      assertFalse(response.toString().contains(SECRET));
    }
    ApiResponse wrongType = response("{\"quantity\":\"" + SECRET + "\",\"createdAt\":null}");
    assertSafe(assertThrows(ApiException.class, () -> wrongType.as(Payload.class)));
    assertEquals("UNAVAILABLE", wrongType.correlationId());
  }

  private static ApiResponse response(String body) {
    return new ApiResponse(
        new ResponseBuilder()
            .setStatusCode(400)
            .setContentType("application/json")
            .setBody(body)
            .build(),
        SECRET);
  }

  private static void assertSafe(ApiException failure) {
    assertNull(failure.getCause());
    assertEquals(0, failure.getSuppressed().length);
    StringWriter output = new StringWriter();
    failure.printStackTrace(new PrintWriter(output));
    assertFalse(output.toString().contains(SECRET));
  }

  public record Payload(int quantity, Instant createdAt) {}
}
