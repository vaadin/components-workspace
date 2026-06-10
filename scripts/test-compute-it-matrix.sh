#!/usr/bin/env bash
# Tests scripts/compute-it-matrix.sh against synthetic flow-components
# fixture trees. Run from workspace root:
#   bash scripts/test-compute-it-matrix.sh
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/compute-it-matrix.sh"
# Use the same bash interpreter that's running this test script so that bash 5
# features (mapfile) work even on macOS where /bin/bash is 3.2.
BASH="${BASH:-bash}"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok: $*"; }

# Build a fake flow-components root: for each "name:count" pair, populate
# <root>/vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/src/test/java/com/pkg
# with `count` synthetic *IT.java files.
make_root() {
  local root
  root=$(mktemp -d)
  for spec in "$@"; do
    local name="${spec%%:*}"
    local n="${spec#*:}"
    local dir="$root/vaadin-$name-flow-parent/vaadin-$name-flow-integration-tests/src/test/java/com/pkg"
    mkdir -p "$dir"
    for ((i=1; i<=n; i++)); do
      : > "$dir/$(printf '%s%dIT.java' "${name//-/}" $i)"
    done
  done
  echo "$root"
}

# Case 1: missing flow-components root → exit non-zero.
if echo "grid" | "$BASH" "$SCRIPT" /tmp/no-such-flow-components >/dev/null 2>&1; then
  fail "missing root should exit non-zero"
fi
pass "missing root exits non-zero"

# Case 2: empty stdin → empty matrix.
root=$(make_root "grid:1")
out=$(printf '' | "$BASH" "$SCRIPT" "$root")
[ "$(echo "$out" | jq -r '.include | length')" = "0" ] \
  || fail "empty stdin: matrix not empty"
rm -rf "$root"
pass "empty stdin emits empty matrix"

# Case 3: unknown overlay name → exit non-zero.
root=$(make_root "grid:1")
if echo "nonexistent" | "$BASH" "$SCRIPT" "$root" >/dev/null 2>&1; then
  fail "unknown overlay should exit non-zero"
fi
rm -rf "$root"
pass "unknown overlay name rejected"

# Case 4: single module → 1 shard with that module path.
root=$(make_root "grid:5")
out=$(echo "grid" | "$BASH" "$SCRIPT" "$root")
[ "$(echo "$out" | jq -r '.include | length')" = "1" ] || fail "1 module != 1 shard"
[ "$(echo "$out" | jq -r '.include[0].modules')" = "vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests" ] \
  || fail "1-module path"
[ "$(echo "$out" | jq -r '.include[0].shard')" = "1/1" ] \
  || fail "1-module shard id"
rm -rf "$root"
pass "1 module -> 1 shard with correct module path"

# Case 4b: newline-separated stdin works the same as space-separated.
root=$(make_root "grid:1" "button:1")
out=$(printf 'grid\nbutton\n' | "$BASH" "$SCRIPT" "$root")
[ "$(echo "$out" | jq -r '.include | length')" = "1" ] \
  || fail "newline-separated stdin: should be 1 shard (got $(echo "$out" | jq -r '.include | length'))"
# Both modules should appear in the single shard, in some order.
all_modules=$(echo "$out" | jq -r '.include[0].modules' | tr ',' '\n' | sort)
expected=$(printf '%s\n' \
  vaadin-button-flow-parent/vaadin-button-flow-integration-tests \
  vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests \
  | sort)
[ "$all_modules" = "$expected" ] || fail "newline-separated stdin: modules mismatch"
rm -rf "$root"
pass "newline-separated stdin handled as whitespace-separated"

# Case 5: module with no src/test/java still counts as 0 and is included.
root=$(mktemp -d)
mkdir -p "$root/vaadin-empty-flow-parent/vaadin-empty-flow-integration-tests"  # no src/test/java
out=$(echo "empty" | "$BASH" "$SCRIPT" "$root")
[ "$(echo "$out" | jq -r '.include | length')" = "1" ] \
  || fail "module without src/test/java should still produce 1 shard"
[ "$(echo "$out" | jq -r '.include[0].modules')" = "vaadin-empty-flow-parent/vaadin-empty-flow-integration-tests" ] \
  || fail "module without src/test/java: wrong path"
rm -rf "$root"
pass "module without src/test/java counted as 0"

# Case 6: LPT distributes by count. 4 modules with counts 30, 20, 10, 5 →
# with TARGET_PER_SHARD=35 → ceil(65/35)=2 shards. Expected LPT result:
# shard 1 gets {30, 5} = 35; shard 2 gets {20, 10} = 30. (Order doesn't matter,
# but every module must appear exactly once across both shards.)
root=$(make_root "big:30" "med:20" "small:10" "tiny:5")
out=$(echo "big med small tiny" | "$BASH" "$SCRIPT" "$root")
n=$(echo "$out" | jq -r '.include | length')
[ "$n" = "2" ] || fail "LPT 65 classes target=35: got $n shards (want 2)"
all_modules=$(echo "$out" | jq -r '.include[].modules' | tr ',' '\n' | sort)
expected=$(printf '%s\n' \
  vaadin-big-flow-parent/vaadin-big-flow-integration-tests \
  vaadin-med-flow-parent/vaadin-med-flow-integration-tests \
  vaadin-small-flow-parent/vaadin-small-flow-integration-tests \
  vaadin-tiny-flow-parent/vaadin-tiny-flow-integration-tests \
  | sort)
[ "$all_modules" = "$expected" ] || fail "LPT did not place every module exactly once"
rm -rf "$root"
pass "LPT 4-module distribution covers every module exactly once"

# Case 7: many small modules cap at MAX_SHARDS=12.
specs=()
for ((i=1; i<=20; i++)); do specs+=("c$i:30"); done
root=$(make_root "${specs[@]}")
names=""
for ((i=1; i<=20; i++)); do names+="c$i "; done
out=$(echo "$names" | "$BASH" "$SCRIPT" "$root")
n=$(echo "$out" | jq -r '.include | length')
[ "$n" = "12" ] || fail "20-module 30-each: got $n shards (want 12)"
all_modules=$(echo "$out" | jq -r '.include[].modules' | tr ',' '\n' | sort)
expected=$(for ((i=1; i<=20; i++)); do
  echo "vaadin-c${i}-flow-parent/vaadin-c${i}-flow-integration-tests"
done | sort)
[ "$all_modules" = "$expected" ] || fail "20-module cap: modules across shards don't match expected set exactly (duplicates or missing entries)"
rm -rf "$root"
pass "20 modules at 30 ITs each -> 12 shards covering all modules"

# Case 8: MAX_SHARDS=4 override.
root=$(make_root "a:30" "b:30" "c:30" "d:30" "e:30" "f:30")
out=$(MAX_SHARDS=4 "$BASH" "$SCRIPT" "$root" <<<"a b c d e f")
n=$(echo "$out" | jq -r '.include | length')
[ "$n" = "4" ] || fail "MAX_SHARDS=4: got $n shards"
rm -rf "$root"
pass "MAX_SHARDS env override respected"

# Case 9: TARGET_PER_SHARD=10 on 3 modules of 10 each -> 3 shards.
root=$(make_root "a:10" "b:10" "c:10")
out=$(TARGET_PER_SHARD=10 "$BASH" "$SCRIPT" "$root" <<<"a b c")
n=$(echo "$out" | jq -r '.include | length')
[ "$n" = "3" ] || fail "TARGET_PER_SHARD=10 on 3x10: got $n shards (want 3)"
rm -rf "$root"
pass "TARGET_PER_SHARD env override respected"

# Case 10: MAX_SHARDS=0 must be rejected.
root=$(make_root "grid:1")
if MAX_SHARDS=0 "$BASH" "$SCRIPT" "$root" <<<"grid" >/dev/null 2>&1; then
  fail "MAX_SHARDS=0 should be rejected"
fi
rm -rf "$root"
pass "MAX_SHARDS=0 rejected"

echo "All tests pass."
