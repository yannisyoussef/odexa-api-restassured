package qa.odexa.provisioning;

import java.nio.file.Path;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import qa.odexa.config.TargetRuntime;

/** Loaded only on the provisioning classpath; one lifecycle around every discovered API class. */
public final class OdexaLauncherSession implements LauncherSessionListener {
  private final java.util.function.Supplier<SuiteTarget> factory;
  private SuiteTarget environment;
  private AutoCloseable binding;
  private Thread shutdown;

  public OdexaLauncherSession() {
    this(() -> new OdexaEnvironment(Path.of(System.getProperty("odexa.diagnostics.directory"))));
  }

  OdexaLauncherSession(java.util.function.Supplier<SuiteTarget> factory) {
    this.factory = factory;
  }

  @Override
  public synchronized void launcherSessionOpened(LauncherSession session) {
    if (!"testcontainers".equals(System.getProperty("odexa.targetMode"))) return;
    environment = factory.get();
    shutdown = new Thread(this::cleanupAtShutdown, "odexa-owned-cleanup");
    Runtime.getRuntime().addShutdownHook(shutdown);
    try {
      var target =
          environment.start(
              System.getProperty(
                  "odexa.version",
                  System.getenv().getOrDefault("ODEXA_VERSION", PinnedRelease.VERSION)));
      binding = TargetRuntime.install(target);
    } catch (RuntimeException failure) {
      try {
        close();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  @Override
  public synchronized void launcherSessionClosed(LauncherSession session) {
    if (environment != null) close();
  }

  private synchronized void close() {
    if (environment == null) return;
    SuiteTarget owned = environment;
    environment = null;
    boolean failed = false;
    try {
      if (binding != null) binding.close();
    } catch (Exception ignored) {
      failed = true;
    } finally {
      binding = null;
    }
    try {
      owned.close();
    } finally {
      if (shutdown != null && Thread.currentThread() != shutdown) {
        try {
          Runtime.getRuntime().removeShutdownHook(shutdown);
        } catch (IllegalStateException shuttingDown) {
          // JVM shutdown already owns the hook; it will observe the cleared target binding.
        }
      }
      shutdown = null;
    }
    if (failed) throw new ProvisioningFailure(ProvisioningFailure.Stage.CLEANUP, "target-binding");
  }

  private void cleanupAtShutdown() {
    try {
      close();
    } catch (RuntimeException ignored) {
      System.err.println("CLEANUP FAILURE: owned-resources");
    }
  }
}
