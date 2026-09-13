#!/usr/bin/env python3
"""Small source/dependency guard for the HTTP-only QA boundary; emits rule/location, never matches."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA_RULES = (
    ("product-implementation", r"\b(?:import|package)\s+(?:static\s+)?(?:[\w]+\.)*commerce\."),
    ("direct-infrastructure", r"\bimport\s+(?:static\s+)?(?:java\.sql|javax\.sql|org\.postgresql|"
     r"org\.apache\.kafka|org\.springframework|org\.testcontainers|com\.zaxxer)\."),
    ("product-filesystem", r"(?:ODEXA_(?:DIR|ENV_FILE|REPO)|odexa[.]dir|bootstrap-local[.]py|"
     r"compose-smoke[.]py|odexa_local|[\"\'](?:[^\"\'\n]*/)?\.env[\"\']|"
     r"(?:services|infrastructure|libraries)/|/workspace/odexa(?:/|[\"\']))"),
    ("direct-runtime-access", r"(?:\bProcessBuilder\b|Runtime\.getRuntime\(\)\.exec|jdbc:|"
     r"\bdocker\s+compose\b)"),
)
BUILD_RULES = (
    ("product-project-dependency", r"\b(?:includeBuild|project)\s*\("),
    ("direct-infrastructure-dependency", r"(?:org\.postgresql|org\.apache\.kafka|org\.springframework|"
     r"org\.testcontainers|com\.zaxxer|com\.h2database|mysql|mariadb|io\.r2dbc|commerce[:.])"),
    ("product-orchestration-task", r"(?:bootstrap-local|compose-smoke|local-ci[.]py|run-local[.]py|"
     r"odexa_local|ODEXA_(?:DIR|ENV_FILE|REPO)|\bdocker\s+compose\b)"),
)


def violations(root):
    root = Path(root)
    findings = []
    if (root / ".gitmodules").exists():
        findings.append(("submodule", ".gitmodules", 1))
    for name in ("services", "libraries", "infrastructure"):
        if (root / name).exists():
            findings.append(("copied-product-tree", name, 1))
    for path in root.glob("*compose*.y*ml"):
        findings.append(("copied-compose", path.name, 1))
    inputs = [(path, JAVA_RULES) for path in sorted((root / "src").rglob("*.java"))]
    inputs += [(path, BUILD_RULES) for path in sorted(root.glob("*.gradle*")) if path.is_file()]
    catalog = root / "gradle" / "libs.versions.toml"
    if catalog.is_file():
        inputs.append((catalog, BUILD_RULES))
    for path, rules in inputs:
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            findings.append(("source-symlink", relative, 1))
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for rule, pattern in rules:
                if re.search(pattern, line, re.IGNORECASE):
                    findings.append((rule, relative, number))
    example = root / ".env.example"
    if example.exists():
        for number, line in enumerate(example.read_text(encoding="utf-8").splitlines(), 1):
            key, separator, value = line.partition("=")
            if (separator and not key.lstrip().startswith("#") and value.strip()
                    and re.search(r"(?:PASSWORD|SECRET|TOKEN|API_KEY)$", key.strip())):
                findings.append(("nonempty-example-secret", ".env.example", number))
    return findings


def main(root=ROOT):
    try:
        findings = violations(root)
    except (OSError, UnicodeError):
        print("Boundary checks failed: source could not be inspected.", file=sys.stderr)
        return 1
    for rule, path, line in findings:
        print(f"Boundary violation: {rule} at {path}:{line}", file=sys.stderr)
    if findings:
        return 1
    print("HTTP-only source/dependency boundary checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
