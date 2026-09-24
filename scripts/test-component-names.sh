#!/usr/bin/env bash
# Tests scripts/component-names.sh against a fixture overlay tree.
# Run from workspace root: bash scripts/test-component-names.sh

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/component-names.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok: $*"; }

# Fixture overlay directory with four IT modules.
FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT

make_overlay() {
  local name="$1"
  local dir="$FIXTURE/vaadin-${name}-flow-parent/vaadin-${name}-flow-integration-tests"
  mkdir -p "$dir"
  echo '{}' > "$dir/package.json"
}
make_overlay button
make_overlay grid
make_overlay combo-box
make_overlay date-picker

# Case 1: default — all four names, sorted.
out=$(bash "$SCRIPT" "$FIXTURE")
[ "$out" = "button combo-box date-picker grid" ] || fail "default: got [$out]"
pass "default lists all component names, sorted"

# Case 2: COMPONENTS filter — narrow to two.
out=$(COMPONENTS="grid date-picker" bash "$SCRIPT" "$FIXTURE")
[ "$out" = "date-picker grid" ] || fail "filter: got [$out]"
pass "COMPONENTS filter narrows the set"

# Case 3: COMPONENTS filter with a name not in overlays — drops it.
out=$(COMPONENTS="grid bogus" bash "$SCRIPT" "$FIXTURE")
[ "$out" = "grid" ] || fail "filter-with-bogus: got [$out]"
pass "COMPONENTS filter drops names not in component tree"

# Case 4: missing source directory — exit 1.
if bash "$SCRIPT" /tmp/this-does-not-exist-$$ >/dev/null 2>&1; then
  fail "missing source should exit non-zero"
fi
pass "missing source exits non-zero"

# Case 5: empty overlay directory — empty output, exit 0.
EMPTY=$(mktemp -d)
trap 'rm -rf "$FIXTURE" "$EMPTY"' EXIT
out=$(bash "$SCRIPT" "$EMPTY")
[ -z "$out" ] || fail "empty: got [$out]"
pass "empty overlay directory yields empty output"

echo "All tests pass."
