"""Lifecycle/credential regressions using only stdlib mocks: never clone, run Java or use Docker."""

from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import odexa_local as local

# Deliberately conspicuous dummy data, not a real credential.
SENTINEL = "dummy-fixture-never-in-output-123456789"


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="qa-script-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.qa = self.root / "qa"
        self.qa.mkdir()
        self.target_path = self.root / "odexa-target-owned"
        self.target_path.mkdir(mode=0o700)
        self.target = local.OwnedTarget(self.target_path, self.root, "odexa-qa-" + "1" * 32)
        self.calls = []
        self.failures = {}
        self.commit = local.BASELINE_COMMIT
        self.head = local.BASELINE_COMMIT
        self.output = io.StringIO()

    def fake_command(self, argv, *, cwd, env, timeout, capture=False):
        self.calls.append((list(argv), Path(cwd), dict(env), timeout, capture))
        if argv[0] == "git":
            operation = "clone" if "clone" in argv else "checkout" if "checkout" in argv else "revision"
        elif "scripts/bootstrap-local.py" in argv:
            operation = "bootstrap"
        elif argv[0] == "docker":
            operation = next(op for op in ("up", "down", "ps") if op in argv)
        else:
            operation = "api"
        failure = self.failures.get(operation)
        if isinstance(failure, BaseException):
            raise failure
        if failure:
            return subprocess.CompletedProcess(argv, failure, SENTINEL.encode())
        output = b""
        if operation == "revision":
            output = ((self.head if argv[-1] == "HEAD" else self.commit) + "\n").encode()
        if operation == "bootstrap":
            (self.target_path / ".env").write_text(
                "# generated fixture\nLOCAL_FIXTURE_PASSWORD=" + SENTINEL + "\nOTHER_PASSWORD=ignored\n")
        if operation == "ps":
            output = ("gateway|running|healthy|0\n"
                      + SENTINEL + "|running|healthy|0\n"
                      + "catalog|" + SENTINEL + "|" + SENTINEL + "|" + SENTINEL + "\n").encode()
        return subprocess.CompletedProcess(argv, 0, output)

    def run_target(self, *, version=local.BASELINE_VERSION, readiness=None):
        def ready(metadata):
            metadata["http_status"] = {"gateway": 200, "keycloak": 200}
        def check_endpoint(*, cwd, env):
            self.assertEqual(self.qa, cwd)
            self.assertEqual(local.runtime_environment(), env)
            self.assertFalse(self.calls)
        with (patch.object(local.OwnedTarget, "create", return_value=self.target),
              patch.object(local, "docker_endpoint_preflight", side_effect=check_endpoint) as preflight,
              patch.object(local, "command", side_effect=self.fake_command),
              patch.object(local, "wait_ready", side_effect=readiness or ready),
              patch.object(local, "http_status", return_value=503),
              redirect_stdout(self.output), redirect_stderr(self.output)):
            result = local.run_isolated(version, qa_root=self.qa)
            preflight.assert_called_once()
        self.metadata_text = (self.qa / "build/diagnostics/local-target.json").read_text()
        self.metadata = json.loads(self.metadata_text)
        self.assertNotIn(SENTINEL, self.output.getvalue())
        self.assertNotIn(SENTINEL, self.metadata_text)
        for argv, _, _, _, _ in self.calls:
            self.assertNotIn(SENTINEL, " ".join(argv))
        return result

    def assert_owned_down(self, *, retained=False):
        calls = [call for call in self.calls if "down" in call[0]]
        self.assertEqual(1, len(calls))
        argv, cwd, env, _, _ = calls[0]
        self.assertEqual(self.target_path, cwd)
        self.assertEqual(self.target.project, argv[argv.index("--project-name") + 1])
        self.assertEqual(self.target.project, env["COMPOSE_PROJECT_NAME"])
        self.assertEqual(str(self.target_path / ".env"), argv[argv.index("--env-file") + 1])
        self.assertIn("--volumes", argv)
        self.assertIn("--remove-orphans", argv)
        self.assertNotIn("ODEXA_FIXTURE_PASSWORD", env)
        if retained:
            self.assertTrue(self.target_path.is_dir())
            self.assertEqual(0o700, self.target_path.stat().st_mode & 0o777)
            self.assertTrue((self.target_path / ".env").is_file())
            self.assertEqual("retained", self.metadata["checkout_cleanup"])
            self.assertEqual({"checkout_path": str(self.target_path), "project": self.target.project},
                             self.metadata["recovery"])
            self.assertEqual(["local-target.json"],
                             [path.name for path in (self.qa / "build/diagnostics").iterdir()])
        else:
            self.assertFalse(self.target_path.exists())
            self.assertNotIn("recovery", self.metadata)

    def test_success_pin_bootstrap_env_bridge_and_owned_cleanup(self):
        with patch.dict(os.environ, {"ODEXA_ENV": "remote", "ODEXA_CUSTOMER_A_PASSWORD": "wrong",
                                     "LOCAL_FIXTURE_PASSWORD": "wrong", "COMPOSE_FILE": "/dev/unsafe",
                                     "COMPOSE_PROJECT_NAME": "developer", "JAVA_TOOL_OPTIONS": "unsafe"}):
            self.assertEqual(0, self.run_target())
        self.assert_owned_down()
        self.assertEqual(local.BASELINE_COMMIT, self.metadata["commit"])
        self.assertEqual("passed", self.metadata["result"])
        self.assertEqual("passed", self.metadata["cleanup"])
        git_checkout = next(c[0] for c in self.calls if "checkout" in c[0])
        self.assertEqual(local.BASELINE_COMMIT, git_checkout[-1])
        clone = next(c[0] for c in self.calls if "clone" in c[0])
        self.assertIn("--no-recurse-submodules", clone)
        self.assertIn(local.BASELINE_VERSION, clone)
        api = next(c for c in self.calls if "apiTest" in c[0])
        self.assertEqual(SENTINEL, api[2]["ODEXA_FIXTURE_PASSWORD"])
        self.assertEqual("true", api[2]["ODEXA_ALLOW_MUTATION"])
        self.assertEqual("true", api[2]["ODEXA_EXCLUSIVE_FIXTURES"])
        self.assertEqual("local", api[2]["ODEXA_ENV"])
        self.assertIn("--no-configuration-cache", api[0])
        for _, _, env, _, _ in self.calls:
            for key in ("COMPOSE_FILE", "LOCAL_FIXTURE_PASSWORD", "JAVA_TOOL_OPTIONS", "ODEXA_CUSTOMER_A_PASSWORD"):
                self.assertNotIn(key, env)
        self.assertEqual("unknown", self.metadata["containers"][1]["state"])
        self.assertEqual(2, len(self.metadata["containers"]))

    def test_partial_startup_failure_still_tears_down(self):
        self.failures["up"] = 17
        self.assertEqual(17, self.run_target())
        self.assert_owned_down()
        self.assertFalse(any("apiTest" in c[0] for c in self.calls))
        self.assertEqual("compose-up", self.metadata["failure_stage"])
        self.assertEqual({"gateway": 503, "keycloak": 503}, self.metadata["http_status"])

    def test_startup_timeout_still_tears_down(self):
        self.failures["up"] = local.SafeFailure("command-timeout")
        self.assertNotEqual(0, self.run_target())
        self.assert_owned_down()

    def test_api_failure_preserves_exit_and_cleans(self):
        self.failures["api"] = 7
        self.assertEqual(7, self.run_target())
        self.assert_owned_down()
        self.assertEqual("api-tests", self.metadata["failure_stage"])

    def test_readiness_failure_cleans_and_does_not_start_tests(self):
        def unavailable(metadata):
            metadata["http_status"] = {"gateway": 503, "keycloak": 0}
            raise local.SafeFailure("http-readiness-timeout")
        self.assertEqual(1, self.run_target(readiness=unavailable))
        self.assert_owned_down()
        self.assertEqual(503, self.metadata["http_status"]["gateway"])
        self.assertFalse(any("apiTest" in c[0] for c in self.calls))

    def test_interrupt_and_secret_bearing_exception_are_sanitized(self):
        self.failures["up"] = InterruptedError(SENTINEL)
        self.assertEqual(130, self.run_target())
        self.assert_owned_down()

    def test_unexpected_exception_does_not_leak_cause(self):
        self.failures["api"] = RuntimeError(SENTINEL)
        self.assertEqual(1, self.run_target())
        self.assert_owned_down()

    def test_down_failure_fails_successful_suite_but_retains_private_checkout(self):
        self.failures["down"] = 9
        self.target_path.chmod(0o755)
        with patch.object(local.OwnedTarget, "remove") as remove:
            self.assertEqual(1, self.run_target())
            remove.assert_not_called()
        self.assert_owned_down(retained=True)
        self.assertEqual("failed", self.metadata["result"])
        self.assertEqual("failed", self.metadata["cleanup"])
        self.assertEqual("failed", self.metadata["compose_cleanup"])

    def test_down_exception_retains_recovery_without_leaking_raw_error(self):
        self.failures["down"] = RuntimeError(SENTINEL)
        self.assertEqual(1, self.run_target())
        self.assert_owned_down(retained=True)
        self.assertEqual("failed", self.metadata["compose_cleanup"])

    def test_cancelled_startup_preserves_exit_and_recovery_when_down_fails(self):
        self.failures.update({"up": InterruptedError(SENTINEL), "down": 9})
        self.assertEqual(130, self.run_target())
        self.assert_owned_down(retained=True)
        self.assertEqual(130, self.metadata["exit_code"])
        self.assertEqual("interrupted", self.metadata["failure_stage"])

    def test_fixture_read_failure_after_startup_still_cleans(self):
        with patch.object(local, "fixture_password", side_effect=OSError(SENTINEL)):
            self.assertEqual(1, self.run_target())
        self.assert_owned_down()
        self.assertEqual("fixture-bridge", self.metadata["failure_stage"])
        self.assertFalse(any("apiTest" in c[0] for c in self.calls))

    def test_api_exit_is_preserved_when_cleanup_also_fails(self):
        self.failures.update({"api": 7, "down": 9})
        self.assertEqual(7, self.run_target())
        self.assert_owned_down(retained=True)
        self.assertEqual("failed", self.metadata["cleanup"])

    def test_diagnostics_failure_never_prevents_teardown(self):
        self.failures["ps"] = RuntimeError(SENTINEL)
        self.assertEqual(0, self.run_target())
        self.assert_owned_down()
        self.assertEqual("unavailable", self.metadata["container_metadata"])

    def test_clone_failure_removes_exact_temp_without_docker(self):
        self.failures["clone"] = 3
        self.assertEqual(3, self.run_target())
        self.assertFalse(self.target_path.exists())
        self.assertFalse(any(c[0][0] == "docker" for c in self.calls))

    def test_bootstrap_failure_removes_generated_secrets_without_starting_docker(self):
        (self.target_path / ".env").write_text("LOCAL_FIXTURE_PASSWORD=" + SENTINEL)
        self.failures["bootstrap"] = 2
        self.assertEqual(2, self.run_target())
        self.assertFalse(self.target_path.exists())
        self.assertFalse(any(c[0][0] == "docker" for c in self.calls))

    def test_baseline_tag_must_dereference_to_known_commit(self):
        self.commit = "0" * 40
        self.assertEqual(1, self.run_target())
        self.assertFalse(self.target_path.exists())
        self.assertFalse(any("scripts/bootstrap-local.py" in c[0] for c in self.calls))
        self.assertEqual("verify-release", self.metadata["failure_stage"])

    def test_actual_checkout_must_match_verified_tag(self):
        self.head = "0" * 40
        self.assertEqual(1, self.run_target())
        self.assertFalse(any("scripts/bootstrap-local.py" in c[0] for c in self.calls))

    def test_unallowlisted_release_is_rejected_before_any_actions(self):
        with (patch.object(local, "docker_endpoint_preflight") as preflight,
              patch.object(local.OwnedTarget, "create") as create,
              redirect_stdout(self.output), redirect_stderr(self.output)):
            self.assertEqual(1, local.run_isolated("v0.2.0", qa_root=self.qa))
        preflight.assert_not_called()
        create.assert_not_called()
        metadata = json.loads((self.qa / "build/diagnostics/local-target.json").read_text())
        self.assertEqual("target-version", metadata["failure_stage"])
        self.assertEqual("not-created", metadata["cleanup"])

    def test_invalid_version_is_not_executed_or_printed(self):
        with patch.object(local.OwnedTarget, "create") as create:
            # Call directly because run_target supplies its own creation mock.
            with redirect_stdout(self.output), redirect_stderr(self.output):
                result = local.run_isolated(SENTINEL, qa_root=self.qa)
            create.assert_not_called()
        self.assertEqual(1, result)
        self.assertNotIn(SENTINEL, self.output.getvalue())
        self.assertNotIn(SENTINEL, (self.qa / "build/diagnostics/local-target.json").read_text())

    def test_failed_checkout_deletion_is_a_failure(self):
        with patch.object(local.OwnedTarget, "remove", side_effect=OSError(SENTINEL)):
            self.assertEqual(1, self.run_target())
        self.assertEqual("failed", self.metadata["cleanup"])
        self.assertTrue(any("down" in c[0] for c in self.calls))

    def test_existing_bridge_reads_only_env_and_never_does_product_lifecycle(self):
        # Deliberately no product sources, Compose file, .git, bootstrap or Java classes here.
        (self.target_path / ".env").write_text("LOCAL_FIXTURE_PASSWORD=" + SENTINEL + "\n")
        with (patch.object(local, "command", side_effect=self.fake_command),
              redirect_stdout(self.output), redirect_stderr(self.output)):
            self.assertEqual(0, local.run_existing(self.target_path, qa_root=self.qa))
        self.assertEqual(1, len(self.calls))
        argv, cwd, env, _, _ = self.calls[0]
        self.assertIn("apiTest", argv)
        self.assertEqual(self.qa, cwd)
        self.assertNotIn(str(self.target_path), " ".join(argv))
        self.assertEqual("false", env["ODEXA_ALLOW_MUTATION"])
        self.assertEqual(SENTINEL, env["ODEXA_FIXTURE_PASSWORD"])
        self.assertNotIn(SENTINEL, self.output.getvalue())
        self.assertTrue(self.target_path.exists())

    def test_existing_bridge_requires_both_mutation_flags_before_file_access(self):
        with (patch.object(local, "fixture_password") as read,
              redirect_stdout(self.output), redirect_stderr(self.output)):
            self.assertEqual(1, local.run_existing(self.target_path, allow_mutation=True, qa_root=self.qa))
            read.assert_not_called()

    def test_existing_bridge_preserves_test_failure_without_touching_target(self):
        (self.target_path / ".env").write_text("LOCAL_FIXTURE_PASSWORD=" + SENTINEL)
        self.failures["api"] = 6
        with (patch.object(local, "command", side_effect=self.fake_command),
              redirect_stdout(self.output), redirect_stderr(self.output)):
            self.assertEqual(6, local.run_existing(self.target_path, qa_root=self.qa))
        self.assertTrue(self.target_path.exists())
        self.assertEqual(1, len(self.calls))
        self.assertNotIn(SENTINEL, self.output.getvalue())


class DockerEndpointTests(unittest.TestCase):
    INSPECT = ["docker", "context", "inspect", "--format", "{{json .Endpoints.docker.Host}}"]

    def assert_rejected_before_actions(self, env, *, inspected=b"", returncode=0):
        output = io.StringIO()
        with tempfile.TemporaryDirectory(prefix="qa-endpoint-") as temporary:
            qa = Path(temporary)
            with (patch.dict(os.environ, env, clear=True),
                  patch.object(local, "command", return_value=subprocess.CompletedProcess(
                      self.INSPECT, returncode, inspected)) as command,
                  patch.object(local.OwnedTarget, "create") as create,
                  patch.object(local.OwnedTarget, "remove") as remove,
                  patch.object(local, "http_status") as probe,
                  patch.object(local, "wait_ready") as ready,
                  patch.object(local, "run_api") as api,
                  redirect_stdout(output), redirect_stderr(output)):
                self.assertEqual(returncode or 1, local.run_isolated(qa_root=qa))
            create.assert_not_called()
            remove.assert_not_called()
            probe.assert_not_called()
            ready.assert_not_called()
            api.assert_not_called()
            # The only permitted command is a captured context lookup, never lifecycle/cleanup.
            if env.get("DOCKER_HOST") and not env.get("DOCKER_CONTEXT"):
                command.assert_not_called()
            else:
                command.assert_called_once_with(self.INSPECT, cwd=qa,
                                                env=local.runtime_environment(env),
                                                timeout=30, capture=True)
            text = (qa / "build/diagnostics/local-target.json").read_text()
            metadata = json.loads(text)
            self.assertEqual("docker-endpoint", metadata["failure_stage"])
            self.assertEqual("not-created", metadata["cleanup"])
            self.assertEqual("failed", metadata["result"])
            self.assertNotIn("project", metadata)
            self.assertNotIn("recovery", metadata)
            self.assertNotIn(SENTINEL, text + output.getvalue())
            for value in env.values():
                if value:
                    self.assertNotIn(value, text + output.getvalue())

    def test_nonlocal_and_tcp_hosts_reject_before_any_actions_or_cleanup(self):
        for endpoint in ("ssh://" + SENTINEL + "@remote.invalid",
                         "tcp://" + SENTINEL + "@remote.invalid:2376",
                         "tcp://localhost:2375", "tcp://127.0.0.1:2375",
                         "tcp://[::1]:2375", "npipe:////./pipe/docker_engine"):
            with self.subTest(endpoint=endpoint):
                self.assert_rejected_before_actions({"DOCKER_HOST": endpoint})

    def test_active_remote_context_rejects_using_inherited_config(self):
        self.assert_rejected_before_actions(
            {"DOCKER_CONFIG": "/unused/docker-config"},
            inspected=json.dumps("ssh://" + SENTINEL + "@remote.invalid").encode())

    def test_explicit_remote_context_takes_priority_over_local_host(self):
        self.assert_rejected_before_actions(
            {"DOCKER_CONTEXT": SENTINEL, "DOCKER_HOST": "unix:///var/run/docker.sock"},
            inspected=json.dumps("tcp://remote.invalid:2376").encode())

    def test_explicit_local_context_takes_priority_over_remote_host(self):
        env = {"DOCKER_CONTEXT": SENTINEL, "DOCKER_HOST": "ssh://remote.invalid",
               "DOCKER_CONFIG": "/unused/docker-config"}
        with patch.object(local, "command", return_value=subprocess.CompletedProcess(
                self.INSPECT, 0, b'"unix:///var/run/docker.sock"\n')) as command:
            local.docker_endpoint_preflight(cwd=SCRIPTS, env=env)
        command.assert_called_once_with(self.INSPECT, cwd=SCRIPTS, env=env, timeout=30, capture=True)
        self.assertNotIn(SENTINEL, " ".join(command.call_args.args[0]))

    def test_docker_host_overrides_active_remote_context_without_inspecting(self):
        for context in (None, ""):
            env = {"DOCKER_HOST": "unix:///run/user/1000/docker.sock",
                   "DOCKER_CONFIG": "/unused/docker-config"}
            if context is not None:
                env["DOCKER_CONTEXT"] = context
            with (self.subTest(context=context),
                  patch.object(local, "command", return_value=subprocess.CompletedProcess(
                      self.INSPECT, 0, b'"ssh://remote.invalid"')) as command):
                local.docker_endpoint_preflight(cwd=SCRIPTS, env=env)
                command.assert_not_called()

    def test_remote_host_overrides_active_local_context(self):
        self.assert_rejected_before_actions(
            {"DOCKER_HOST": "ssh://remote.invalid"},
            inspected=b'"unix:///var/run/docker.sock"')

    def test_active_unix_socket_context_is_accepted(self):
        for env in ({}, {"DOCKER_HOST": "", "DOCKER_CONTEXT": ""}):
            with (self.subTest(env=env),
                  patch.object(local, "command", return_value=subprocess.CompletedProcess(
                      self.INSPECT, 0, b'"unix:///run/user/1000/docker.sock"')) as command):
                local.docker_endpoint_preflight(cwd=SCRIPTS, env=env)
                command.assert_called_once_with(self.INSPECT, cwd=SCRIPTS, env=env,
                                                timeout=30, capture=True)

    def test_invalid_context_output_fails_closed_without_disclosure(self):
        for inspected in (b"", SENTINEL.encode(), b"\xff", b"null", b"{}", b"[]", b"123",
                          b'""', b'"unix:///"', b'"unix://relative.sock"',
                          b'"tcp://localhost:2375"'):
            with self.subTest(inspected=inspected):
                self.assert_rejected_before_actions({}, inspected=inspected)

    def test_failed_context_inspection_preserves_exit_without_disclosure(self):
        self.assert_rejected_before_actions({"DOCKER_CONTEXT": SENTINEL},
                                            inspected=SENTINEL.encode(), returncode=19)


class SafetyTests(unittest.TestCase):
    def test_rejects_arbitrary_refs(self):
        for value in ("v0.2.0", "main", "--help", "../v0.1.0", "v0.1.0;echo", "v0.1.0\n",
                      "v1.2", "v1.2.3-rc1", ""):
            with self.subTest(value=value), self.assertRaises(local.SafeFailure):
                local.validate_version(value)

    def test_owned_directory_guard_rejects_broad_foreign_and_symlink_paths(self):
        with tempfile.TemporaryDirectory(prefix="qa-safety-") as temporary:
            parent = Path(temporary).resolve()
            owned = parent / "odexa-target-safe"
            owned.mkdir()
            foreign = parent / "developer"
            foreign.mkdir()
            marker = foreign / "keep"
            marker.write_text("untouched")
            link = parent / "odexa-target-link"
            link.symlink_to(foreign, target_is_directory=True)
            for path in (Path("/"), parent, foreign, parent / "odexa-target-", link):
                with self.subTest(path=path), self.assertRaises(local.SafeFailure):
                    local.OwnedTarget(path, parent, "odexa-qa-" + "1" * 32).remove()
            with self.assertRaises(local.SafeFailure):
                local.OwnedTarget(owned, parent, "developer").compose("down")
            self.assertEqual("untouched", marker.read_text())
            self.assertTrue(owned.exists())

    def test_new_target_identity_is_unique_and_under_temp_parent(self):
        first = local.OwnedTarget.create()
        second = local.OwnedTarget.create()
        try:
            self.assertNotEqual(first.path, second.path)
            self.assertNotEqual(first.project, second.project)
            self.assertEqual(Path(tempfile.gettempdir()).resolve(), first.path.parent)
            self.assertEqual(0o700, first.path.stat().st_mode & 0o777)
            self.assertEqual(0o700, second.path.stat().st_mode & 0o777)
            first.validate()
            second.validate()
        finally:
            first.remove()
            second.remove()

    def test_env_parser_rejects_symlink_missing_duplicate_and_empty_key(self):
        with tempfile.TemporaryDirectory(prefix="qa-env-") as temporary:
            root = Path(temporary)
            path = root / ".env"
            for contents in ("", "LOCAL_FIXTURE_PASSWORD=\n", "OTHER_PASSWORD=value\n",
                             "LOCAL_FIXTURE_PASSWORD=one\nLOCAL_FIXTURE_PASSWORD=two\n"):
                path.write_text(contents)
                with self.assertRaises(local.SafeFailure):
                    local.fixture_password(root)
            path.unlink()
            source = root / "source"
            source.write_text("LOCAL_FIXTURE_PASSWORD=" + SENTINEL)
            path.symlink_to(source)
            with self.assertRaises(local.SafeFailure):
                local.fixture_password(root)

    def test_fixture_parser_does_not_execute_shell_text(self):
        with tempfile.TemporaryDirectory(prefix="qa-env-") as temporary:
            root = Path(temporary)
            (root / ".env").write_text("LOCAL_FIXTURE_PASSWORD=$(not-executed)\n")
            self.assertEqual("$(not-executed)", local.fixture_password(root))

    def test_readiness_is_bounded_and_uses_condition_backoff(self):
        elapsed = [0.0]
        pauses = []
        probes = []
        def pause(seconds):
            pauses.append(seconds)
            elapsed[0] += seconds
        def probe(url, timeout):
            probes.append((url, timeout))
            return 503
        metadata = {}
        with self.assertRaises(local.SafeFailure):
            local.wait_ready(metadata, timeout=1, clock=lambda: elapsed[0], pause=pause, probe=probe)
        self.assertEqual(1, elapsed[0])
        self.assertTrue(probes)
        self.assertGreater(len(pauses), 1)
        self.assertNotEqual(pauses[0], pauses[1])
        self.assertTrue(all(timeout <= 1 for _, timeout in probes))
        self.assertEqual({"gateway": 503, "keycloak": 503}, metadata["http_status"])

    def test_readiness_stops_immediately_when_both_endpoints_are_ready(self):
        pause = MagicMock()
        metadata = {}
        local.wait_ready(metadata, probe=lambda url, timeout: 200, pause=pause)
        pause.assert_not_called()
        self.assertEqual({"gateway": 200, "keycloak": 200}, metadata["http_status"])

    def test_readiness_refuses_redirects_and_ambient_proxy(self):
        response = MagicMock()
        response.__enter__.return_value.status = 200
        opener = MagicMock()
        opener.open.return_value = response
        with patch.object(local.urllib.request, "build_opener", return_value=opener) as build:
            self.assertEqual(200, local.http_status(local.HEALTH_ENDPOINTS["gateway"], 1))
        handlers = build.call_args.args
        self.assertEqual({}, handlers[0].proxies)
        self.assertIsInstance(handlers[1], local.NoRedirect)
        self.assertIsNone(handlers[1].redirect_request(None, None, 302, None, None, "https://invalid.example"))

    def test_command_never_inherits_output_or_uses_shell(self):
        process = MagicMock()
        process.__enter__.return_value = process
        process.communicate.return_value = (None, None)
        process.returncode = 1
        output = io.StringIO()
        with (patch.object(local.subprocess, "Popen", return_value=process) as popen,
              redirect_stdout(output), redirect_stderr(output)):
            result = local.command(["test-runner"], cwd="/tmp", env={"ODEXA_FIXTURE_PASSWORD": SENTINEL}, timeout=1)
        options = popen.call_args.kwargs
        self.assertEqual(subprocess.DEVNULL, options["stdout"])
        self.assertEqual(subprocess.DEVNULL, options["stderr"])
        self.assertEqual(subprocess.DEVNULL, options["stdin"])
        self.assertTrue(options["start_new_session"])
        self.assertNotIn("shell", options)
        self.assertEqual(1, result.returncode)
        self.assertEqual("", output.getvalue())

    def test_command_timeout_kills_owned_process_group_and_sanitizes_error(self):
        process = MagicMock()
        process.__enter__.return_value = process
        process.pid = 12345
        process.communicate.side_effect = [subprocess.TimeoutExpired([SENTINEL], 1, output=SENTINEL), (None, None)]
        with (patch.object(local.subprocess, "Popen", return_value=process),
              patch.object(local.os, "killpg") as kill,
              self.assertRaises(local.SafeFailure) as raised):
            local.command(["test-runner"], cwd="/tmp", env={}, timeout=1)
        kill.assert_called_once_with(12345, signal.SIGKILL)
        self.assertNotIn(SENTINEL, str(raised.exception))
        self.assertTrue(raised.exception.__suppress_context__)

    def test_signal_guard_turns_cancellation_into_cleanup_path_and_restores_handlers(self):
        before = signal.getsignal(signal.SIGTERM)
        with local.interrupt_guard():
            handler = signal.getsignal(signal.SIGTERM)
            with self.assertRaises(InterruptedError):
                handler(signal.SIGTERM, None)
        self.assertEqual(before, signal.getsignal(signal.SIGTERM))

    def test_argument_errors_do_not_echo_rejected_credentials(self):
        spec = importlib.util.spec_from_file_location("run_local_cli", SCRIPTS / "run-local.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        output = io.StringIO()
        with (patch.object(sys, "argv", ["run-local.py", "--password", SENTINEL]),
              redirect_stderr(output), self.assertRaises(SystemExit) as raised):
            module.main()
        self.assertEqual(2, raised.exception.code)
        self.assertNotIn(SENTINEL, output.getvalue())


if __name__ == "__main__":
    unittest.main()
