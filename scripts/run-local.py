#!/usr/bin/env python3
"""Optional existing LOCAL target bridge. Reads only .env; never starts/stops a developer target."""

import argparse
import os
import sys

from odexa_local import BASELINE_VERSION, interrupt_guard, run_existing


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default includes rejected values; someone may accidentally pass a credential.
        self.exit(2, "Invalid arguments; use --help. Credentials must never be passed as arguments.\n")


def main():
    parser = SafeParser(description=__doc__)
    parser.add_argument("--odexa-dir", required=True, help="Existing target directory; only .env is read")
    parser.add_argument("--allow-mutation", action="store_true", help="Opt in to stock-changing API tests")
    parser.add_argument("--exclusive-fixtures", action="store_true", help="Confirm dedicated fixture ownership")
    args = parser.parse_args()
    with interrupt_guard():
        return run_existing(args.odexa_dir, version=os.environ.get("ODEXA_VERSION", BASELINE_VERSION),
                            allow_mutation=args.allow_mutation, exclusive=args.exclusive_fixtures)


if __name__ == "__main__":
    sys.exit(main())
