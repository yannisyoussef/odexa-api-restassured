package qa.odexa.wait;

import static org.junit.jupiter.api.Assertions.*;
import static qa.odexa.client.UnitHttpServer.PRODUCT;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qa.odexa.client.OrderClient;
import qa.odexa.client.UnitHttpServer;
import qa.odexa.model.Order;

class OrderAwaiterTest {
  @Test
  void retriesOnlyTransientGatewayResponsesAndObservesAsynchronousTransitions() throws Exception {
    UUID id = UUID.randomUUID();
    AtomicInteger attempts = new AtomicInteger();
    try (UnitHttpServer server =
        new UnitHttpServer(
            request -> {
              int attempt = attempts.getAndIncrement();
              return switch (attempt) {
                case 0 -> UnitHttpServer.Reply.raw(502, "private-marker");
                case 1 -> UnitHttpServer.Reply.raw(503, "private-marker");
                case 2 -> UnitHttpServer.Reply.raw(504, "private-marker");
                case 3 -> reply(id, "CREATED");
                case 4 -> reply(id, "PENDING_PAYMENT");
                default -> reply(id, "CONFIRMED");
              };
            })) {
      assertEquals("CONFIRMED", awaiter(server).untilStatus(id, "CONFIRMED").status());
      assertEquals(6, attempts.get());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"CONFIRMED", "STOCK_REJECTED", "PAYMENT_FAILED"})
  void acceptsEachDocumentedTerminalState(String state) throws Exception {
    UUID id = UUID.randomUUID();
    try (UnitHttpServer server = new UnitHttpServer(request -> reply(id, state))) {
      assertEquals(state, awaiter(server).untilTerminal(id).status());
      assertEquals(1, server.requests().size());
    }
  }

  @Test
  void unexpectedTerminalFailsImmediatelyWithOnlySafeMetadata() throws Exception {
    UUID id = UUID.randomUUID();
    try (UnitHttpServer server = new UnitHttpServer(request -> reply(id, "PAYMENT_FAILED"))) {
      var error =
          assertThrows(
              IllegalStateException.class, () -> awaiter(server).untilStatus(id, "CONFIRMED"));
      assertEquals(1, server.requests().size());
      assertTrue(error.getMessage().contains("Unexpected terminal"));
      assertTrue(error.getMessage().contains("state=PAYMENT_FAILED"));
      assertTrue(error.getMessage().contains(UnitHttpServer.CORRELATION));
      assertNull(error.getCause());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 429, 500})
  void nontransientHttpFailuresAreNeverPolledAgain(int status) throws Exception {
    try (UnitHttpServer server =
        new UnitHttpServer(request -> UnitHttpServer.Reply.raw(status, "private-marker"))) {
      var error =
          assertThrows(
              IllegalStateException.class, () -> awaiter(server).untilTerminal(UUID.randomUUID()));
      assertEquals(1, server.requests().size());
      assertTrue(error.getMessage().contains("http=" + status));
      assertFalse(error.toString().contains("private-marker"));
      assertNull(error.getCause());
    }
  }

  @Test
  void timeoutReportsLastSafeHttpStateAndCorrelationNotBody() throws Exception {
    UUID id = UUID.randomUUID();
    try (UnitHttpServer server = new UnitHttpServer(request -> reply(id, "PENDING_PAYMENT"))) {
      var error =
          assertThrows(IllegalStateException.class, () -> awaiter(server).untilTerminal(id));
      assertTrue(error.getMessage().contains("Timed out"));
      assertTrue(error.getMessage().contains("http=200"));
      assertTrue(error.getMessage().contains("state=PENDING_PAYMENT"));
      assertTrue(error.getMessage().contains(UnitHttpServer.CORRELATION));
      assertTrue(server.requests().size() > 1);
      assertNull(error.getCause());
    }
  }

  @Test
  void retryableFailuresStillTimeOutAndMaliciousCorrelationIsNotPrinted() throws Exception {
    try (UnitHttpServer server =
        new UnitHttpServer(
            request ->
                UnitHttpServer.Reply.raw(503, "private-marker")
                    .header("X-Correlation-ID", "private-marker"))) {
      var error =
          assertThrows(
              IllegalStateException.class, () -> awaiter(server).untilTerminal(UUID.randomUUID()));
      assertTrue(error.getMessage().contains("http=503"));
      assertTrue(error.getMessage().contains("correlation=unavailable"));
      assertFalse(error.toString().contains("private-marker"));
      assertNull(error.getCause());
    }
  }

  @Test
  void malformedJsonUnknownStateAndMismatchedIdFailSafely() throws Exception {
    UUID id = UUID.randomUUID();
    for (var reply :
        java.util.List.of(
            UnitHttpServer.Reply.raw(200, "private-marker"),
            reply(id, "private-marker"),
            reply(UUID.randomUUID(), "CONFIRMED"))) {
      try (UnitHttpServer server = new UnitHttpServer(request -> reply)) {
        var error =
            assertThrows(IllegalStateException.class, () -> awaiter(server).untilTerminal(id));
        assertEquals(1, server.requests().size());
        assertFalse(error.toString().contains("private-marker"));
        assertNull(error.getCause());
      }
    }
  }

  @Test
  void unknownExpectedStateFailsBeforeHttp() throws Exception {
    try (UnitHttpServer server =
        new UnitHttpServer(request -> UnitHttpServer.Reply.raw(500, "{}"))) {
      var error =
          assertThrows(
              IllegalArgumentException.class,
              () -> awaiter(server).untilStatus(UUID.randomUUID(), "private-marker"));
      assertTrue(server.requests().isEmpty());
      assertFalse(error.toString().contains("private-marker"));
    }
  }

  @Test
  void concurrentWaitsKeepObservationsIndependent() throws Exception {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    try (UnitHttpServer server =
            new UnitHttpServer(
                request ->
                    request.path().endsWith(first.toString())
                        ? reply(first, "CONFIRMED")
                        : reply(second, "STOCK_REJECTED"));
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var awaiter = awaiter(server);
      var one = executor.submit(() -> awaiter.untilTerminal(first));
      var two = executor.submit(() -> awaiter.untilTerminal(second));
      assertEquals("CONFIRMED", one.get().status());
      assertEquals("STOCK_REJECTED", two.get().status());
    }
  }

  private static OrderAwaiter awaiter(UnitHttpServer server) {
    return new OrderAwaiter(new OrderClient(server.http(), null), server.config(false, false));
  }

  private static UnitHttpServer.Reply reply(UUID id, String state) {
    return UnitHttpServer.Reply.json(
        200, new Order(id, PRODUCT, 1, 150, "USD", state, 2, Instant.EPOCH));
  }
}
