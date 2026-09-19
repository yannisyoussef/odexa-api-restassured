package qa.odexa.provisioning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class StartupBatchTest {
  @Test
  void failedDependencyCannotReleaseCleanupWhileSiblingIsStillStarting() throws Exception {
    var failed = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var settled = new AtomicBoolean();
    var cleanup = new AtomicBoolean();
    var batch =
        CompletableFuture.runAsync(
            () -> {
              try {
                StartupBatch.run(
                    () -> {
                      failed.countDown();
                      throw new IllegalStateException("synthetic");
                    },
                    () -> {
                      started.countDown();
                      try {
                        if (!release.await(5, TimeUnit.SECONDS))
                          throw new AssertionError("Sibling not released");
                        settled.set(true);
                      } catch (InterruptedException e) {
                        throw new AssertionError(e);
                      }
                    });
              } finally {
                assertTrue(
                    settled.get(), "Cleanup must wait for every sibling, including after failure");
                cleanup.set(true);
              }
            });
    try {
      assertTrue(failed.await(5, TimeUnit.SECONDS));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertFalse(batch.isDone());
      assertFalse(cleanup.get());
    } finally {
      release.countDown();
    }
    assertThrows(
        java.util.concurrent.ExecutionException.class, () -> batch.get(5, TimeUnit.SECONDS));
    assertTrue(cleanup.get());
  }
}
