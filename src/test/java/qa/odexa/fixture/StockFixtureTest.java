package qa.odexa.fixture;

import static org.junit.jupiter.api.Assertions.*;
import static qa.odexa.client.UnitHttpServer.PRODUCT;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qa.odexa.client.InventoryClient;
import qa.odexa.client.OrderClient;
import qa.odexa.client.UnitHttpServer;
import qa.odexa.data.OrderRequest;
import qa.odexa.http.ApiHttp;
import qa.odexa.http.ApiResponse;
import qa.odexa.model.Inventory;
import qa.odexa.model.Order;

class StockFixtureTest {
  @Test
  void requiresBothMutationAndExclusiveOptInsBeforeAnyHttp() throws Exception {
    try (Harness h = new Harness()) {
      assertThrows(
          IllegalStateException.class,
          () -> StockFixture.acquire(h.server.config(false, false), h.inventory, h.orders, 5));
      assertThrows(
          IllegalStateException.class,
          () -> StockFixture.acquire(h.server.config(false, true), h.inventory, h.orders, 5));
      // Configuration may itself reject this unsafe combination before the fixture is called.
      assertThrows(
          RuntimeException.class,
          () -> StockFixture.acquire(h.server.config(true, false), h.inventory, h.orders, 5));
      assertTrue(h.server.requests().isEmpty());
      assertEquals("odexa:tenant-a-inventory", StockFixture.RESOURCE_LOCK);
    }
  }

  @Test
  void refusesNegativeSetupAndPreexistingReservationsWithoutWriting() throws Exception {
    try (Harness h = new Harness()) {
      assertThrows(IllegalArgumentException.class, () -> h.acquire(-1));
      assertTrue(h.server.requests().isEmpty());
      h.stock.set(stock(20, 1, 10));
      assertThrows(IllegalStateException.class, () -> h.acquire(5));
      assertTrue(h.puts().isEmpty());
      try (var journal = h.orders.openCreationJournal()) {
        assertTrue(journal.seal().isEmpty());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"W/\"10\"", "\"9\"", "*", "private-marker"})
  void requiresAnExactStrongInventoryEtagBeforeSetup(String etag) throws Exception {
    try (Harness h = new Harness()) {
      h.inventoryReplies.add(inventoryReply(stock(20, 0, 10)).header("ETag", etag));
      var error = assertThrows(IllegalStateException.class, () -> h.acquire(5));
      assertTrue(h.puts().isEmpty());
      assertFalse(error.toString().contains("private-marker"));
    }
  }

  @Test
  void missingNumericInventoryFieldCannotBeCoercedIntoSafeZeroReservations() throws Exception {
    try (Harness h = new Harness()) {
      h.inventoryReplies.add(
          UnitHttpServer.Reply.json(
                  200, Map.of("productId", PRODUCT, "onHand", 20, "available", 20, "version", 10))
              .header("ETag", "\"10\""));
      assertThrows(IllegalStateException.class, () -> h.acquire(5));
      assertTrue(h.puts().isEmpty());
    }
  }

  @Test
  void noOrderFixtureRestoresOriginalStockConditionallyAndClosesIdempotently() throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      assertEquals("\"10\"", fixture.originalEtag());
      assertEquals(200, fixture.setupResponse().status());
      assertEquals(11, fixture.setupResponse().as(Inventory.class).version());
      fixture.close();
      assertEquals(20, h.stock.get().onHand());
      assertEquals(12, h.stock.get().version());
      assertEquals(2, h.puts().size());
      assertEquals("\"10\"", h.puts().get(0).header("If-Match"));
      assertEquals("\"11\"", h.puts().get(1).header("If-Match"));
      assertEquals(5, h.puts().get(0).json().path("onHand").longValue());
      assertEquals(20, h.puts().get(1).json().path("onHand").longValue());
      int requests = h.server.requests().size();
      fixture.close();
      assertEquals(requests, h.server.requests().size());
      assertThrows(IllegalStateException.class, () -> fixture.track(fixture.setupResponse()));
    }
  }

  @Test
  void tracksDistinctCreatedAndReplayedIdsAndCountsConfirmedQuantityVersusReservedOrders()
      throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      UUID confirmedTwo = UUID.randomUUID();
      UUID confirmedOne = UUID.randomUUID();
      UUID declinedThree = UUID.randomUUID();
      UUID rejectedOne = UUID.randomUUID();
      ApiResponse first = h.create(confirmedTwo, 2, 201, "CONFIRMED");
      fixture.track(first);
      fixture.track(first);
      fixture.track(h.create(confirmedTwo, 2, 200, "CONFIRMED"));
      fixture.track(h.create(confirmedOne, 1, 201, "CONFIRMED"));
      fixture.track(h.create(declinedThree, 3, 201, "PAYMENT_FAILED"));
      fixture.track(h.create(rejectedOne, 1, 201, "STOCK_REJECTED"));
      // Three orders reserved, irrespective of quantity: setupVersion + 2 * 3.
      h.stock.set(stock(2, 0, 17));
      fixture.close();
      assertEquals(4, h.orderReads.size());
      assertTrue(h.orderReads.values().stream().allMatch(count -> count.get() == 1));
      assertEquals("\"17\"", h.puts().getLast().header("If-Match"));
      assertEquals(20, h.stock.get().onHand());
      assertEquals(18, h.stock.get().version());
    }
  }

  @Test
  void rejectedOrderDoesNotConsumeStockOrIncrementExpectedSettlementVersion() throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(0);
      fixture.track(h.create(UUID.randomUUID(), 1, 201, "STOCK_REJECTED"));
      fixture.close();
      assertEquals("\"11\"", h.puts().getLast().header("If-Match"));
      assertEquals(20, h.stock.get().onHand());
    }
  }

  @Test
  void waitsForInventorySettlementAfterOrderAlreadyReachedTerminalState() throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      fixture.track(h.create(UUID.randomUUID(), 2, 201, "CONFIRMED"));
      h.inventoryReplies.add(inventoryReply(stock(5, 0, 11)));
      h.inventoryReplies.add(inventoryReply(stock(5, 2, 12)));
      h.stock.set(stock(3, 0, 13));
      fixture.close();
      assertTrue(
          h.server.requests().stream()
                  .filter(
                      request ->
                          request.method().equals("GET") && request.path().contains("/inventory/"))
                  .count()
              >= 4);
      assertEquals("\"13\"", h.puts().getLast().header("If-Match"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "onHand", "reserved"})
  void refusesToOverwriteForeignOrUnexpectedSettledInventory(String change) throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      fixture.track(h.create(UUID.randomUUID(), 1, 201, "CONFIRMED"));
      Inventory foreign =
          switch (change) {
            case "version" -> stock(4, 0, 14);
            case "onHand" -> stock(5, 0, 13);
            default -> stock(4, 1, 13);
          };
      h.stock.set(foreign);
      var error = assertThrows(IllegalStateException.class, fixture::close);
      assertTrue(error.getMessage().contains("restoration refused"));
      assertEquals(1, h.puts().size());
      assertEquals(foreign, h.stock.get());
    }
  }

  @Test
  void inventoryThatNeverSettlesTimesOutWithoutResettingHeldStock() throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      fixture.track(h.create(UUID.randomUUID(), 1, 201, "CONFIRMED"));
      Inventory held = stock(5, 1, 12);
      h.stock.set(held);
      var error = assertThrows(IllegalStateException.class, fixture::close);
      assertTrue(error.getMessage().contains("Timed out awaiting owned inventory"));
      assertTrue(error.getMessage().contains("reserved=1"));
      assertTrue(error.getMessage().contains("version=12"));
      assertTrue(error.getMessage().contains(UnitHttpServer.CORRELATION));
      assertNull(error.getCause());
      assertEquals(1, h.puts().size());
      assertEquals(held, h.stock.get());
    }
  }

  @Test
  void attemptsEveryKnownOrderButNeverRestoresIfAnyWorkflowRemainsUnsettled() throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      UUID stuck = UUID.randomUUID();
      UUID confirmed = UUID.randomUUID();
      fixture.track(h.create(stuck, 1, 201, "PENDING_PAYMENT"));
      fixture.track(h.create(confirmed, 1, 201, "CONFIRMED"));
      var error = assertThrows(IllegalStateException.class, fixture::close);
      assertTrue(error.getMessage().contains("Uncertain or unsettled"));
      assertTrue(h.orderReads.get(stuck).get() > 1);
      assertEquals(1, h.orderReads.get(confirmed).get());
      assertEquals(1, h.puts().size());
    }
  }

  @Test
  void journalSettlesSuccessfulResponseEvenWhenTestNeverReachedExplicitTrack() throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      UUID id = UUID.randomUUID();
      h.create(id, 1, 201, "CONFIRMED");
      h.stock.set(stock(4, 0, 13));
      fixture.close();
      assertEquals(1, h.orderReads.get(id).get());
      assertEquals("\"13\"", h.puts().getLast().header("If-Match"));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {202, 500, 502, 503, 504})
  void ambiguousPostResponsesRefuseRestoreEvenIfStockHasNotChangedYet(int status) throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      h.creationReplies.add(UnitHttpServer.Reply.raw(status, "private-marker"));
      assertEquals(
          status,
          h.orders.create(OrderRequest.forProduct(PRODUCT), UUID.randomUUID().toString()).status());
      var error = assertThrows(IllegalStateException.class, fixture::close);
      assertEquals(1, h.puts().size());
      assertFalse(error.toString().contains("private-marker"));
      assertNull(error.getCause());
      int requests = h.server.requests().size();
      ApiHttp independentHttp = new ApiHttp(h.server.config(true, true));
      assertThrows(
          IllegalStateException.class,
          () ->
              StockFixture.acquire(
                  h.server.config(true, true),
                  new InventoryClient(independentHttp, null),
                  new OrderClient(independentHttp, null),
                  8));
      assertThrows(IllegalStateException.class, () -> h.acquire(8));
      assertThrows(
          IllegalStateException.class,
          () -> h.orders.create(Map.of(), UUID.randomUUID().toString()));
      assertEquals(requests, h.server.requests().size());
    }
  }

  @Test
  void malformedSuccessAndConflictingDuplicateQuantityPermanentlyMarkCleanupUncertain()
      throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      h.creationReplies.add(UnitHttpServer.Reply.raw(201, "private-marker"));
      ApiResponse response = h.orders.create(Map.of(), UUID.randomUUID().toString());
      var trackError = assertThrows(IllegalStateException.class, () -> fixture.track(response));
      assertFalse(trackError.toString().contains("private-marker"));
      assertThrows(IllegalStateException.class, fixture::close);
      assertEquals(1, h.puts().size());
    }
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      UUID id = UUID.randomUUID();
      fixture.track(h.create(id, 1, 201, "CONFIRMED"));
      ApiResponse conflicting = h.create(id, 2, 200, "CONFIRMED");
      assertThrows(IllegalStateException.class, () -> fixture.track(conflicting));
      assertThrows(IllegalStateException.class, fixture::close);
      assertEquals(1, h.puts().size());
    }
  }

  @Test
  void droppedPostConnectionIsUncertainAndNeverBlindlyRestores() throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      h.creationReplies.add(UnitHttpServer.Reply.raw(0, ""));
      assertThrows(
          RuntimeException.class,
          () -> h.orders.create(OrderRequest.forProduct(PRODUCT), UUID.randomUUID().toString()));
      assertThrows(IllegalStateException.class, fixture::close);
      assertEquals(1, h.puts().size());
    }
  }

  @Test
  void inFlightPostPreventsRestorationEvenBeforeItsResponseExists() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (Harness h = new Harness();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      StockFixture fixture = h.acquire(5);
      h.beforeCreation =
          () -> {
            started.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Unit test did not release request");
              }
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("Unit test interrupted");
            }
          };
      var future = executor.submit(() -> h.create(UUID.randomUUID(), 1, 201, "CONFIRMED"));
      try {
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, fixture::close);
        assertEquals(1, h.puts().size());
      } finally {
        release.countDown();
      }
      assertEquals(201, future.get(5, TimeUnit.SECONDS).status());
    }
  }

  @Test
  void knownRejectedCheckoutIsNotCountedAsAnOrder() throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      for (int status : List.of(400, 401, 403, 404, 409, 415, 422)) {
        h.creationReplies.add(UnitHttpServer.Reply.raw(status, "{}"));
        fixture.track(h.orders.create(Map.of(), UUID.randomUUID().toString()));
      }
      fixture.close();
      assertTrue(h.orderReads.isEmpty());
      assertEquals("\"11\"", h.puts().getLast().header("If-Match"));
    }
  }

  @Test
  void restoreRaceUsesOnlyPreviouslyObservedExpectedEtagAndNeverRetries412() throws Exception {
    try (Harness h = new Harness()) {
      StockFixture fixture = h.acquire(5);
      fixture.track(h.create(UUID.randomUUID(), 1, 201, "CONFIRMED"));
      h.stock.set(stock(4, 0, 13));
      Inventory foreign = stock(9, 0, 14);
      h.beforeRestore = () -> h.stock.set(foreign);
      var error = assertThrows(IllegalStateException.class, fixture::close);
      assertTrue(error.getMessage().contains("http=412"));
      assertEquals(2, h.puts().size());
      assertEquals("\"13\"", h.puts().getLast().header("If-Match"));
      assertEquals(foreign, h.stock.get());
    }
  }

  @Test
  void failedSetupIsNotRetriedOrBlindlyUndone() throws Exception {
    try (Harness h = new Harness()) {
      h.putReplies.add(UnitHttpServer.Reply.raw(412, "private-marker"));
      var error = assertThrows(IllegalStateException.class, () -> h.acquire(5));
      assertFalse(error.toString().contains("private-marker"));
      assertEquals(1, h.puts().size());
      assertEquals(20, h.stock.get().onHand());
    }
    try (Harness h = new Harness()) {
      h.putReplies.add(inventoryReply(stock(5, 0, 12)));
      assertThrows(IllegalStateException.class, () -> h.acquire(5));
      assertEquals(1, h.puts().size());
    }
  }

  @Test
  void nestedFixtureForSameOrderClientIsRejectedWithoutAnotherStockWrite() throws Exception {
    try (Harness h = new Harness();
        StockFixture fixture = h.acquire(5)) {
      assertThrows(IllegalStateException.class, () -> h.acquire(3));
      assertEquals(1, h.puts().size());
      assertEquals(200, fixture.setupResponse().status());
    }
  }

  @Test
  void separateFixturesHaveNoSharedCleanupState() throws Exception {
    try (Harness first = new Harness();
        Harness second = new Harness()) {
      StockFixture one = first.acquire(5);
      StockFixture two = second.acquire(0);
      one.track(first.create(UUID.randomUUID(), 1, 201, "CONFIRMED"));
      two.track(second.create(UUID.randomUUID(), 1, 201, "STOCK_REJECTED"));
      first.stock.set(stock(4, 0, 13));
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var a = executor.submit(one::close);
        var b = executor.submit(two::close);
        a.get();
        b.get();
      }
      assertEquals("\"13\"", first.puts().getLast().header("If-Match"));
      assertEquals("\"11\"", second.puts().getLast().header("If-Match"));
    }
  }

  private static Inventory stock(long onHand, long reserved, long version) {
    return new Inventory(PRODUCT, onHand, reserved, onHand - reserved, version);
  }

  private static UnitHttpServer.Reply inventoryReply(Inventory stock) {
    return UnitHttpServer.Reply.json(200, stock).header("ETag", "\"" + stock.version() + "\"");
  }

  /** Scripted public snapshots, not a copy of product implementation or event handling. */
  private static final class Harness implements AutoCloseable {
    private final AtomicReference<Inventory> stock = new AtomicReference<>(stock(20, 0, 10));
    private final ConcurrentLinkedQueue<UnitHttpServer.Reply> inventoryReplies =
        new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<UnitHttpServer.Reply> creationReplies =
        new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<UnitHttpServer.Reply> putReplies =
        new ConcurrentLinkedQueue<>();
    private final Map<UUID, UnitHttpServer.Reply> orderReplies = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> orderReads = new ConcurrentHashMap<>();
    private final AtomicInteger writes = new AtomicInteger();
    private final UnitHttpServer server;
    private final InventoryClient inventory;
    private final OrderClient orders;
    private volatile Runnable beforeCreation = () -> {};
    private volatile Runnable beforeRestore = () -> {};

    private Harness() throws IOException {
      server = new UnitHttpServer(this::handle);
      ApiHttp http = new ApiHttp(server.config(true, true));
      inventory = new InventoryClient(http, null);
      orders = new OrderClient(http, null);
    }

    private StockFixture acquire(long count) {
      return StockFixture.acquire(server.config(true, true), inventory, orders, count);
    }

    private ApiResponse create(UUID id, int quantity, int status, String terminal) {
      Order created =
          new Order(id, PRODUCT, quantity, 150L * quantity, "USD", "CREATED", 0, Instant.EPOCH);
      orderReplies.put(
          id,
          UnitHttpServer.Reply.json(
              200,
              new Order(
                  id, PRODUCT, quantity, 150L * quantity, "USD", terminal, 2, Instant.EPOCH)));
      creationReplies.add(UnitHttpServer.Reply.json(status, created));
      return orders.create(
          OrderRequest.forProduct(PRODUCT).withQuantity(quantity), UUID.randomUUID().toString());
    }

    private List<UnitHttpServer.Request> puts() {
      return server.requests().stream().filter(request -> request.method().equals("PUT")).toList();
    }

    private UnitHttpServer.Reply handle(UnitHttpServer.Request request) {
      if (request.path().equals("/api/v1/inventory/" + PRODUCT)) {
        if (request.method().equals("GET")) {
          UnitHttpServer.Reply scripted = inventoryReplies.poll();
          return scripted == null ? inventoryReply(stock.get()) : scripted;
        }
        if (request.method().equals("PUT")) {
          if (writes.incrementAndGet() > 1) {
            beforeRestore.run();
          }
          UnitHttpServer.Reply scripted = putReplies.poll();
          if (scripted != null) {
            return scripted;
          }
          Inventory current = stock.get();
          if (!("\"" + current.version() + "\"").equals(request.header("If-Match"))) {
            return UnitHttpServer.Reply.raw(412, "{}");
          }
          try {
            Inventory updated =
                stock(request.json().path("onHand").longValue(), 0, current.version() + 1);
            stock.set(updated);
            return inventoryReply(updated);
          } catch (IOException ignored) {
            throw new IllegalStateException("Invalid unit request");
          }
        }
      }
      if (request.path().equals("/api/v1/orders") && request.method().equals("POST")) {
        beforeCreation.run();
        UnitHttpServer.Reply scripted = creationReplies.poll();
        return scripted == null ? UnitHttpServer.Reply.raw(500, "{}") : scripted;
      }
      if (request.path().startsWith("/api/v1/orders/") && request.method().equals("GET")) {
        UUID id = UUID.fromString(request.path().substring("/api/v1/orders/".length()));
        orderReads.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
        return orderReplies.getOrDefault(id, UnitHttpServer.Reply.raw(404, "{}"));
      }
      return UnitHttpServer.Reply.raw(404, "{}");
    }

    @Override
    public void close() {
      server.close();
    }
  }
}
