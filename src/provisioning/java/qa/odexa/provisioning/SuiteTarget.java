package qa.odexa.provisioning;

import qa.odexa.config.TargetConfig;

/** The launcher owns one target; this seam permits failure-path tests without a Docker daemon. */
interface SuiteTarget extends AutoCloseable {
  TargetConfig start(String version);

  @Override
  void close();
}
