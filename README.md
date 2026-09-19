# Odexa API — Rest Assured

**Independent API automation · three target provisioning strategies**

Independent black-box automation for [Odexa](https://github.com/yannisyoussef/odexa), pinned initially to **v0.1.0**. This repository treats the product as an externally owned HTTP system: no application classes, Spring context, database access, Kafka consumers or submodules.

Java 25 · Gradle Kotlin DSL · Rest Assured 6 · JUnit 5 · Jackson · AssertJ · Awaitility · Allure

## Architecture

JUnit scenarios → domain clients → fresh Rest Assured specifications → Odexa's public gateway. A separate OIDC client supplies per-actor cached tokens and refreshes them before expiry. Parallel classes share one session per identity, so a fixture user is never authenticated concurrently—identity providers can treat that as a credential attack. Responses remain available as Rest Assured responses; domain assertions complement, rather than hide, HTTP status/header checks.

- `config/`, `auth/`: explicit COMPOSE/REMOTE/TESTCONTAINERS targets and four real v0.1.0 identities; one session per identity, never a global or cross-actor token.
- `http/`, `diagnostics/`: bounded requests, disabled redirects/write retries, UUID correlations, sanitized failures and metadata-only structured logs/Allure attachments.
- `client/`, `model/`, `data/`: small hand-written clients, records and immutable checkout variants.
- `assertion/`, `wait/`, `fixture/`: Problem Details assertions, bounded eventual-state polling and conservative public-API stock restoration.
- `src/test/java/qa/odexa/api/`: independent scenarios; no test ordering dependencies.

The [black-box boundary decision](docs/adr/001-independent-http-suite.md) explains the fixture and execution trade-offs.

## Fast checks

Requires Java 25. Run `./gradlew check` for compilation, framework unit tests and Spotless. Run `./gradlew spotlessApply` when formatting changes. Python 3 tooling checks are `python3 -B -m unittest discover -s scripts/tests -v` and `python3 -B scripts/check-boundaries.py`.

The wrapper is checksum-pinned, dependencies are locked, and compiler deprecation/unchecked warnings fail the build. Framework unit tests use isolated local HTTP servers, not Odexa infrastructure. `check` never runs against a live product.

## Execution strategies

All modes execute the **same compiled API scenario classes** and shared JUnit tag selection. Provisioning supplies the existing immutable `TargetConfig`; clients and assertions remain HTTP-only. `apiTest` has no Testcontainers libraries on its runtime classpath. `check` verifies this separation and scenario class reuse.

| Mode | Environment owner | Docker required | Use |
|---|---|---|---|
| `remote` | External team/platform | No | Pre-provisioned staging/shared target |
| `compose` | Product Compose lifecycle | Yes for provisioning | Official local deployment |
| `testcontainers` | QA suite lifecycle | Yes | Ephemeral target with dynamic ports |

Select existing targets with `ODEXA_TARGET_MODE` (default `compose`). `ODEXA_ENV` is retired and rejected with migration guidance. `testcontainersTest` selects its own mode and generated configuration; setting the mode alone on `apiTest` does not provision a target.

## COMPOSE execution

With an already running v0.1.0 target:

- Read-only suite: `python3 scripts/run-local.py --odexa-dir /path/to/odexa`
- Full suite on **dedicated fixtures**: `python3 scripts/run-local.py --odexa-dir /path/to/odexa --allow-mutation --exclusive-fixtures`

This optional bridge reads only the documented fixture password from the target's private `.env`, passes it through the child environment, and never starts, stops or resets a developer target. The Java suite itself requires no product directory. Alternatively, supply the documented environment variables and run `./gradlew apiTest` directly.

For a disposable target, run `python3 scripts/local-ci.py`. It requires Python 3, Git and Docker Compose v2 on a Unix host, checks out the exact Odexa release into a private temporary directory, verifies the v0.1.0 commit, invokes the product's own bootstrap/Compose, waits for HTTP readiness, runs the suite and removes only its uniquely named project and volumes. The fixed v0.1.0 host ports require an otherwise unused runner. Remote Docker endpoints are rejected. Failed teardown retains private recovery files; their locator is recorded without credential contents.

`ODEXA_VERSION` selects an exact release tag, never arbitrary `main`. v0.1.0 must resolve to `fac40f94377901419da4e1f26b99148ff5f550bf`. Changing the target version does not automatically upgrade the pinned contract assertions.

## TESTCONTAINERS execution

Run `./gradlew testcontainersTest` with Java 25, Git, and a local Docker daemon reached through a Unix socket (Linux or macOS Docker Desktop). TCP/SSH endpoints and Windows named pipes are rejected. No Python, running Odexa instance, pre-existing checkout, or credentials are needed. The task runs all 25 scenarios, including mutation cases, on its exclusively owned fixtures. Keep roughly 6 GB of Docker memory available for the target; a cold image build takes several minutes.

The isolated `provisioning` source set uses Testcontainers **2.0.4**: `Network`, `PostgreSQLContainer`, `KafkaContainer`, `GenericContainer`, and `ImageFromDockerfile`. Odexa v0.1.0 publishes no service images, so each run obtains and SHA-verifies a private disposable checkout, then builds the six services with the official Dockerfile and `SERVICE` argument. Product code never enters the QA classpath and the checkout is not modified. Base/infrastructure images retain the product's exact version tags; these upstream tags are not digest-pinned.

One JUnit launcher session owns PostgreSQL 17.6, Kafka 4.1.1, Keycloak 26.4.7, gateway, catalog, inventory, order, payment, and payment-simulator. The official PostgreSQL initialization script preserves separate service databases/roles. The official realm template receives generated credentials in memory. A private network uses product service aliases; only gateway and Keycloak receive dynamic host ports. Token requests use the mapped Keycloak endpoint, while tokens retain the internal issuer checked by services using private JWKS access. HTTP is restricted to loopback: remote Docker hosts are unsupported.

Declared dependencies start concurrently before dependents. Readiness uses final PostgreSQL startup, Kafka broker readiness, Keycloak discovery, the product's Docker health checks, and real token grants for all actors. Readiness seeds the same actor sessions used by tests. No fixture locks or mutation protections are bypassed.

Normal completion, failures, and cooperative JVM shutdown attempt owned container, network, image, and checkout cleanup. Ryuk remains enabled for abandoned Docker resources. Docker build layers and downloaded base images remain cached; container reuse is not enabled. Abrupt host loss can leave the private checkout in the host temp directory. No global Docker prune or Compose cleanup is used.

Allowlisted stage/role, image ID, health, exit code, and endpoint metadata is written to `build/diagnostics/testcontainers-target.json`. Third-party raw container/build logging is disabled because startup exceptions can include secrets. Failures distinguish provisioning, target readiness, authentication configuration, API assertions, and cleanup; underlying Docker exceptions and raw logs are not published.

## REMOTE execution

Set `ODEXA_TARGET_MODE=remote`, supply HTTPS gateway/token URLs and pre-provisioned actors/fixtures, then run `./gradlew apiTest`. No product checkout, Docker, database or local product file is required. Certificate verification stays enabled and redirects are disabled. Remote jobs are **not** run automatically by CI.

The target must provide an automation OIDC client supporting the configured password/refresh grant. The development CLI client must not simply be exposed publicly; browser PKCE login is a separate future concern. No hosted `api.odexa.cc` environment is assumed to exist.

### Configuration

See [.env.example](.env.example) for all names. It is a reference, **not an automatically loaded file**. Non-secret precedence is `-Dodexa.<property>` → environment → safe COMPOSE default. Credentials are environment-only; never put them in command-line arguments, Gradle properties or reports. Configuration caching is disabled to avoid persisting environment-derived credentials.

| Inputs | Policy |
|---|---|
| `ODEXA_TARGET_MODE` | `remote`, `compose`, or task-managed `testcontainers` |
| `ODEXA_BASE_URL`, `ODEXA_TOKEN_URL`, `ODEXA_CLIENT_ID` | Loopback COMPOSE defaults; explicit HTTPS URLs/client in REMOTE |
| `ODEXA_CLIENT_SECRET` | Optional, according to the configured OIDC client |
| `ODEXA_<ACTOR>_USERNAME`, `ODEXA_<ACTOR>_PASSWORD` | Actors: `CUSTOMER_A`, `CUSTOMER_B`, `OTHER_CUSTOMER_A`, `MERCHANT_A` |
| `ODEXA_FIXTURE_PASSWORD` | COMPOSE-only shared fallback; never committed |
| `ODEXA_PRODUCT_ID`, `ODEXA_OTHER_PRODUCT_ID`, `ODEXA_TENANT_A`, `ODEXA_TENANT_B` | COMPOSE fixture defaults; required explicitly in REMOTE |
| `ODEXA_ALLOW_MUTATION`, `ODEXA_EXCLUSIVE_FIXTURES` | Both must be `true` for stock-changing cases; default `false` |
| `ODEXA_TIMEOUT_SECONDS`, `ODEXA_POLL_TIMEOUT_SECONDS`, `ODEXA_POLL_INTERVAL_MILLIS` | Bounded request and eventual-consistency waits |
| `ODEXA_VERBOSE` | Adds safe metadata only; never raw requests/responses |

For example, `./gradlew apiTest -Dodexa.pollTimeoutSeconds=120` changes only the polling deadline. Live API results are never taken from the build cache.

## Scenarios and isolation

**25 API cases:** 11 read-only and 14 opt-in mutation cases. Coverage includes all four actors, missing/invalid authentication, catalog and conditional reads, tenant/customer isolation, invalid checkout, successful and declined payments, unavailable stock, idempotency conflicts/retries, stale/missing preconditions, concurrent duplicate submissions and eight competing orders for five units.

Read-only cases run concurrently. Stock-changing tests share a JUnit resource lock within one test JVM; their deliberately concurrent HTTP submissions still exercise the real server race. This is not a distributed environment lease: callers must reserve dedicated fixtures and avoid simultaneous suite processes or unrelated writers.

v0.1.0 cannot provision stock for new products or delete/cancel orders. Each fixture uses conditional merchant writes, records accepted checkouts, awaits settlement, verifies expected stock/version, and restores the original physical count only if the state is safe. Ambiguous requests or foreign changes stop restoration rather than overwriting someone else's stock. Orders remain as audit records. No database cleanup or artificial reset endpoint is used.

## Reports and CI

JUnit XML/HTML is under `build/test-results/` and `build/reports/tests/`. Allure results are under `build/allure-results/test/` and `build/allure-results/apiTest/`; an installed Allure CLI can render them with `allure serve build/allure-results/apiTest`. API target mode/version/base URL are included. Each execution starts a fresh Allure result directory.

Diagnostics include actor label, method, route template, status and correlation UUID. Authentication responses, bodies, query values, cookies and authorization headers are deliberately omitted—even on failure. This limits payload debugging but prevents credential/payment data from reaching artifacts.

CI separates fast checks from the pinned COMPOSE and TESTCONTAINERS black-box suites, uploads only QA reports/allowlisted metadata, and always attempts owned-resource cleanup. Reports and generated target files are ignored, not committed.

The four checked-in public OpenAPI artifacts include release/commit/SHA-256 provenance. Assertions validate selected fields, types and protocol semantics; they are **not a full OpenAPI/JSON Schema validation engine**. Actual signed expired-token behavior is not asserted against the product; refresh/expiry is deterministic in framework tests.

Next: **ODAQA-003 — Authorization & Tenant Isolation**. Later milestones cover deeper workflows, concurrency, asynchronous behavior, optional local event validation, provider failures and schema validation. Cucumber, Karate, Pact, browser/mobile automation, database checks and load/security suites remain separate projects or explicitly later scopes.
