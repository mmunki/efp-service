#!/usr/bin/env bash
# ============================================================================
# tests/forensic-test.sh -- host tests for the pure Java forensic parser
# ----------------------------------------------------------------------------
# Builds only src/java/com/factorypop/efp/Forensic.java (no Android imports)
# together with tests/ForensicTest.java and runs it on the local JVM.
#
# Usage:
#   tests/forensic-test.sh
# ============================================================================
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$PROJECT/.build-tests/forensic-test"
JAVA_SRC="$PROJECT/src/java/com/factorypop/efp/Forensic.java"
TEST_SRC="$PROJECT/tests/ForensicTest.java"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "==> javac (pure Java parser)"
javac -source 8 -target 8 -Xlint:-options -d "$OUT" "$JAVA_SRC" "$TEST_SRC"

echo "==> run ForensicTest"
java -cp "$OUT" ForensicTest
