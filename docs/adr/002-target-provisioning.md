# 002 — Peer provisioning strategies with one HTTP suite

Status: accepted

## Context

The foundation supports externally configured HTTP targets and an optional Python Compose lifecycle. QA also needs an isolated Java-managed target with dynamic host ports. The pinned Odexa v0.1.0 release has no published service images.

## Decision

Retain the existing immutable `TargetConfig` and compiled API scenarios. REMOTE and COMPOSE use `apiTest`; `testcontainersTest` adds an isolated provisioning source set and a JUnit launcher-session listener. The listener starts one target, installs a scoped configuration binding, and tears down after the entire suite. Core clients never import provisioning APIs. Gradle verifies runtime dependency separation and shared scenario classes.

COMPOSE remains the official product lifecycle driven by Python. TESTCONTAINERS builds the official Dockerfile from an exact SHA-verified temporary release checkout, then provisions real PostgreSQL, Kafka, Keycloak and six services on a private network. Only the gateway and identity endpoint receive dynamic loopback host ports. The product realm and database bootstrap are packaging artifacts; product Java classes, database assertions and broker assertions remain outside the QA boundary.

Independent container starts execute concurrently in dependency-ordered batches, with all siblings settled before failure cleanup. Image builds resolve synchronously so no detached worker can publish an image after teardown. The Gradle task and CI job bound total execution; container readiness has separate deadlines. Cleanup checks owned resource removal and retains standard Testcontainers resource reaping. Diagnostics expose allowlisted metadata, not raw third-party logs or exception causes.

## Consequences

All strategies exercise the same scenarios and fixture protections. REMOTE needs neither Docker libraries nor product files at runtime. Java provisioning duplicates the small v0.1.0 deployment graph, so a release upgrade must explicitly reconcile that graph with the official Compose packaging. Source builds are slower than immutable published images; release-tagged upstream base images are retained until the product provides digest-pinned packaging. Unix-socket local Docker is supported; remote daemons and Windows named pipes are outside this milestone.
