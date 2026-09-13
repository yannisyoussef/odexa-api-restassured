package qa.odexa.diagnostics;

import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import qa.odexa.config.Actor;

/** An explicit metadata allowlist: bodies, headers, query values and token traffic are omitted. */
public final class SafeDiagnostics {
  private static final System.Logger LOG = System.getLogger(SafeDiagnostics.class.getName());

  private SafeDiagnostics() {}

  public static String route(String path) {
    if (path == null) {
      return "/api/v1/{route}";
    }
    for (String collection : new String[] {"products", "inventory", "orders", "payments"}) {
      String prefix = "/api/v1/" + collection;
      if (path.equals(prefix)) {
        return prefix;
      }
      if (path.startsWith(prefix + "/")) {
        return prefix + "/{id}";
      }
    }
    return "/api/v1/{route}";
  }

  public static String correlation(String candidate) {
    if (candidate != null) {
      try {
        String canonical = UUID.fromString(candidate).toString();
        if (canonical.equalsIgnoreCase(candidate)) {
          return canonical;
        }
      } catch (IllegalArgumentException ignored) {
        // Untrusted response/caller correlation is never copied into diagnostics.
      }
    }
    return "UNAVAILABLE";
  }

  public static String metadata(
      String method, String path, Actor actor, int status, String correlation) {
    String safeMethod =
        switch (method == null ? "" : method) {
          case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE" -> method;
          default -> "UNKNOWN";
        };
    return "method="
        + safeMethod
        + "\nroute="
        + route(path)
        + "\nactor="
        + (actor == null ? "UNAUTHENTICATED" : actor.name())
        + "\nstatus="
        + (status >= 100 && status <= 599 ? status : 0)
        + "\ncorrelationId="
        + correlation(correlation)
        + "\npayloads=omitted (metadata-only allowlist)";
  }

  public static String structuredMetadata(
      String method, String path, Actor actor, int status, String correlation) {
    var json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    // Reuse the same closed set of values as the human-readable attachment.
    for (String line : metadata(method, path, actor, status, correlation).split("\\n")) {
      int separator = line.indexOf('=');
      json.put(line.substring(0, separator), line.substring(separator + 1));
    }
    return json.toString();
  }

  public static void attach(
      String method, String path, Actor actor, int status, String correlation, boolean verbose) {
    attach(Allure.getLifecycle(), method, path, actor, status, correlation, verbose);
  }

  static void attach(
      AllureLifecycle lifecycle,
      String method,
      String path,
      Actor actor,
      int status,
      String correlation,
      boolean verbose) {
    if (!verbose && status > 0 && status < 400) {
      return;
    }
    LOG.log(System.Logger.Level.INFO, structuredMetadata(method, path, actor, status, correlation));
    // Worker threads frequently have no Allure context. Do not create orphan attachments or log it.
    if (lifecycle.getCurrentTestCase().isPresent()) {
      lifecycle.addAttachment(
          "HTTP metadata",
          "text/plain",
          ".txt",
          metadata(method, path, actor, status, correlation).getBytes(StandardCharsets.UTF_8));
    }
  }
}
