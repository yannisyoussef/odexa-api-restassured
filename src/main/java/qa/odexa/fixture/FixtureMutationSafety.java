package qa.odexa.fixture;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import qa.odexa.config.TargetConfig;

/** Suite-local, target/product-scoped fail-closed state for public fixture mutations. */
public final class FixtureMutationSafety {
  private static final ConcurrentMap<Scope, FailureLatch> LATCHES = new ConcurrentHashMap<>();

  private FixtureMutationSafety() {}

  /** Returns the shared latch for this JVM suite, target, and product. */
  public static FailureLatch forTarget(TargetConfig config) {
    Objects.requireNonNull(config, "config");
    Scope scope = new Scope(config.baseUri().normalize().resolve("/"), config.productId());
    return LATCHES.computeIfAbsent(scope, ignored -> new FailureLatch());
  }

  private record Scope(URI target, UUID productId) {}

  /** Irreversible for the suite: an ambiguous prior mutation may still reach this product. */
  public static final class FailureLatch {
    private final AtomicBoolean blocked = new AtomicBoolean();

    private FailureLatch() {}

    public boolean isBlocked() {
      return blocked.get();
    }

    public void requireSafe() {
      if (isBlocked()) {
        throw new IllegalStateException(
            "Mutations are blocked after uncertain fixture cleanup for this target and product");
      }
    }

    public void block() {
      blocked.set(true);
    }
  }
}
