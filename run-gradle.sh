#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="$SCRIPT_DIR/.toolchain/jdk-25/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
exec "$SCRIPT_DIR/gradlew" "$@"
