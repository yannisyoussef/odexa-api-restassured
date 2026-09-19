package qa.odexa.provisioning;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/** Failure does not release lifecycle ownership until every sibling start has settled. */
final class StartupBatch {
  private StartupBatch() {}

  static void run(Runnable... starts) {
    CompletableFuture<?>[] futures =
        Arrays.stream(starts).map(CompletableFuture::runAsync).toArray(CompletableFuture<?>[]::new);
    // join intentionally waits through interruption; teardown must not race an active start.
    CompletableFuture.allOf(futures).join();
  }
}
