#!/usr/bin/env bash
# Compiles the production sources together with the concurrency suite and runs it.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=out/tstest
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn -d "$OUT" $(find src test -name '*.java')
exec java -cp "$OUT" tsuite.ThreadSafetyTests "$@"
