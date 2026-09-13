package qa.odexa.assertion;

import qa.odexa.model.Order;

/** Small domain assertions, deliberately separate from explicit HTTP expectations. */
public final class OrderAssert {
  private final Order order;

  private OrderAssert(Order order) {
    if (order == null) {
      throw new AssertionError("Order is required");
    }
    this.order = order;
  }

  public static OrderAssert assertThatOrder(Order order) {
    return new OrderAssert(order);
  }

  public OrderAssert hasStatus(String expected) {
    if (expected == null || !expected.equals(order.status())) {
      throw new AssertionError("Unexpected order status");
    }
    return this;
  }

  public OrderAssert hasQuantity(int expected) {
    if (order.quantity() != expected) {
      throw new AssertionError("Unexpected order quantity");
    }
    return this;
  }

  public OrderAssert hasPositiveTotal() {
    if (order.totalMinor() <= 0) {
      throw new AssertionError("Order total must be positive minor units");
    }
    return this;
  }
}
