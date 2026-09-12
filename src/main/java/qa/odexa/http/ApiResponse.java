package qa.odexa.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.restassured.response.Response;
import java.util.Objects;
import qa.odexa.diagnostics.SafeDiagnostics;

/** Raw access is deliberate for explicit Rest Assured assertions; wrapper diagnostics stay safe. */
public final class ApiResponse {
  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());
  private final Response response;
  private final String correlationId;

  public ApiResponse(Response response, String correlationId) {
    this.response = Objects.requireNonNull(response);
    this.correlationId = SafeDiagnostics.correlation(correlationId);
  }

  public Response response() {
    return response;
  }

  public int status() {
    return response.statusCode();
  }

  public String header(String name) {
    return response.header(name);
  }

  public String correlationId() {
    return correlationId;
  }

  public <T> T as(Class<T> type) {
    try {
      T value = MAPPER.readValue(response.asByteArray(), type);
      if (value == null) {
        throw invalidBody();
      }
      return value;
    } catch (Exception ignored) {
      throw invalidBody();
    }
  }

  public JsonNode json() {
    try {
      JsonNode value = MAPPER.readTree(response.asByteArray());
      if (value == null || value.isNull() || value.isMissingNode()) {
        throw invalidBody();
      }
      return value;
    } catch (Exception ignored) {
      throw invalidBody();
    }
  }

  private ApiException invalidBody() {
    return new ApiException(
        "Response deserialization failed: status=" + status() + ", correlationId=" + correlationId);
  }

  @Override
  public String toString() {
    return "ApiResponse[status=" + status() + ", correlationId=" + correlationId + "]";
  }
}
