#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
JDK_HOME="${JAVA_HOME:-$MOD_ROOT/.toolchain/jdk-25/Contents/Home}"
OUT_DIR="$MOD_ROOT/src/main/resources/natives/macos-arm64"
mkdir -p "$OUT_DIR"

clang++ -std=c++17 -O3 -fobjc-arc -ObjC++ -arch arm64 -mmacosx-version-min=14.0 \
    -dynamiclib \
    -I"$JDK_HOME/include" \
    -I"$JDK_HOME/include/darwin" \
    -framework Metal \
    -framework Foundation \
    "$SCRIPT_DIR/raindio_metal_rt.mm" \
    -o "$OUT_DIR/libraindio_metal_rt.dylib"

echo "[Raindio] Built native Apple Silicon Metal Hardware RT library -> $OUT_DIR/libraindio_metal_rt.dylib"
