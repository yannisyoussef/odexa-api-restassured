package qa.odexa.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelsTest {
  @Test
  void productPageDefensivelyCopiesItsItems() {
    var items = new ArrayList<Product>();
    items.add(new Product(UUID.randomUUID(), "unit", "", 100, "USD", true, 1));
    ProductPage page = new ProductPage(items, null);
    items.clear();
    assertEquals(1, page.items().size());
    assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
  }

  @Test
  void jacksonRoundTripsRecordTypesIncludingInstantsAndLongMinorUnits() throws Exception {
    var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Order order = new Order(id, id, 2, 8_000_000_000L, "USD", "CONFIRMED", 2, now);
    Payment payment = new Payment(id, id, order.totalMinor(), "USD", "AUTHORIZED", now, now);
    Inventory inventory = new Inventory(id, 8, 2, 6, 3);
    assertEquals(order, mapper.readValue(mapper.writeValueAsBytes(order), Order.class));
    assertEquals(payment, mapper.readValue(mapper.writeValueAsBytes(payment), Payment.class));
    assertEquals(inventory, mapper.readValue(mapper.writeValueAsBytes(inventory), Inventory.class));
  }

  @Test
  void problemAllowsTransportExtensionsAndNeverRendersServerStrings() throws Exception {
    var mapper = JsonMapper.builder().build();
    Problem problem =
        mapper.readValue(
            """
        {"type":"private-marker", "title":"private-marker", "status":404,
         "code":"private-marker", "detail":"private-marker", "correlationId":"private-marker",
         "instance":"private-marker", "extra":"private-marker"}
        """,
            Problem.class);
    assertEquals(404, problem.status());
    assertEquals("Problem[status=404]", problem.toString());
    assertFalse(problem.toString().contains("private-marker"));
  }
}
