#!/usr/bin/env bash
# Tests scripts/overlay-component-names.sh against fixture overlay lists.
# Run from workspace root: bash scripts/test-overlay-component-names.sh

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/overlay-component-names.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok: $*"; }

# Fixture overlays.txt with comments and blank lines.
FIXTURE=$(mktemp)
trap 'rm -f "$FIXTURE"' EXIT
cat > "$FIXTURE" <<'EOF'
# comment line
vaadin-button-flow-parent/vaadin-button-flow-integration-tests

vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests
vaadin-combo-box-flow-parent/vaadin-combo-box-flow-integration-tests
vaadin-date-picker-flow-parent/vaadin-date-picker-flow-integration-tests
EOF

# Case 1: default — all four names.
out=$(bash "$SCRIPT" "$FIXTURE")
[ "$out" = "button grid combo-box date-picker" ] || fail "default: got [$out]"
pass "default lists all overlay names"

# Case 2: COMPONENTS filter — narrow to two.
out=$(COMPONENTS="grid date-picker" bash "$SCRIPT" "$FIXTURE")
[ "$out" = "grid date-picker" ] || fail "filter: got [$out]"
pass "COMPONENTS filter narrows the set"

# Case 3: COMPONENTS filter with a name not in overlays — drops it.
out=$(COMPONENTS="grid bogus" bash "$SCRIPT" "$FIXTURE")
[ "$out" = "grid" ] || fail "filter-with-bogus: got [$out]"
pass "COMPONENTS filter drops names not in overlays.txt"

# Case 4: missing source file — exit 1.
if bash "$SCRIPT" /tmp/this-does-not-exist >/dev/null 2>&1; then
  fail "missing source should exit non-zero"
fi
pass "missing source exits non-zero"

echo "All tests pass."
