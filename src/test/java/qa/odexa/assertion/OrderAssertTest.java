package qa.odexa.assertion;

import static org.junit.jupiter.api.Assertions.*;
import static qa.odexa.assertion.OrderAssert.assertThatOrder;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import qa.odexa.model.Order;

class OrderAssertTest {
  @Test
  void focusedAssertionsAreChainableAndCheckPositiveMinorUnits() {
    Order order = order("CONFIRMED", 250);
    assertThatOrder(order).hasStatus("CONFIRMED").hasQuantity(2).hasPositiveTotal();
    assertThrows(AssertionError.class, () -> assertThatOrder(order).hasQuantity(1));
    assertThrows(
        AssertionError.class, () -> assertThatOrder(order("CREATED", 0)).hasPositiveTotal());
    assertThrows(AssertionError.class, () -> assertThatOrder(null));
  }

  @Test
  void unexpectedServerStringsNeverAppearInAssertionFailures() {
    var error =
        assertThrows(
            AssertionError.class,
            () -> assertThatOrder(order("private-marker", 250)).hasStatus("CONFIRMED"));
    assertFalse(error.toString().contains("private-marker"));
    assertNull(error.getCause());
  }

  private static Order order(String state, long total) {
    return new Order(
        UUID.randomUUID(), UUID.randomUUID(), 2, total, "USD", state, 2, Instant.EPOCH);
  }
}
