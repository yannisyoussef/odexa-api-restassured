package qa.odexa.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Generated only in memory; realm content is copied into its owned container, never a report. */
final class EphemeralSecrets {
  private final Map<String, String> values = new LinkedHashMap<>();

  EphemeralSecrets() {
    for (String key :
        new String[] {
          "POSTGRES_PASSWORD",
          "CATALOG_DB_PASSWORD",
          "INVENTORY_DB_PASSWORD",
          "ORDER_DB_PASSWORD",
          "PAYMENT_DB_PASSWORD",
          "SIMULATOR_DB_PASSWORD",
          "KEYCLOAK_DB_PASSWORD",
          "KC_BOOTSTRAP_ADMIN_PASSWORD",
          "LOCAL_FIXTURE_PASSWORD",
          "PROVIDER_API_KEY"
        }) {
      byte[] bytes = new byte[32];
      new SecureRandom().nextBytes(bytes);
      values.put(key, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
  }

  String get(String key) {
    String value = values.get(key);
    if (value == null) throw new IllegalArgumentException("Unknown credential key");
    return value;
  }

  byte[] realm(Path template) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      var realm = mapper.readTree(template.toFile());
      if (!realm.path("users").isArray() || realm.path("users").size() != 4)
        throw new IOException();
      for (var user : realm.path("users")) {
        if (user.path("credentials").size() != 1) throw new IOException();
        for (var credential : user.path("credentials")) {
          if (!"__LOCAL_FIXTURE_PASSWORD__".equals(credential.path("value").asText()))
            throw new IOException();
          ((ObjectNode) credential).put("value", get("LOCAL_FIXTURE_PASSWORD"));
        }
      }
      return mapper.writeValueAsBytes(realm);
    } catch (IOException | RuntimeException ignored) {
      throw new ProvisioningFailure(ProvisioningFailure.Stage.PROVISIONING, "realm-template");
    }
  }

  @Override
  public String toString() {
    return "EphemeralSecrets[redacted]";
  }
}
