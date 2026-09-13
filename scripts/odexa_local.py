"""Stdlib-only, optional LOCAL orchestration; Java/REMOTE never imports this module."""

from contextlib import contextmanager
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

QA_ROOT = Path(__file__).resolve().parents[1]
PRODUCT_REPOSITORY = "https://github.com/yannisyoussef/odexa.git"
BASELINE_VERSION = "v0.1.0"
BASELINE_COMMIT = "fac40f94377901419da4e1f26b99148ff5f550bf"
TARGET_COMMITS = {BASELINE_VERSION: BASELINE_COMMIT}
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}", re.ASCII)
PROJECT_PATTERN = re.compile(r"odexa-qa-[0-9a-f]{32}", re.ASCII)
TEMP_PREFIX = "odexa-target-"
HEALTH_ENDPOINTS = {
    "gateway": "http://localhost:8080/actuator/health",
    "keycloak": "http://localhost:8180/realms/odexa/.well-known/openid-configuration",
}
SERVICES = frozenset(("postgres", "kafka", "keycloak", "gateway", "catalog", "inventory",
                      "order", "payment", "payment-simulator"))
STATES = frozenset(("running", "exited", "created", "restarting", "paused", "dead", "removing"))
HEALTH_STATES = frozenset(("healthy", "unhealthy", "starting"))
# Do not inherit Compose overrides, product credentials, JVM options or QA target overrides.
RUNTIME_KEYS = frozenset(("PATH", "HOME", "USER", "LOGNAME", "JAVA_HOME", "GRADLE_USER_HOME",
                          "TMPDIR", "TEMP", "TMP", "SYSTEMROOT", "DOCKER_HOST", "DOCKER_CONTEXT",
                          "DOCKER_CONFIG", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"))


class SafeFailure(Exception):
    """Only static stage names and numeric return codes may cross this boundary."""

    def __init__(self, stage, exit_code=1):
        self.stage = stage
        self.exit_code = exit_code if isinstance(exit_code, int) and 0 < exit_code < 126 else 1
        super().__init__(stage)


def validate_version(value):
    if not isinstance(value, str) or value not in TARGET_COMMITS:
        raise SafeFailure("invalid-target-version")
    return value


def runtime_environment(source=None):
    source = os.environ if source is None else source
    return {key: value for key, value in source.items() if key in RUNTIME_KEYS}


def command(argv, *, cwd, env, timeout, capture=False):
    """Never inherit child output or stringify subprocess errors (which can contain secrets)."""
    try:
        with subprocess.Popen(
            argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, start_new_session=True,
        ) as child:
            try:
                output, _ = child.communicate(timeout=timeout)
            except BaseException:
                # Bound and stop the whole owned command, not just the Compose/Gradle launcher.
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                child.communicate()
                raise
            return subprocess.CompletedProcess(argv, child.returncode, output or b"")
    except subprocess.TimeoutExpired:
        raise SafeFailure("command-timeout") from None
    except OSError:
        raise SafeFailure("command-unavailable") from None


def checked(argv, *, cwd, env, timeout, stage, capture=False):
    result = command(argv, cwd=cwd, env=env, timeout=timeout, capture=capture)
    if result.returncode:
        raise SafeFailure(stage, result.returncode)
    return result.stdout


def docker_endpoint_preflight(*, cwd, env):
    """Accept only Unix sockets: even loopback TCP may forward to a remote daemon."""
    if env.get("DOCKER_HOST") and not env.get("DOCKER_CONTEXT"):
        endpoint = env["DOCKER_HOST"]
    else:
        # With no context argument Docker resolves DOCKER_CONTEXT, then the active context
        # in DOCKER_CONFIG. Capture only the endpoint; never log context/config or output.
        output = checked(["docker", "context", "inspect", "--format",
                          "{{json .Endpoints.docker.Host}}"],
                         cwd=cwd, env=env, timeout=30, stage="docker-endpoint", capture=True)
        try:
            endpoint = json.loads(output.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError):
            raise SafeFailure("docker-endpoint") from None
    if (not isinstance(endpoint, str) or not endpoint.startswith("unix:///")
            or len(endpoint) <= len("unix:///")):
        raise SafeFailure("docker-endpoint")


def fixture_password(directory):
    """Read only the documented plain .env key. Never source shell text or import product code."""
    path = Path(directory) / ".env"
    descriptor = None
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_size > 65536:
            raise SafeFailure("invalid-fixture-file")
        with os.fdopen(descriptor, "r", encoding="utf-8") as stream:
            descriptor = None
            found = []
            for line in stream:
                key, separator, value = line.partition("=")
                if separator and key.strip() == "LOCAL_FIXTURE_PASSWORD":
                    found.append(value.strip())
        if len(found) != 1 or not found[0] or any(c.isspace() or ord(c) < 32 for c in found[0]):
            raise SafeFailure("invalid-fixture-password")
        return found[0]
    except (OSError, UnicodeError):
        raise SafeFailure("fixture-file-unavailable") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)


def local_qa_environment(password, version, *, allow_mutation=False, exclusive=False, source=None):
    if allow_mutation != exclusive:
        raise SafeFailure("mutation-requires-exclusive-fixtures")
    env = runtime_environment(source)
    env.update({
        "ODEXA_ENV": "local",
        "ODEXA_VERSION": validate_version(version),
        "ODEXA_BASE_URL": "http://localhost:8080",
        "ODEXA_TOKEN_URL": "http://localhost:8180/realms/odexa/protocol/openid-connect/token",
        "ODEXA_CLIENT_ID": "odexa-cli",
        "ODEXA_FIXTURE_PASSWORD": password,
        "ODEXA_ALLOW_MUTATION": str(allow_mutation).lower(),
        "ODEXA_EXCLUSIVE_FIXTURES": str(exclusive).lower(),
    })
    return env


def run_api(qa_root, env):
    checked([str(Path(qa_root) / "gradlew"), "--no-daemon", "--no-configuration-cache",
             "--console=plain", "apiTest"], cwd=qa_root, env=env, timeout=1200, stage="api-tests")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_status(url, timeout):
    # LOCAL readiness must not forward requests through ambient proxies or follow redirects.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(url, timeout=timeout) as response:
            return response.status
    except urllib.error.HTTPError as error:
        code = error.code
        error.close()
        return code
    except (OSError, urllib.error.URLError, ValueError):
        return 0


def wait_ready(metadata, timeout=180, *, probe=http_status, clock=time.monotonic, pause=None):
    pause = threading.Event().wait if pause is None else pause
    deadline = clock() + timeout
    statuses = {name: 0 for name in HEALTH_ENDPOINTS}
    metadata["http_status"] = statuses
    delay = 0.2
    while clock() < deadline:
        for name, url in HEALTH_ENDPOINTS.items():
            remaining = deadline - clock()
            if remaining <= 0:
                break
            code = probe(url, min(5.0, remaining))
            statuses[name] = code if isinstance(code, int) and 100 <= code <= 599 else 0
        if all(code == 200 for code in statuses.values()):
            return
        remaining = deadline - clock()
        if remaining > 0:
            pause(min(delay, remaining))
            delay = min(delay * 1.6, 2.0)
    raise SafeFailure("http-readiness-timeout")


@dataclass(frozen=True, repr=False)
class OwnedTarget:
    path: Path
    temp_parent: Path
    project: str

    @classmethod
    def create(cls):
        parent = Path(tempfile.gettempdir()).resolve()
        path = Path(tempfile.mkdtemp(prefix=TEMP_PREFIX, dir=parent))
        return cls(path, parent, "odexa-qa-" + uuid.uuid4().hex)

    def validate(self):
        if (self.path.is_symlink() or self.path.parent != self.temp_parent
                or self.path.resolve().parent != self.temp_parent.resolve()
                or not self.path.name.startswith(TEMP_PREFIX)
                or len(self.path.name) <= len(TEMP_PREFIX)
                or not PROJECT_PATTERN.fullmatch(self.project)):
            raise SafeFailure("unsafe-owned-target")

    def compose(self, *arguments):
        self.validate()
        return ["docker", "compose", "--project-name", self.project,
                "--project-directory", str(self.path), "--env-file", str(self.path / ".env"),
                *arguments]

    def preserve(self):
        """Keep generated secrets private and expose only identifiers needed to retry down."""
        self.validate()
        self.path.chmod(0o700)
        return {"checkout_path": str(self.path), "project": self.project}

    def remove(self):
        self.validate()
        if self.path.exists():
            shutil.rmtree(self.path)


def compose_metadata(target, env):
    # Go template deliberately excludes Command, Labels, names, ports and arbitrary status text.
    output = checked(target.compose("ps", "--all", "--format",
                                   "{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}"),
                     cwd=target.path, env=env, timeout=30, stage="compose-metadata", capture=True)
    entries = []
    for line in output.decode("utf-8", errors="replace").splitlines():
        fields = line.split("|")
        if len(fields) != 4 or fields[0] not in SERVICES:
            continue
        service, state, health, exit_code = fields
        entries.append({"service": service, "state": state if state in STATES else "unknown",
                        "health": health if health in HEALTH_STATES else "none",
                        "exit_code": int(exit_code) if re.fullmatch(r"[0-9]{1,3}", exit_code)
                        and int(exit_code) <= 255 else None})
    return entries


def write_metadata(qa_root, metadata):
    # Only allowlisted metadata, including an owned recovery checkout path when retained.
    # Never copy secret files, raw exceptions, command output or env values here.
    directory = Path(qa_root) / "build" / "diagnostics"
    for path in (Path(qa_root) / "build", directory):
        if path.is_symlink():
            raise SafeFailure("unsafe-diagnostics-path")
        path.mkdir(exist_ok=True)
    path = directory / "local-target.json"
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(metadata, stream, indent=2, sort_keys=True)
        stream.write("\n")


@contextmanager
def interrupt_guard():
    """Route cooperative CI cancellation through finally; SIGKILL/host loss cannot be trapped."""
    def interrupt(signum, frame):
        raise InterruptedError

    previous = {sig: signal.signal(sig, interrupt) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        yield
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


@contextmanager
def finish_cleanup():
    previous = {sig: signal.signal(sig, signal.SIG_IGN) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        yield
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def run_isolated(version=BASELINE_VERSION, *, qa_root=QA_ROOT):
    metadata = {"mode": "local", "result": "failed", "cleanup": "not-created"}
    target = None
    compose_attempted = False
    env = runtime_environment()
    exit_code = 1
    stage = "target-version"
    try:
        version = validate_version(version)
        metadata["version"] = version
        stage = "docker-endpoint"
        docker_endpoint_preflight(cwd=qa_root, env=env)
        stage = "temporary-checkout"
        target = OwnedTarget.create()
        metadata["project"] = target.project
        env["COMPOSE_PROJECT_NAME"] = target.project
        stage = "clone"
        checked(["git", "-c", "core.hooksPath=/dev/null", "clone", "--no-checkout", "--depth", "1",
                 "--single-branch", "--no-recurse-submodules", "--branch", version,
                 PRODUCT_REPOSITORY, str(target.path)], cwd=qa_root, env=env, timeout=180, stage=stage)
        stage = "verify-release"
        raw = checked(["git", "rev-parse", "--verify", f"refs/tags/{version}^{{commit}}"],
                      cwd=target.path, env=env, timeout=30, stage=stage, capture=True)
        commit = raw.decode("ascii").strip()
        if not COMMIT_PATTERN.fullmatch(commit):
            raise SafeFailure("invalid-release-commit")
        if commit != TARGET_COMMITS[version]:
            raise SafeFailure("release-pin-mismatch")
        metadata["commit"] = commit
        stage = "checkout"
        checked(["git", "-c", "core.hooksPath=/dev/null", "checkout", "--detach", commit],
                cwd=target.path, env=env, timeout=60, stage=stage)
        head = checked(["git", "rev-parse", "HEAD"], cwd=target.path, env=env,
                       timeout=30, stage=stage, capture=True).decode("ascii").strip()
        if head != commit:
            raise SafeFailure("checkout-pin-mismatch")
        stage = "bootstrap"
        checked([sys.executable, "scripts/bootstrap-local.py"], cwd=target.path, env=env,
                timeout=60, stage=stage)
        stage = "compose-up"
        # Set BEFORE starting: even partial startup or a timeout must tear down this exact project.
        compose_attempted = True
        checked(target.compose("up", "--build", "-d", "--wait", "--wait-timeout", "300"),
                cwd=target.path, env=env, timeout=1500, stage=stage)
        stage = "readiness"
        wait_ready(metadata)
        stage = "fixture-bridge"
        qa_env = local_qa_environment(fixture_password(target.path), version,
                                      allow_mutation=True, exclusive=True)
        stage = "api-tests"
        try:
            run_api(qa_root, qa_env)
        finally:
            qa_env.clear()
        metadata["result"] = "passed"
        exit_code = 0
    except SafeFailure as failure:
        metadata["failure_stage"] = stage
        metadata["exit_code"] = failure.exit_code
        exit_code = failure.exit_code
    except (KeyboardInterrupt, InterruptedError):
        metadata["failure_stage"] = "interrupted"
        exit_code = 130
    except Exception:
        # External exception messages may include URLs, credentials or captured command output.
        metadata["failure_stage"] = stage
        exit_code = 1
    finally:
        with finish_cleanup():
            if target is not None:
                metadata["cleanup"] = "passed"
                if compose_attempted:
                    if "http_status" not in metadata:
                        statuses = {}
                        for name, url in HEALTH_ENDPOINTS.items():
                            try:
                                code = http_status(url, 2)
                                statuses[name] = code if isinstance(code, int) and 100 <= code <= 599 else 0
                            except Exception:
                                statuses[name] = 0
                        metadata["http_status"] = statuses
                    try:
                        metadata["containers"] = compose_metadata(target, env)
                    except Exception:
                        metadata["container_metadata"] = "unavailable"
                    try:
                        checked(target.compose("down", "--volumes", "--remove-orphans", "--timeout", "30"),
                                cwd=target.path, env=env, timeout=120, stage="compose-down")
                        metadata["compose_cleanup"] = "passed"
                    except Exception:
                        metadata["compose_cleanup"] = "failed"
                        metadata["cleanup"] = "failed"
                        exit_code = exit_code or 1
                try:
                    if compose_attempted and metadata["compose_cleanup"] != "passed":
                        metadata["checkout_cleanup"] = "retained"
                        metadata["recovery"] = target.preserve()
                    else:
                        target.remove()
                        metadata["checkout_cleanup"] = "passed"
                except Exception:
                    metadata["checkout_cleanup"] = "failed"
                    metadata["cleanup"] = "failed"
                    exit_code = exit_code or 1
            if exit_code:
                metadata["result"] = "failed"
            metadata["exit_code"] = exit_code
            try:
                write_metadata(qa_root, metadata)
            except Exception:
                exit_code = exit_code or 1
                print("LOCAL diagnostics could not be written.", file=sys.stderr)
    print(f"LOCAL suite exit={exit_code}; cleanup={metadata['cleanup']}. QA reports remain.")
    if "failure_stage" in metadata:
        print(f"LOCAL failure stage={metadata['failure_stage']}; exit={exit_code}.", file=sys.stderr)
    if metadata["cleanup"] == "passed":
        print("Owned ephemeral checkout removed; any started project volumes were discarded (not recoverable).")
    elif metadata["cleanup"] == "failed":
        print("Owned cleanup incomplete; consult allowlisted local-target.json metadata.", file=sys.stderr)
    return exit_code


def run_existing(directory, *, version=BASELINE_VERSION, allow_mutation=False, exclusive=False,
                 qa_root=QA_ROOT):
    env = {}
    try:
        validate_version(version)
        if allow_mutation != exclusive:
            raise SafeFailure("mutation-requires-exclusive-fixtures")
        env = local_qa_environment(fixture_password(directory), version,
                                  allow_mutation=allow_mutation, exclusive=exclusive)
        run_api(qa_root, env)
        print("Existing LOCAL suite passed; no target lifecycle actions performed.")
        return 0
    except SafeFailure as failure:
        print(f"Existing LOCAL suite failed; exit={failure.exit_code}. See QA test reports.", file=sys.stderr)
        return failure.exit_code
    except (KeyboardInterrupt, InterruptedError):
        print("Existing LOCAL suite interrupted; target left running.", file=sys.stderr)
        return 130
    except Exception:
        print("Existing LOCAL suite failed; no target lifecycle actions performed.", file=sys.stderr)
        return 1
    finally:
        env.clear()
