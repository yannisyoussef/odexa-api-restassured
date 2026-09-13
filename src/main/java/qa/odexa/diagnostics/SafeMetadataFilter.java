package qa.odexa.diagnostics;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import qa.odexa.config.Actor;

/** Never inspects request/response content; metadata is captured before the transport runs. */
public final class SafeMetadataFilter implements Filter {
  private final String method;
  private final String route;
  private final Actor actor;
  private final String correlation;
  private final boolean verbose;

  public SafeMetadataFilter(
      String method, String route, Actor actor, String correlation, boolean verbose) {
    this.method = method;
    this.route = SafeDiagnostics.route(route);
    this.actor = actor;
    this.correlation = SafeDiagnostics.correlation(correlation);
    this.verbose = verbose;
  }

  @Override
  public Response filter(
      FilterableRequestSpecification request,
      FilterableResponseSpecification response,
      FilterContext context) {
    Response result = context.next(request, response);
    SafeDiagnostics.attach(method, route, actor, result.statusCode(), correlation, verbose);
    return result;
  }

  @Override
  public String toString() {
    return "SafeMetadataFilter[metadata-only]";
  }
}
