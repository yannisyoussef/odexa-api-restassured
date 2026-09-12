package qa.odexa.fixture;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import qa.odexa.client.InventoryClient;
import qa.odexa.client.OrderClient;
import qa.odexa.config.TargetConfig;
import qa.odexa.http.ApiResponse;
import qa.odexa.model.Inventory;
import qa.odexa.model.Order;
import qa.odexa.wait.OrderAwaiter;

/**
 * Per-test public-HTTP fixture for an explicitly dedicated inventory environment. Every mutating
 * API test must use {@code @ResourceLock("odexa:tenant-a-inventory")}; this is not a distributed
 * lock. Use the supplied OrderClient for every checkout while acquired, and join all checkout tasks
 * before close. Orders remain retained: v0.1.0 has no public delete/cancel operation. Uncertain
 * workflows or foreign stock/version changes deliberately leave inventory untouched and fail
 * cleanup. An unsafe cleanup also blocks further checkouts/fixtures on that OrderClient, so a
 * following test cannot overwrite stock while an earlier uncertain workflow may still arrive.
 */
public final class StockFixture implements AutoCloseable {
  public static final String RESOURCE_LOCK = "odexa:tenant-a-inventory";
  private static final Set<Integer> REJECTED_WITHOUT_CREATION =
      Set.of(400, 401, 403, 404, 409, 415, 422);
  private final TargetConfig config;
  private final InventoryClient inventory;
  private final OrderAwaiter awaiter;
  private final OrderClient.CreationJournal journal;
  private final Inventory original;
  private final Inventory setup;
  private final String originalEtag;
  private final ApiResponse setupResponse;
  private final Map<UUID, Integer> tracked = new LinkedHashMap<>();
  private boolean uncertain;
  private boolean closed;

  private StockFixture(
      TargetConfig config,
      InventoryClient inventory,
      OrderClient orders,
      OrderClient.CreationJournal journal,
      Inventory original,
      String originalEtag,
      Inventory setup,
      ApiResponse setupResponse) {
    this.config = config;
    this.inventory = inventory;
    this.awaiter = new OrderAwaiter(orders, config);
    this.journal = journal;
    this.original = original;
    this.originalEtag = originalEtag;
    this.setup = setup;
    this.setupResponse = setupResponse;
  }

  public static StockFixture acquire(
      TargetConfig config,
      InventoryClient merchantInventory,
      OrderClient customerOrders,
      long setupStock) {
    Objects.requireNonNull(config, "config");
    if (!config.allowMutation() || !config.exclusiveFixtures()) {
      throw failure("Mutation requires allowMutation and exclusiveFixtures");
    }
    if (setupStock < 0) {
      throw new IllegalArgumentException("setupStock must be nonnegative");
    }
    Objects.requireNonNull(merchantInventory, "merchantInventory");
    Objects.requireNonNull(customerOrders, "customerOrders");
    OrderClient.CreationJournal journal = customerOrders.openCreationJournal();
    boolean acquired = false;
    boolean writeAttempted = false;
    try {
      ApiResponse before = getInventory(merchantInventory, config.productId());
      Inventory original = readInventory(before, config.productId());
      if (original.reserved() != 0) {
        throw failure("Active reservations prevent stock setup");
      }
      long setupVersion = increment(original.version(), 1);
      writeAttempted = true;
      ApiResponse response =
          adjust(merchantInventory, config.productId(), setupStock, before.header("ETag"));
      Inventory setup = readInventory(response, config.productId());
      if (setup.onHand() != setupStock
          || setup.reserved() != 0
          || setup.version() != setupVersion) {
        throw failure("Setup state is uncertain; restoration refused");
      }
      StockFixture fixture =
          new StockFixture(
              config,
              merchantInventory,
              customerOrders,
              journal,
              original,
              before.header("ETag"),
              setup,
              response);
      acquired = true;
      return fixture;
    } finally {
      if (!acquired) {
        // Never retry or blindly undo a PUT whose outcome may be ambiguous.
        if (writeAttempted) {
          journal.abandon();
        }
        journal.close();
      }
    }
  }

  public String originalEtag() {
    return originalEtag;
  }

  public ApiResponse setupResponse() {
    return setupResponse;
  }

  /** Idempotent tracking: exact replays count once, regardless of order status when returned. */
  public synchronized void track(ApiResponse response) {
    if (closed) {
      throw failure("Stock fixture is closed");
    }
    recordCreation(response);
  }

  private void recordCreation(ApiResponse response) {
    if (response == null) {
      uncertain = true;
      throw failure("Missing order creation response; restoration refused");
    }
    if (response.status() != 200 && response.status() != 201) {
      if (!REJECTED_WITHOUT_CREATION.contains(response.status())) {
        uncertain = true;
        throw failure("Ambiguous order creation HTTP status; restoration refused");
      }
      return;
    }
    Order order;
    try {
      JsonNode body = response.json();
      if (!body.path("id").isTextual()
          || !body.path("productId").isTextual()
          || !body.path("quantity").isIntegralNumber()
          || !body.path("quantity").canConvertToInt()) {
        throw new IllegalArgumentException();
      }
      order = response.as(Order.class);
    } catch (RuntimeException ignored) {
      uncertain = true;
      throw failure("Unreadable order creation response; restoration refused");
    }
    if (order == null
        || order.id() == null
        || !config.productId().equals(order.productId())
        || order.quantity() < 1
        || order.quantity() > 100) {
      uncertain = true;
      throw failure("Invalid tracked order identity or quantity; restoration refused");
    }
    Integer previous = tracked.putIfAbsent(order.id(), order.quantity());
    if (previous != null && previous != order.quantity()) {
      uncertain = true;
      throw failure("Conflicting tracked order quantity; restoration refused");
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    boolean safelyRestored = false;
    try {
      List<ApiResponse> completed = journal.seal();
      uncertain |= journal.hasUncertainRequests();
      for (ApiResponse response : completed) {
        try {
          recordCreation(response);
        } catch (IllegalStateException ignored) {
          uncertain = true;
        }
      }
      long confirmedQuantity = 0;
      long reservedOrders = 0;
      boolean unsettled = false;
      // Attempt every known order even if one is stuck; never restore after any uncertain outcome.
      for (Map.Entry<UUID, Integer> entry : tracked.entrySet()) {
        Order terminal;
        try {
          terminal = awaiter.untilTerminal(entry.getKey());
        } catch (RuntimeException ignored) {
          unsettled = true;
          continue;
        }
        if (!config.productId().equals(terminal.productId())
            || terminal.quantity() != entry.getValue()) {
          unsettled = true;
          continue;
        }
        if ("CONFIRMED".equals(terminal.status())) {
          confirmedQuantity = increment(confirmedQuantity, terminal.quantity());
          reservedOrders++;
        } else if ("PAYMENT_FAILED".equals(terminal.status())) {
          reservedOrders++;
        } else if (!"STOCK_REJECTED".equals(terminal.status())) {
          unsettled = true;
        }
      }
      if (uncertain || unsettled) {
        throw failure("Uncertain or unsettled order workflows; restoration refused");
      }
      if (confirmedQuantity > setup.onHand()) {
        throw failure("Tracked orders exceed owned stock; restoration refused");
      }
      long expectedOnHand = setup.onHand() - confirmedQuantity;
      // Pinned inventory HTTP contract: one version for reservation, one for commitment/release.
      long expectedVersion = increment(setup.version(), Math.multiplyExact(2L, reservedOrders));
      ApiResponse settled = awaitOwnedInventory(expectedOnHand, expectedVersion);
      // The ETag was validated against exactly expectedVersion. A racing foreign write yields 412;
      // never fetch a newer ETag and retry this restoration.
      long restoredVersion = increment(expectedVersion, 1);
      ApiResponse restored =
          adjust(inventory, config.productId(), original.onHand(), settled.header("ETag"));
      Inventory restoredStock = readInventory(restored, config.productId());
      if (restoredStock.onHand() != original.onHand()
          || restoredStock.reserved() != 0
          || restoredStock.version() != restoredVersion) {
        throw failure("Restoration result is uncertain; no further write attempted");
      }
      safelyRestored = true;
    } finally {
      if (!safelyRestored) {
        journal.abandon();
      }
      journal.close();
    }
  }

  private ApiResponse awaitOwnedInventory(long expectedOnHand, long expectedVersion) {
    StockObservation last = new StockObservation();
    try {
      await()
          .dontCatchUncaughtExceptions()
          .pollDelay(Duration.ZERO)
          .pollInterval(config.pollInterval())
          .atMost(config.pollTimeout())
          .until(
              () -> {
                ApiResponse response = getInventory(inventory, config.productId());
                last.status = response.status();
                last.correlation = correlation(response);
                if (last.status == 502 || last.status == 503 || last.status == 504) {
                  return false;
                }
                Inventory stock = readInventory(response, config.productId());
                last.stock = stock;
                if (stock.version() > expectedVersion
                    || stock.version() < setup.version()
                    || stock.onHand() < expectedOnHand
                    || stock.onHand() > setup.onHand()) {
                  throw failure("Foreign inventory change; restoration refused; " + last.summary());
                }
                if (stock.version() == expectedVersion) {
                  if (stock.onHand() != expectedOnHand || stock.reserved() != 0) {
                    throw failure(
                        "Unexpected settled inventory; restoration refused; " + last.summary());
                  }
                  last.response = response;
                  return true;
                }
                return false;
              });
      return last.response;
    } catch (ConditionTimeoutException ignored) {
      throw failure("Timed out awaiting owned inventory; restoration refused; " + last.summary());
    }
  }

  private static ApiResponse getInventory(InventoryClient client, UUID productId) {
    try {
      return client.get(productId);
    } catch (RuntimeException ignored) {
      throw failure("Inventory read failed; no compensating write attempted");
    }
  }

  private static ApiResponse adjust(
      InventoryClient client, UUID productId, long onHand, String etag) {
    try {
      return client.adjust(productId, onHand, etag);
    } catch (RuntimeException ignored) {
      throw failure("Inventory write outcome is uncertain; no retry attempted");
    }
  }

  private static Inventory readInventory(ApiResponse response, UUID productId) {
    if (response.status() != 200) {
      throw failure(
          "Inventory response rejected; http="
              + response.status()
              + "; correlation="
              + correlation(response));
    }
    Inventory stock;
    try {
      JsonNode body = response.json();
      for (String field : List.of("onHand", "reserved", "available", "version")) {
        JsonNode value = body.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
          throw new IllegalArgumentException();
        }
      }
      stock = response.as(Inventory.class);
    } catch (RuntimeException ignored) {
      throw failure("Unreadable inventory snapshot; restoration refused");
    }
    if (stock == null
        || !productId.equals(stock.productId())
        || stock.version() < 1
        || stock.onHand() < 0
        || stock.reserved() < 0
        || stock.reserved() > stock.onHand()
        || stock.available() != stock.onHand() - stock.reserved()
        || !("\"" + stock.version() + "\"").equals(response.header("ETag"))) {
      throw failure("Invalid inventory snapshot or ETag; restoration refused");
    }
    return stock;
  }

  private static long increment(long base, long amount) {
    try {
      return Math.addExact(base, amount);
    } catch (ArithmeticException ignored) {
      throw failure("Inventory accounting overflow; restoration refused");
    }
  }

  private static IllegalStateException failure(String message) {
    return new IllegalStateException(message);
  }

  private static String correlation(ApiResponse response) {
    String value = response.header("X-Correlation-ID");
    if (value == null || value.length() != 36) {
      return "unavailable";
    }
    try {
      String canonical = UUID.fromString(value).toString();
      return canonical.equalsIgnoreCase(value) ? canonical : "unavailable";
    } catch (IllegalArgumentException ignored) {
      return "unavailable";
    }
  }

  private static final class StockObservation {
    private volatile int status;
    private volatile String correlation = "unavailable";
    private volatile Inventory stock;
    private volatile ApiResponse response;

    private String summary() {
      Inventory value = stock;
      return "http="
          + status
          + "; correlation="
          + correlation
          + (value == null
              ? "; stock=UNOBSERVED"
              : "; onHand="
                  + value.onHand()
                  + "; reserved="
                  + value.reserved()
                  + "; version="
                  + value.version());
    }
  }
}
