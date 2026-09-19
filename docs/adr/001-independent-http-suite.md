# 001 — An independently owned HTTP automation suite

Status: accepted

## Context

Odexa is a distributed product, not a test fixture library. A separate QA team needs to verify stable, externally observable behavior without access to service code or databases. Its first release also has limited public fixture lifecycle APIs.

## Decision

Keep the framework in a separate repository with no application dependencies or submodule. Domain clients call only the gateway APIs; authentication uses the documented OIDC boundary. Public contracts may be copied as versioned test artifacts with provenance, but generated server models and implementation classes are excluded.

LOCAL orchestration owns a disposable checkout of an exact release tag. It uses the product's own bootstrap and Compose rather than maintaining a second deployment definition. That Python orchestration is optional: the Java suite consumes configuration only. REMOTE execution requires no product filesystem, Docker or infrastructure credentials and is not scheduled automatically.

Use per-actor token providers and fresh request specifications, not global Rest Assured configuration. Poll public order/inventory projections with bounded waits. Expose status/header expectations in the tests while sharing small domain assertions. Authentication and HTTP errors carry safe metadata, not raw payloads or underlying exceptions.

Mutation is opt-in and requires exclusively assigned fixtures. In v0.1.0, new catalog products cannot acquire stock through the public API; orders cannot be deleted. Stock-changing scenarios therefore serialize within one JVM on a documented fixture resource. The concurrency tests issue competing HTTP calls inside that boundary. No process-local mechanism is presented as protection against another suite or a human writer.

A fixture journals successful order responses and ambiguous in-flight requests, waits for known checkouts, then validates public stock/version accounting before restoring the original on-hand count with If-Match. A mismatch, unresolved operation or stale restore fails closed. It does not reset the environment or retry an overwrite. Created orders remain as audit history.

## Consequences

The suite survives internal service refactoring and can run under externally supplied access. LOCAL integration is reproducible against v0.1.0; REMOTE operators must provision the four supported actors and reserve test fixtures. New public provisioning/cancellation APIs could improve isolation later without coupling tests to persistence.

Metadata-only reports intentionally trade payload visibility for credential safety. Selected contract assertions are not full schema conformance testing. Kafka consumers, direct database validation, hosted browser authentication and other automation tools are outside ODAQA-001.
