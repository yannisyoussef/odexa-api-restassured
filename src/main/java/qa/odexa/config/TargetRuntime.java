package qa.odexa.config;

import java.util.Objects;

/** A suite-owned target binding. Existing targets need no provisioning code on their classpath. */
public final class TargetRuntime {
  private static TargetConfig installed;

  private TargetRuntime() {}

  public static synchronized TargetConfig current() {
    if (installed != null) return installed;
    TargetConfig config = TargetConfig.fromEnvironment();
    if (config.mode() == TargetConfig.Mode.TESTCONTAINERS) {
      throw new IllegalStateException("TESTCONTAINERS requires the testcontainersTest task");
    }
    return config;
  }

  /** The lifecycle owner must close this binding after the suite, even when tests fail. */
  public static synchronized AutoCloseable install(TargetConfig config) {
    Objects.requireNonNull(config);
    if (installed != null) throw new IllegalStateException("A suite target is already installed");
    installed = config;
    return () -> {
      synchronized (TargetRuntime.class) {
        if (installed == config) installed = null;
      }
    };
  }
}
