package qa.odexa.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.AllureResultsWriter;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.model.TestResultContainer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import qa.odexa.config.Actor;

class SafeDiagnosticsTest {
  private static final String SECRET = "synthetic-diagnostic-secret";

  @Test
  void maliciousPathsMethodsAndCorrelationValuesNeverEnterMetadata() {
    String metadata =
        SafeDiagnostics.metadata(
            SECRET,
            "/api/v1/products/" + SECRET + "?password=" + SECRET,
            Actor.CUSTOMER_A,
            400,
            SECRET);
    assertFalse(metadata.contains(SECRET));
    assertTrue(metadata.contains("method=UNKNOWN"));
    assertTrue(metadata.contains("route=/api/v1/products/{id}"));
    assertTrue(metadata.contains("actor=CUSTOMER_A"));
    assertTrue(metadata.contains("status=400"));
    assertTrue(metadata.contains("correlationId=UNAVAILABLE"));
    assertTrue(metadata.contains("metadata-only allowlist"));
    assertEquals("/api/v1/{route}", SafeDiagnostics.route("https://" + SECRET));
    assertEquals("UNAVAILABLE", SafeDiagnostics.correlation("1-1-1-1-1"));
    assertEquals("UNAVAILABLE", SafeDiagnostics.correlation(null));
  }

  @Test
  void structuredLogsUseTheSameSecretSafeAllowlist() throws Exception {
    String value =
        SafeDiagnostics.structuredMetadata(
            SECRET, "/api/v1/orders/" + SECRET, Actor.CUSTOMER_A, 401, SECRET);
    assertFalse(value.contains(SECRET));
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
    assertEquals("UNKNOWN", json.path("method").asText());
    assertEquals("/api/v1/orders/{id}", json.path("route").asText());
    assertEquals("CUSTOMER_A", json.path("actor").asText());
    assertEquals("UNAVAILABLE", json.path("correlationId").asText());
  }

  @Test
  void failureAttachmentContainsOnlyAllowedContext() {
    MemoryWriter writer = new MemoryWriter();
    AllureLifecycle lifecycle = new AllureLifecycle(writer);
    String testId = UUID.randomUUID().toString();
    String correlation = UUID.randomUUID().toString();
    lifecycle.scheduleTestCase(new TestResult().setUuid(testId).setName("safe-context"));
    lifecycle.startTestCase(testId);
    try {
      SafeDiagnostics.attach(
          lifecycle, "POST", "/api/v1/orders/" + SECRET, Actor.CUSTOMER_A, 503, correlation, false);
      assertEquals(1, writer.attachments.size());
      String attachment = writer.attachments.get(0);
      assertFalse(attachment.contains(SECRET));
      assertEquals(
          "method=POST\nroute=/api/v1/orders/{id}\nactor=CUSTOMER_A\nstatus=503"
              + "\ncorrelationId="
              + correlation
              + "\npayloads=omitted (metadata-only allowlist)",
          attachment);
    } finally {
      lifecycle.stopTestCase(testId);
      lifecycle.writeTestCase(testId);
    }
  }

  @Test
  void noContextDoesNotCreateOrphanAttachmentsEvenOnFailureOrVerbose() {
    MemoryWriter writer = new MemoryWriter();
    AllureLifecycle lifecycle = new AllureLifecycle(writer);
    SafeDiagnostics.attach(lifecycle, "GET", "/api/v1/products", null, 500, SECRET, false);
    SafeDiagnostics.attach(lifecycle, "GET", "/api/v1/products", null, 200, SECRET, true);
    assertTrue(writer.attachments.isEmpty());
  }

  @Test
  void successIsQuietUnlessVerboseButVerboseStillCannotIncludePayloads() {
    MemoryWriter writer = new MemoryWriter();
    AllureLifecycle lifecycle = new AllureLifecycle(writer);
    String testId = UUID.randomUUID().toString();
    lifecycle.scheduleTestCase(new TestResult().setUuid(testId).setName("safe-verbose"));
    lifecycle.startTestCase(testId);
    try {
      SafeDiagnostics.attach(
          lifecycle, "GET", "/api/v1/products/" + SECRET, null, 200, SECRET, false);
      assertTrue(writer.attachments.isEmpty());
      SafeDiagnostics.attach(
          lifecycle, "GET", "/api/v1/products/" + SECRET, null, 200, SECRET, true);
      assertEquals(1, writer.attachments.size());
      assertFalse(writer.attachments.get(0).contains(SECRET));
    } finally {
      lifecycle.stopTestCase(testId);
      lifecycle.writeTestCase(testId);
    }
  }

  private static final class MemoryWriter implements AllureResultsWriter {
    private final List<String> attachments = new ArrayList<>();

    @Override
    public void write(TestResult result) {}

    @Override
    public void write(TestResultContainer container) {}

    @Override
    public void write(String source, InputStream attachment) {
      try {
        attachments.add(new String(attachment.readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException ignored) {
        throw new IllegalStateException("Cannot read test attachment");
      }
    }
  }
}
