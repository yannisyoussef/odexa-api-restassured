package qa.odexa.client;

import static org.junit.jupiter.api.Assertions.*;
import static qa.odexa.client.UnitHttpServer.PRODUCT;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import qa.odexa.data.OrderRequest;
import qa.odexa.http.ApiHttp;

class DomainClientsTest {
  @Test
  void readsUseOnlyPublicGatewayRoutesAndPreserveQueriesAndConditionalHeaders() throws Exception {
    try (UnitHttpServer server =
        new UnitHttpServer(request -> UnitHttpServer.Reply.raw(200, "{}"))) {
      ApiHttp http = server.http();
      CatalogClient catalog = new CatalogClient(http, null);
      catalog.get(PRODUCT);
      catalog.get(PRODUCT, Map.of("If-None-Match", "\"3\""));
      catalog.list(Map.of("limit", 2, "q", "a b"));
      new InventoryClient(http, null).get(PRODUCT);
      new OrderClient(http, null).get(PRODUCT);
      new PaymentClient(http, null).get(PRODUCT);
      var requests = server.requests();
      assertEquals(6, requests.size());
      assertEquals("/api/v1/products/" + PRODUCT, requests.get(0).path());
      assertEquals("\"3\"", requests.get(1).header("If-None-Match"));
      assertNull(requests.get(0).header("If-None-Match"));
      assertEquals("/api/v1/products", requests.get(2).path());
      String decodedQuery =
          java.net.URLDecoder.decode(
              requests.get(2).query(), java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(decodedQuery.contains("limit=2"));
      assertTrue(decodedQuery.contains("q=a b"));
      assertEquals("/api/v1/inventory/" + PRODUCT, requests.get(3).path());
      assertEquals("/api/v1/orders/" + PRODUCT, requests.get(4).path());
      assertEquals("/api/v1/payments/" + PRODUCT, requests.get(5).path());
      assertTrue(requests.stream().allMatch(request -> "GET".equals(request.method())));
    }
  }

  @Test
  void writesForwardTypedAndRawBodiesAndOmitNullPreconditionsWithoutBusinessAssertions()
      throws Exception {
    try (UnitHttpServer server =
        new UnitHttpServer(request -> UnitHttpServer.Reply.raw(428, "{}"))) {
      ApiHttp http = server.http();
      CatalogClient catalog = new CatalogClient(http, null);
      assertEquals(428, catalog.create("{invalid}").status());
      catalog.replace(PRODUCT, Map.of("name", "unit product"), "\"5\"");
      catalog.replace(PRODUCT, Map.of(), null);
      InventoryClient inventory = new InventoryClient(http, null);
      inventory.adjust(PRODUCT, 7, "\"6\"");
      inventory.adjust(PRODUCT, -1, null);
      var requests = server.requests();
      assertEquals("POST", requests.get(0).method());
      assertEquals("{invalid}", requests.get(0).body());
      assertEquals("PUT", requests.get(1).method());
      assertEquals("\"5\"", requests.get(1).header("If-Match"));
      assertEquals("unit product", requests.get(1).json().path("name").textValue());
      assertNull(requests.get(2).header("If-Match"));
      assertEquals(7, requests.get(3).json().path("onHand").longValue());
      assertEquals("\"6\"", requests.get(3).header("If-Match"));
      assertEquals(-1, requests.get(4).json().path("onHand").longValue());
      assertNull(requests.get(4).header("If-Match"));
    }
  }

  @Test
  void orderKeysAndImmutableDataAreForwardedAndNoResponseStatusIsAsserted() throws Exception {
    try (UnitHttpServer server =
        new UnitHttpServer(request -> UnitHttpServer.Reply.raw(409, "{}"))) {
      OrderClient orders = new OrderClient(server.http(), null);
      String key = UUID.randomUUID().toString();
      assertEquals(409, orders.create(OrderRequest.forProduct(PRODUCT), key).status());
      orders.create("{bad}", null);
      assertEquals(key, server.requests().getFirst().header("Idempotency-Key"));
      assertEquals(
          "pm_approved", server.requests().getFirst().json().path("paymentMethod").textValue());
      assertNull(server.requests().get(1).header("Idempotency-Key"));
      assertEquals("{bad}", server.requests().get(1).body());
    }
  }

  @Test
  void sealingWhileCheckoutIsInFlightPermanentlyPreventsUnsafeRestoration() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (UnitHttpServer server =
        new UnitHttpServer(
            request -> {
              entered.countDown();
              try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Unit response deadline exceeded");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Unit response interrupted");
              }
              return UnitHttpServer.Reply.raw(201, "{}");
            })) {
      OrderClient orders = new OrderClient(server.http(), null);
      try (var journal = orders.openCreationJournal();
          var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var pending = executor.submit(() -> orders.create(Map.of(), "in-flight"));
        try {
          assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
          assertTrue(journal.seal().isEmpty());
        } finally {
          release.countDown();
        }
        assertEquals(201, pending.get(5, java.util.concurrent.TimeUnit.SECONDS).status());
        assertTrue(journal.hasUncertainRequests());
      }
      assertThrows(IllegalStateException.class, orders::openCreationJournal);
    }
  }

  @Test
  void perClientJournalCapturesConcurrentResponsesAndSealsUntilReleased() throws Exception {
    try (UnitHttpServer server =
        new UnitHttpServer(request -> UnitHttpServer.Reply.raw(201, "{}"))) {
      OrderClient orders = new OrderClient(server.http(), null);
      try (OrderClient.CreationJournal journal = orders.openCreationJournal()) {
        assertThrows(IllegalStateException.class, orders::openCreationJournal);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
          var tasks =
              java.util.stream.IntStream.range(0, 4)
                  .<java.util.concurrent.Callable<Integer>>mapToObj(
                      i -> () -> orders.create(Map.of(), UUID.randomUUID().toString()).status())
                  .toList();
          for (var future : executor.invokeAll(tasks)) {
            assertEquals(201, future.get());
          }
        }
        assertEquals(4, journal.seal().size());
        assertFalse(journal.hasUncertainRequests());
        assertThrows(IllegalStateException.class, () -> orders.create(Map.of(), "sealed"));
        assertEquals(4, server.requests().size());
      }
      try (OrderClient.CreationJournal next = orders.openCreationJournal()) {
        assertTrue(next.seal().isEmpty());
      }
    }
  }
}
