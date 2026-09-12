package qa.odexa.wait;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import qa.odexa.client.OrderClient;
import qa.odexa.config.TargetConfig;
import qa.odexa.http.ApiResponse;
import qa.odexa.model.Order;

/** Bounded public HTTP polling; no retry of checkout writes and no raw server text in failures. */
public final class OrderAwaiter {
  private static final Set<String> STATES =
      Set.of("CREATED", "PENDING_PAYMENT", "CONFIRMED", "STOCK_REJECTED", "PAYMENT_FAILED");
  private static final Set<String> TERMINAL =
      Set.of("CONFIRMED", "STOCK_REJECTED", "PAYMENT_FAILED");
  private final OrderClient orders;
  private final Duration timeout;
  private final Duration interval;

  public OrderAwaiter(OrderClient orders, TargetConfig config) {
    this.orders = Objects.requireNonNull(orders, "orders");
    Objects.requireNonNull(config, "config");
    timeout = config.pollTimeout();
    interval = config.pollInterval();
  }

  public Order untilStatus(UUID id, String expected) {
    if (expected == null || !STATES.contains(expected)) {
      throw new IllegalArgumentException("Expected order state must be a public contract state");
    }
    return poll(Objects.requireNonNull(id, "id"), expected);
  }

  public Order untilTerminal(UUID id) {
    return poll(Objects.requireNonNull(id, "id"), null);
  }

  private Order poll(UUID id, String expected) {
    Observation last = new Observation();
    try {
      // Each invocation owns its observation; parallel waiters share no mutable poll state.
      await()
          .dontCatchUncaughtExceptions()
          .pollDelay(Duration.ZERO)
          .pollInterval(interval)
          .atMost(timeout)
          .until(
              () -> {
                ApiResponse response;
                try {
                  response = orders.get(id);
                } catch (RuntimeException ignored) {
                  throw failure("Order read failed", last);
                }
                last.httpStatus = response.status();
                last.correlation = safeCorrelation(response.header("X-Correlation-ID"));
                if (last.httpStatus == 502 || last.httpStatus == 503 || last.httpStatus == 504) {
                  return false;
                }
                if (last.httpStatus != 200) {
                  throw failure("Nonretryable order response", last);
                }
                Order order;
                try {
                  order = response.as(Order.class);
                } catch (RuntimeException ignored) {
                  throw failure("Unreadable order response", last);
                }
                if (order == null
                    || !id.equals(order.id())
                    || order.status() == null
                    || !STATES.contains(order.status())) {
                  last.state = "UNKNOWN";
                  throw failure("Invalid order snapshot", last);
                }
                last.order = order;
                last.state = order.status();
                if (expected == null) {
                  return TERMINAL.contains(order.status());
                }
                if (expected.equals(order.status())) {
                  return true;
                }
                if (TERMINAL.contains(order.status())) {
                  throw failure("Unexpected terminal order state", last);
                }
                return false;
              });
      return last.order;
    } catch (ConditionTimeoutException ignored) {
      throw failure("Timed out awaiting order", last);
    }
  }

  private static IllegalStateException failure(String reason, Observation last) {
    return new IllegalStateException(
        reason
            + "; http="
            + last.httpStatus
            + "; state="
            + last.state
            + "; correlation="
            + last.correlation);
  }

  private static String safeCorrelation(String value) {
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

  private static final class Observation {
    private volatile int httpStatus;
    private volatile String state = "UNOBSERVED";
    private volatile String correlation = "unavailable";
    private volatile Order order;
  }
}
