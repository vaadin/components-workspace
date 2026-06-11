#!/usr/bin/env bash
# Creates web-components/node_modules/.bin -> ../../node_modules/.bin
# so wtr-utils.js can resolve its hardcoded ./node_modules/.bin/lerna path
# from cwd=web-components/ under workspace hoisting.
# Idempotent: `ln -snf` overwrites/creates as needed.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p web-components/node_modules
ln -snf ../../node_modules/.bin web-components/node_modules/.bin
