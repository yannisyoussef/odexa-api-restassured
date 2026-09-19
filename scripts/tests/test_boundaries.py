"""Guard regressions; intentionally malicious examples are local temporary fixture strings."""

from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("qa_boundaries", SCRIPTS / "check-boundaries.py")
boundaries = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundaries)


class BoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="qa-boundary-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.java = self.root / "src/main/java/qa/odexa/Example.java"
        self.java.parent.mkdir(parents=True)

    def rules(self):
        return {entry[0] for entry in boundaries.violations(self.root)}

    def test_public_http_and_classpath_contracts_are_allowed(self):
        self.java.write_text('package qa.odexa;\nimport io.restassured.RestAssured;\n'
                             'import java.nio.file.Files;\n'
                             'String mode = System.getenv("ODEXA_ENV");\n'
                             'String contract = "/contracts/odexa-v0.1.0/catalog.json";\n')
        (self.root / "build.gradle.kts").write_text('implementation("io.rest-assured:rest-assured:6.0.1")\n')
        self.assertEqual(set(), self.rules())

    def test_product_classes_and_direct_infrastructure_are_rejected(self):
        self.java.write_text('import commerce.catalog.Product;\nimport java.sql.Connection;\n'
                             'import org.apache.kafka.clients.consumer.KafkaConsumer;\n')
        self.assertEqual({"product-implementation", "direct-infrastructure"}, self.rules())

    def test_java_remote_runtime_must_not_read_product_files_or_launch_compose(self):
        for text in ('System.getenv("ODEXA_ENV_FILE")', 'Path.of("../odexa/.env")',
                     '"/workspace/odexa/services/catalog"', '"bootstrap-local.py"',
                     'new ProcessBuilder("docker", "compose")'):
            with self.subTest(text=text):
                self.java.write_text(text)
                self.assertTrue(self.rules())

    def test_gradle_api_task_has_no_product_runtime_dependency(self):
        (self.root / "build.gradle.kts").write_text('commandLine("python3", "scripts/local-ci.py")\n')
        self.assertIn("product-orchestration-task", self.rules())
        (self.root / "build.gradle.kts").write_text('implementation(project(":odexa"))\n')
        self.assertIn("product-project-dependency", self.rules())

    def test_infrastructure_dependencies_rejected_in_build_and_catalog(self):
        (self.root / "gradle").mkdir()
        (self.root / "gradle/libs.versions.toml").write_text('db = { module = "org.postgresql:postgresql" }\n')
        self.assertIn("direct-infrastructure-dependency", self.rules())

    def test_submodules_and_copied_product_trees_and_compose_rejected(self):
        (self.root / ".gitmodules").write_text("[submodule]\n")
        (self.root / "compose.yaml").write_text("services: {}\n")
        (self.root / "services").mkdir()
        self.assertEqual({"submodule", "copied-compose", "copied-product-tree"}, self.rules())

    def test_secret_findings_print_locations_not_values(self):
        sentinel = "dummy-secret-do-not-echo"
        (self.root / ".env.example").write_text("ODEXA_FIXTURE_PASSWORD=" + sentinel + "\n")
        output = io.StringIO()
        with redirect_stdout(output), redirect_stderr(output):
            self.assertEqual(1, boundaries.main(self.root))
        self.assertIn("nonempty-example-secret", output.getvalue())
        self.assertNotIn(sentinel, output.getvalue())

    def test_empty_environment_placeholders_and_comments_are_allowed(self):
        (self.root / ".env.example").write_text("ODEXA_FIXTURE_PASSWORD=\nODEXA_CLIENT_SECRET=\n"
                                               "# ODEXA_FIXTURE_PASSWORD=example-description\n")
        self.assertEqual(set(), self.rules())

    def test_provisioning_allows_containers_but_never_product_or_data_clients(self):
        infrastructure = self.root / "src/provisioning/java/qa/odexa/provisioning/Target.java"
        infrastructure.parent.mkdir(parents=True)
        infrastructure.write_text('import org.testcontainers.containers.GenericContainer;\n'
                                  'new ProcessBuilder("git", "clone");\n'
                                  'String jdbc = "jdbc:postgresql://postgres/catalog";\n')
        (self.root / "build.gradle.kts").write_text(
            '    "provisioningImplementation"("org.testcontainers:testcontainers:2.0.4")\n')
        self.assertEqual(set(), self.rules())
        infrastructure.write_text('import cc.odexa.orders.OrderApplication;\n'
                                  'import org.apache.kafka.clients.consumer.KafkaConsumer;\n')
        self.assertEqual({"product-implementation", "direct-data-client"}, self.rules())

    def test_containers_cannot_leak_to_core_or_normal_test_dependencies(self):
        self.java.write_text('import org.testcontainers.containers.GenericContainer;\n'
                             'import com.github.dockerjava.api.DockerClient;\n')
        self.assertEqual({"direct-infrastructure"}, self.rules())
        for scope in ("implementation", "testImplementation", "runtimeOnly"):
            (self.root / "build.gradle.kts").write_text(
                scope + '("org.testcontainers:testcontainers:2.0.4")\n')
            self.assertIn("direct-infrastructure-dependency", self.rules())

    def test_repository_sources_preserve_standalone_remote_boundary(self):
        # Inspect QA only; no product directory is used to validate REMOTE independence.
        self.assertEqual([], boundaries.violations(SCRIPTS.parent))


if __name__ == "__main__":
    unittest.main()
