#!/usr/bin/env python3
"""Run black-box QA against an isolated, disposable product release (exclusive Docker host ports)."""

import os
import sys

from odexa_local import BASELINE_VERSION, interrupt_guard, run_isolated


def main():
    if len(sys.argv) != 1:
        print("No command arguments accepted; set ODEXA_VERSION to an exact release tag.", file=sys.stderr)
        return 2
    with interrupt_guard():
        return run_isolated(os.environ.get("ODEXA_VERSION", BASELINE_VERSION))


if __name__ == "__main__":
    sys.exit(main())
