package qa.odexa.provisioning;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qa.odexa.config.TargetConfig;
import qa.odexa.config.TargetRuntime;

class SuiteLifecycleTest {
  private String previous;

  @BeforeEach
  void selectLifecycle() {
    previous = System.getProperty("odexa.targetMode");
    System.setProperty("odexa.targetMode", "testcontainers");
  }

  @AfterEach
  void restore() {
    if (previous == null) System.clearProperty("odexa.targetMode");
    else System.setProperty("odexa.targetMode", previous);
  }

  @Test
  void oneTargetIsBoundForTheSessionAndClosedExactlyOnce() throws Exception {
    var target = new FakeTarget();
    var lifecycle = new OdexaLauncherSession(() -> target);
    lifecycle.launcherSessionOpened(null);
    try {
      assertSame(target.config, TargetRuntime.current());
    } finally {
      lifecycle.launcherSessionClosed(null);
    }
    lifecycle.launcherSessionClosed(null);
    assertEquals(1, target.starts);
    assertEquals(1, target.closes);
    try (var binding = TargetRuntime.install(target.config)) {
      assertSame(target.config, TargetRuntime.current());
    }
  }

  @Test
  void partialStartupStillClosesAndKeepsSafeFailureClassification() {
    var target = new FakeTarget();
    target.startFailure = true;
    target.closeFailure = true;
    var lifecycle = new OdexaLauncherSession(() -> target);
    var failure =
        assertThrows(ProvisioningFailure.class, () -> lifecycle.launcherSessionOpened(null));
    assertEquals("TARGET_READINESS FAILURE: synthetic", failure.getMessage());
    assertEquals("CLEANUP FAILURE: synthetic", failure.getSuppressed()[0].getMessage());
    assertEquals(1, target.closes);
  }

  @Test
  void bindingConflictCleansNewTargetWithoutClearingOriginalTarget() throws Exception {
    var existing = new FakeTarget();
    var next = new FakeTarget();
    try (var binding = TargetRuntime.install(existing.config)) {
      var lifecycle = new OdexaLauncherSession(() -> next);
      assertThrows(IllegalStateException.class, () -> lifecycle.launcherSessionOpened(null));
      assertSame(existing.config, TargetRuntime.current());
      assertEquals(1, next.closes);
    }
  }

  @Test
  void cleanupFailureIsVisibleAndBindingIsReleased() throws Exception {
    var target = new FakeTarget();
    target.closeFailure = true;
    var lifecycle = new OdexaLauncherSession(() -> target);
    lifecycle.launcherSessionOpened(null);
    assertThrows(ProvisioningFailure.class, () -> lifecycle.launcherSessionClosed(null));
    try (var binding = TargetRuntime.install(target.config)) {
      assertSame(target.config, TargetRuntime.current());
    }
  }

  @Test
  void concurrentCloseCannotDisposeTargetBeforeStartupAndBindingComplete() throws Exception {
    var target = new FakeTarget();
    target.entered = new java.util.concurrent.CountDownLatch(1);
    target.release = new java.util.concurrent.CountDownLatch(1);
    var closing = new java.util.concurrent.CountDownLatch(1);
    var lifecycle = new OdexaLauncherSession(() -> target);
    var opening =
        java.util.concurrent.CompletableFuture.runAsync(
            () -> lifecycle.launcherSessionOpened(null));
    assertTrue(target.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
    var close =
        java.util.concurrent.CompletableFuture.runAsync(
            () -> {
              closing.countDown();
              lifecycle.launcherSessionClosed(null);
            });
    try {
      assertTrue(closing.await(5, java.util.concurrent.TimeUnit.SECONDS));
      assertFalse(close.isDone());
      assertEquals(0, target.closes);
    } finally {
      target.release.countDown();
    }
    opening.get(5, java.util.concurrent.TimeUnit.SECONDS);
    close.get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(1, target.closes);
    try (var binding = TargetRuntime.install(target.config)) {
      assertSame(target.config, TargetRuntime.current());
    }
  }

  private static final class FakeTarget implements SuiteTarget {
    final TargetConfig config =
        OdexaEnvironment.configuration(
            URI.create("http://localhost:49101"),
            URI.create("http://localhost:49102"),
            new EphemeralSecrets());
    int starts;
    int closes;
    boolean startFailure;
    boolean closeFailure;
    java.util.concurrent.CountDownLatch entered;
    java.util.concurrent.CountDownLatch release;

    @Override
    public TargetConfig start(String version) {
      starts++;
      if (entered != null) {
        entered.countDown();
        try {
          if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            throw new AssertionError("Startup not released");
        } catch (InterruptedException interrupted) {
          throw new AssertionError(interrupted);
        }
      }
      if (startFailure)
        throw new ProvisioningFailure(ProvisioningFailure.Stage.TARGET_READINESS, "synthetic");
      return config;
    }

    @Override
    public void close() {
      closes++;
      if (closeFailure)
        throw new ProvisioningFailure(ProvisioningFailure.Stage.CLEANUP, "synthetic");
    }
  }
}
