#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ "${1:-}" != "--no-build" ]]; then
  ./gradlew installDist
fi
exec python3 scripts/run.py
