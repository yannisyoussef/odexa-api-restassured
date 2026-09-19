package qa.odexa.provisioning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** An owned, unmodified, verified external packaging artifact; never a Java dependency. */
final class PinnedRelease implements AutoCloseable {
  static final String VERSION = "v0.1.0";
  static final String COMMIT = "fac40f94377901419da4e1f26b99148ff5f550bf";
  static final String REPOSITORY = "https://github.com/yannisyoussef/odexa.git";
  private final Path directory;

  private PinnedRelease(Path directory) {
    this.directory = directory;
  }

  static void validateVersion(String version) {
    if (!VERSION.equals(version)) throw failure();
  }

  static void validateCommit(String commit) {
    if (!COMMIT.equals(commit)) throw failure();
  }

  static PinnedRelease acquire(String version) {
    validateVersion(version);
    PinnedRelease release = null;
    try {
      release =
          new PinnedRelease(
              Files.createTempDirectory(
                  "odexa-tc-",
                  PosixFilePermissions.asFileAttribute(
                      PosixFilePermissions.fromString("rwx------"))));
      release.git(
          Duration.ofMinutes(3),
          "clone",
          "--no-checkout",
          "--depth",
          "1",
          "--single-branch",
          "--no-recurse-submodules",
          "--branch",
          VERSION,
          REPOSITORY,
          ".");
      validateCommit(
          release.git(
              Duration.ofSeconds(30),
              "rev-parse",
              "--verify",
              "refs/tags/" + VERSION + "^{commit}"));
      release.git(Duration.ofMinutes(1), "checkout", "--detach", COMMIT);
      validateCommit(release.git(Duration.ofSeconds(30), "rev-parse", "HEAD"));
      return release;
    } catch (RuntimeException | IOException failure) {
      if (release != null) release.close();
      throw failure();
    }
  }

  Path path() {
    return directory;
  }

  private String git(Duration timeout, String... arguments) {
    var command =
        new java.util.ArrayList<>(
            List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "protocol.file.allow=never"));
    command.addAll(List.of(arguments));
    Process child = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
      // Ignore ambient Git configuration, credential helpers, hooks, proxies and trace settings.
      Map<String, String> environment = builder.environment();
      String path = environment.get("PATH");
      environment.clear();
      if (path != null) environment.put("PATH", path);
      environment.put("HOME", directory.toString());
      environment.put("GIT_CONFIG_NOSYSTEM", "1");
      environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
      environment.put("GIT_TERMINAL_PROMPT", "0");
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      child = builder.start();
      if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw failure();
      if (child.exitValue() != 0) throw failure();
      return new String(child.getInputStream().readNBytes(4096), StandardCharsets.UTF_8).trim();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw failure();
    } catch (IOException ignored) {
      throw failure();
    } finally {
      if (child != null && child.isAlive()) {
        child.descendants().forEach(ProcessHandle::destroyForcibly);
        child.destroyForcibly();
      }
    }
  }

  @Override
  public void close() {
    try (var entries = Files.walk(directory)) {
      for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
    } catch (IOException ignored) {
      throw new ProvisioningFailure(ProvisioningFailure.Stage.CLEANUP, "release-checkout");
    }
  }

  private static ProvisioningFailure failure() {
    return new ProvisioningFailure(ProvisioningFailure.Stage.PROVISIONING, "pinned-release");
  }
}
