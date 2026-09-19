package qa.odexa.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Provenance and selected shallow core-field checks, NOT a JSON Schema/OpenAPI validator. */
class PublicContractTest {
  private static final String ROOT = "/contracts/odexa-v0.1.0/";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();
  private static final Map<String, String> PINNED_SHA256 =
      Map.of(
          "catalog.json", "eba89857530bc0539a4091743f41bcdf1101db71febb273053dc7d0d4347ebd6",
          "inventory.json", "f764d4df4293c3b61ec777775a5162cf2633fe103a4ad84911f99eef60f820d4",
          "order.json", "b79a96be1b9e7d6d975c034f25dda654a0fbf9dd29ea485542e12cc79f020ac6",
          "payment.json", "81d1e5b65cddee54b3b0904f6bf7652f74119b8bcbd30bb28fd5153a75a91973");

  @Test
  void copiedPublicContractsMatchPinnedReleaseProvenanceAndExactChecksums() throws Exception {
    JsonNode provenance = JSON.readTree(bytes("provenance.json"));
    assertEquals(
        "https://github.com/yannisyoussef/odexa", provenance.path("repository").textValue());
    assertEquals("v0.1.0", provenance.path("tag").textValue());
    assertEquals("fac40f94377901419da4e1f26b99148ff5f550bf", provenance.path("commit").textValue());
    assertEquals("contracts/openapi", provenance.path("sourceDirectory").textValue());
    assertEquals(4, provenance.path("sha256").size());
    for (var entry : PINNED_SHA256.entrySet()) {
      byte[] artifact = bytes(entry.getKey());
      assertEquals(entry.getValue(), provenance.path("sha256").path(entry.getKey()).textValue());
      assertEquals(
          entry.getValue(),
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact)));
      assertEquals("3.1.0", JSON.readTree(artifact).path("openapi").textValue());
    }
  }

  @Test
  void recordWireRepresentationsHavePinnedRequiredCoreFieldsTypesAndEnums() throws Exception {
    UUID id = UUID.randomUUID();
    Product product = new Product(id, "unit", "", 150, "USD", true, 1);
    checkCore(JSON.valueToTree(product), schema("catalog", "Product"));
    checkCore(
        JSON.valueToTree(new ProductPage(List.of(product), null)),
        schema("catalog", "ProductPage"));
    checkCore(JSON.valueToTree(new Inventory(id, 5, 1, 4, 2)), schema("inventory", "Inventory"));
    checkCore(
        JSON.valueToTree(new Order(id, id, 1, 150, "USD", "CONFIRMED", 2, Instant.EPOCH)),
        schema("order", "Order"));
    checkCore(
        JSON.valueToTree(
            new Payment(id, id, 150, "USD", "AUTHORIZED", Instant.EPOCH, Instant.EPOCH)),
        schema("payment", "Payment"));
  }

  @Test
  void selectedChecksRejectMissingFieldsWrongScalarTypesAndUnknownStates() throws Exception {
    JsonNode orderSchema = schema("order", "Order");
    var order =
        JSON.valueToTree(
            new Order(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                150,
                "USD",
                "CONFIRMED",
                2,
                Instant.EPOCH));
    var missing = (com.fasterxml.jackson.databind.node.ObjectNode) order.deepCopy();
    missing.remove("totalMinor");
    assertThrows(AssertionError.class, () -> checkCore(missing, orderSchema));
    var wrongType = (com.fasterxml.jackson.databind.node.ObjectNode) order.deepCopy();
    wrongType.put("quantity", "1");
    assertThrows(AssertionError.class, () -> checkCore(wrongType, orderSchema));
    var unknownState = (com.fasterxml.jackson.databind.node.ObjectNode) order.deepCopy();
    unknownState.put("status", "UNKNOWN");
    assertThrows(AssertionError.class, () -> checkCore(unknownState, orderSchema));
  }

  @Test
  void fixtureAccountingAndTerminalStatesAreDocumentedInPublicHttpContracts() throws Exception {
    JsonNode inventory = schema("inventory", "Inventory");
    String versionDescription =
        inventory.path("properties").path("version").path("description").textValue();
    assertTrue(
        versionDescription.contains(
            "each successful adjustment, reservation, commitment or release"));
    JsonNode states = schema("order", "Order").path("properties").path("status").path("enum");
    assertEquals(
        JSON.valueToTree(
            List.of("CREATED", "PENDING_PAYMENT", "CONFIRMED", "STOCK_REJECTED", "PAYMENT_FAILED")),
        states);
  }

  private static JsonNode schema(String service, String name) throws IOException {
    return JSON.readTree(bytes(service + ".json")).path("components").path("schemas").path(name);
  }

  private static byte[] bytes(String file) throws IOException {
    try (var stream = PublicContractTest.class.getResourceAsStream(ROOT + file)) {
      assertNotNull(stream, "Pinned public contract resource is required");
      return stream.readAllBytes();
    }
  }

  // Intentionally shallow: required presence, primitive types, const and enum only. No refs,
  // nested array-item validation, bounds, formats, patterns, or claims of full schema validation.
  private static void checkCore(JsonNode body, JsonNode schema) {
    assertTrue(body.isObject(), "Expected object response");
    for (JsonNode required : schema.path("required")) {
      assertTrue(body.has(required.textValue()), "Required core field is absent");
      JsonNode property = schema.path("properties").path(required.textValue());
      JsonNode value = body.path(required.textValue());
      JsonNode types = property.path("type");
      List<String> allowed =
          types.isArray()
              ? JSON.convertValue(
                  types, JSON.getTypeFactory().constructCollectionType(List.class, String.class))
              : List.of(types.textValue());
      boolean typeMatches =
          allowed.stream()
              .anyMatch(
                  type ->
                      switch (type) {
                        case "string" -> value.isTextual();
                        case "integer" -> value.isIntegralNumber();
                        case "boolean" -> value.isBoolean();
                        case "array" -> value.isArray();
                        case "null" -> value.isNull();
                        default -> throw new AssertionError("Unsupported selected core-field type");
                      });
      assertTrue(typeMatches, "Unexpected core-field type");
      if (property.has("const")) {
        assertEquals(property.path("const"), value, "Unexpected constant field");
      }
      if (property.has("enum")) {
        assertTrue(
            java.util.stream.StreamSupport.stream(property.path("enum").spliterator(), false)
                .anyMatch(value::equals),
            "Unexpected enum field");
      }
    }
  }
}
