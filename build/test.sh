#!/usr/bin/env bash
set -euo pipefail

###
# Run all tests in the project.
###

clojure -Adev -m cognitect.test-runner $@
