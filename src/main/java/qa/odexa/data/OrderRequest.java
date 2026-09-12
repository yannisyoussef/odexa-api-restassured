package qa.odexa.data;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable request data; quantity is deliberately not validated to support negative HTTP tests.
 */
public record OrderRequest(UUID productId, int quantity, String paymentMethod) {
  public static OrderRequest forProduct(UUID productId) {
    return new OrderRequest(Objects.requireNonNull(productId, "productId"), 1, "pm_approved");
  }

  public OrderRequest withQuantity(int quantity) {
    return new OrderRequest(productId, quantity, paymentMethod);
  }

  public OrderRequest declined() {
    return new OrderRequest(productId, quantity, "pm_declined");
  }

  @Override
  public String toString() {
    return "OrderRequest[productId=" + productId + ", quantity=" + quantity + "]";
  }
}
