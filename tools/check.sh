#!/usr/bin/env bash
# The same checks as .github/workflows/check.yml, run here: unit tests, lint
# (with AnkiDroid's fatal checks that block a release build), and both APKs.
#   tools/check.sh            everything
#   tools/check.sh --offline  without Gradle looking at the network
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./gradlew "$@" :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :fakegame:assembleDebug
