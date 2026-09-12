package qa.odexa.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderRequestTest {
  @Test
  void variantsNeverMutateTheOriginalAndPermitInvalidQuantityTests() {
    UUID product = UUID.randomUUID();
    OrderRequest original = OrderRequest.forProduct(product);
    OrderRequest quantity = original.withQuantity(0);
    OrderRequest declined = original.declined();
    assertEquals(product, original.productId());
    assertEquals(1, original.quantity());
    assertEquals("pm_approved", original.paymentMethod());
    assertEquals(0, quantity.quantity());
    assertEquals("pm_declined", declined.paymentMethod());
    assertEquals(1, declined.quantity());
    assertNotSame(original, quantity);
    assertNotSame(original, declined);
    assertNotSame(declined, declined.declined());
  }

  @Test
  void requestDiagnosticsOmitPaymentMethodEvenIfItContainsAnUnexpectedValue() {
    OrderRequest request = new OrderRequest(UUID.randomUUID(), 1, "untrusted-private-marker");
    assertFalse(request.toString().contains("untrusted-private-marker"));
    assertFalse(request.toString().contains("paymentMethod"));
  }
}
