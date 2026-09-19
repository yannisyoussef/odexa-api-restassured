package qa.odexa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** RFC 9457 permits additional transport metadata; never render arbitrary server strings. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Problem(
    String type, String title, int status, String code, String detail, String correlationId) {
  @Override
  public String toString() {
    return "Problem[status=" + status + "]";
  }
}
