#!/usr/bin/env bash
# Локальный прогон того же ревью, что в GitHub Actions: изменения рабочего дерева относительно ветки.
set -euo pipefail
cd "$(dirname "$0")"
base="${1:-main}"
mkdir -p data
git -C .. -c core.quotePath=false diff --no-color --merge-base "$base" > data/pr.diff
./gradlew -q :reviewer:bootJar
java -jar reviewer/build/libs/reviewer.jar --review.root=.. --review.diff=data/pr.diff --review.output=data/review.md
