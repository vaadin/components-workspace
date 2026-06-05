#!/usr/bin/env bash
# Tests scripts/compute-it-matrix.sh against fixture source trees.
# Run from workspace root: bash scripts/test-compute-it-matrix.sh

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/compute-it-matrix.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok: $*"; }

# Helper: create a fixture tree with N IT classes named pkg.Class<i>IT.
make_fixture() {
  local n=$1
  local dir
  dir=$(mktemp -d)
  for ((i=1; i<=n; i++)); do
    mkdir -p "$dir/com/pkg"
    : > "$dir/com/pkg/Class${i}IT.java"
  done
  echo "$dir"
}

# Case 1: missing dir — exit 1.
if bash "$SCRIPT" /tmp/no-such-dir >/dev/null 2>&1; then
  fail "missing root should exit non-zero"
fi
pass "missing root exits non-zero"

# Case 2: empty dir — empty matrix.
empty=$(mktemp -d)
out=$(bash "$SCRIPT" "$empty")
[ "$(echo "$out" | jq -r '.include | length')" = "0" ] \
  || fail "empty dir: matrix not empty"
rm -rf "$empty"
pass "empty dir emits empty matrix"

# Case 3: 1 IT class — 1 shard.
one=$(make_fixture 1)
out=$(bash "$SCRIPT" "$one")
[ "$(echo "$out" | jq -r '.include | length')" = "1" ] \
  || fail "1 class != 1 shard"
[ "$(echo "$out" | jq -r '.include[0].tests')" = "com.pkg.Class1IT" ] \
  || fail "1-class FQCN"
[ "$(echo "$out" | jq -r '.include[0].shard')" = "1/1" ] \
  || fail "1-class shard id"
rm -rf "$one"
pass "1 class -> 1 shard"

# Case 4: TARGET_PER_SHARD boundary (35) -> 1 shard.
thirtyfive=$(make_fixture 35)
out=$(bash "$SCRIPT" "$thirtyfive")
[ "$(echo "$out" | jq -r '.include | length')" = "1" ] \
  || fail "35 classes should fit in 1 shard"
rm -rf "$thirtyfive"
pass "35 classes -> 1 shard"

# Case 5: 36 classes -> 2 shards.
thirtysix=$(make_fixture 36)
out=$(bash "$SCRIPT" "$thirtysix")
[ "$(echo "$out" | jq -r '.include | length')" = "2" ] \
  || fail "36 classes != 2 shards"
rm -rf "$thirtysix"
pass "36 classes -> 2 shards"

# Case 6: 500 classes -> capped at 12 shards.
fivehundred=$(make_fixture 500)
out=$(bash "$SCRIPT" "$fivehundred")
n=$(echo "$out" | jq -r '.include | length')
[ "$n" = "12" ] || fail "500 classes capped: got $n shards (want 12)"
# Every class must appear exactly once across all shards.
total=$(echo "$out" | jq -r '[.include[].tests | split(",") | length] | add')
[ "$total" = "500" ] || fail "500 classes: $total covered"
rm -rf "$fivehundred"
pass "500 classes -> 12 shards covering all classes"

# Case 7: MAX_SHARDS=4 override.
out=$(MAX_SHARDS=4 bash "$SCRIPT" "$(make_fixture 500)")
n=$(echo "$out" | jq -r '.include | length')
[ "$n" = "4" ] || fail "MAX_SHARDS=4 override: got $n shards"
pass "MAX_SHARDS env override respected"

echo "All tests pass."
