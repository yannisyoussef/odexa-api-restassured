package qa.odexa.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.UUID;
import qa.odexa.http.ApiResponse;

/**
 * Focused Problem assertions. Failure messages never render response bodies or arbitrary strings.
 */
public final class ProblemAssert {
  private final ApiResponse response;
  private final JsonNode problem;

  private ProblemAssert(ApiResponse response) {
    require(response != null, "Problem response is required");
    this.response = response;
    String contentType = response.header("Content-Type");
    require(
        contentType != null
            && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/problem+json"),
        "Expected application/problem+json");
    try {
      problem = response.json();
    } catch (RuntimeException ignored) {
      throw new AssertionError("Problem response is not readable JSON");
    }
    require(problem != null && problem.isObject(), "Problem must be a JSON object");
    require(text("type") != null && !text("type").isBlank(), "Problem type must be nonempty");
    require(text("title") != null && !text("title").isBlank(), "Problem title must be nonempty");
    JsonNode status = problem.path("status");
    require(
        status.isIntegralNumber() && status.canConvertToInt(), "Problem status must be an integer");
    require(
        status.intValue() >= 400 && status.intValue() <= 599, "Problem status must be an error");
    require(status.intValue() == response.status(), "Problem status must match HTTP status");
    String correlation = response.header("X-Correlation-ID");
    require(isUuid(correlation), "Problem response must include a correlation UUID header");
    // Order's public Problem schema does not require a correlationId body extension.
    if (problem.has("correlationId")) {
      require(isUuid(text("correlationId")), "Problem correlationId must be a UUID");
      require(
          correlation.equalsIgnoreCase(text("correlationId")),
          "Problem correlationId must match the response header");
    }
  }

  public static ProblemAssert assertThatProblem(ApiResponse response) {
    return new ProblemAssert(response);
  }

  public ProblemAssert hasStatus(int expected) {
    require(response.status() == expected, "Unexpected Problem HTTP status");
    return this;
  }

  public ProblemAssert hasCode(String expected) {
    require(expected != null && expected.equals(text("code")), "Unexpected Problem code");
    return this;
  }

  /** Checks the public contract's https://odexa.cc/problems/{lowercase-code} convention. */
  public ProblemAssert hasTypeForCode() {
    String code = text("code");
    require(
        code != null && code.matches("[A-Z][A-Z0-9_]{0,63}"),
        "Problem code must be a machine code");
    require(
        ("https://odexa.cc/problems/" + code.toLowerCase(Locale.ROOT)).equals(text("type")),
        "Problem type must correspond to its code");
    return this;
  }

  private String text(String name) {
    JsonNode value = problem.path(name);
    return value.isTextual() ? value.textValue() : null;
  }

  private static boolean isUuid(String value) {
    if (value == null || value.length() != 36) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equalsIgnoreCase(value);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
