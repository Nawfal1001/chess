#!/bin/sh

# Gradle wrapper bootstrap. The CI uses Gradle 8.10 directly; this launcher
# keeps local invocation consistent when a Gradle installation is available.
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec gradle -p "$SCRIPT_DIR" "$@"
