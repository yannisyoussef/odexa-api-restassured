package qa.odexa.provisioning;

/** Never retain a third-party cause: Docker/build/HTTP errors can contain credentials or logs. */
final class ProvisioningFailure extends RuntimeException {
  enum Stage {
    PROVISIONING,
    TARGET_READINESS,
    AUTHENTICATION_CONFIGURATION,
    CLEANUP
  }

  ProvisioningFailure(Stage stage, String role) {
    super(stage.name() + " FAILURE: " + role);
  }
}
