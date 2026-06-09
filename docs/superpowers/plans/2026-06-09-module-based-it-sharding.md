# Module-Based IT Sharding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the common-WAR sharding in `.github/workflows/validation.yml` with module-based Maven sharding, so each ≤12-shard matrix entry runs `mvn verify -am -pl <module-list>` against a subset of overlay IT modules instead of a `-Dit.test=<class-list>` filter on a single synthetic merged module.

**Architecture:** Drop the `package-war` job entirely; the `install` job's matrix step pipes overlay short names into a rewritten `scripts/compute-it-matrix.sh` that LPT bin-packs modules by `*IT.java` file count and emits `modules` (comma-separated paths) per shard; the `its` job consumes that matrix and runs one Maven reactor invocation per shard with `-am` so cross-module deps resolve. No CI consumer of `flow-components/scripts/mergeITs.js`. Evaluation procedure (see spec §Evaluation) compares the PR-branch dispatch run to the automatic baseline run that uses `main`'s validation.yml against the same SHA.

**Tech Stack:** Bash 5 (scripts), GitHub Actions (workflow YAML), Maven 3 + Failsafe + jetty-maven-plugin (shard execution), `jq` (matrix sanity-checks in tests).

**Spec reference:** `docs/superpowers/specs/2026-06-09-module-based-it-sharding-design.md`. Read it once before starting — the §Verification, §Changes to `compute-it-matrix.sh`, and §Changes to `validation.yml` sections are the source of truth.

---

## Task 1: Rewrite `compute-it-matrix.sh` and its tests

**Files:**
- Modify: `scripts/test-compute-it-matrix.sh` (rewrite — interface changed from "walks merged tree" to "reads stdin")
- Modify: `scripts/compute-it-matrix.sh` (rewrite — LPT bin-packing by per-module IT count)

The new script signature:
- Reads overlay short names (e.g. `grid date-picker`) from stdin as whitespace-separated tokens.
- Positional arg 1: flow-components root directory (default `flow-components`).
- For each name, resolves `<root>/vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/src/test/java/` and counts `*IT.java` files.
- LPT bin-packs into ≤`MAX_SHARDS` (default 12) shards aiming for `TARGET_PER_SHARD` (default 35) classes per shard.
- Emits `{"include":[{"shard":"1/N","modules":"path1,path2,..."}, ...]}` JSON.

### Steps

- [ ] **Step 1.1: Replace the test file with the new test cases.**

Write `scripts/test-compute-it-matrix.sh` with this exact content:

```bash
#!/usr/bin/env bash
# Tests scripts/compute-it-matrix.sh against synthetic flow-components fixture
# trees. Run from workspace root: bash scripts/test-compute-it-matrix.sh
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/compute-it-matrix.sh"

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
if echo "grid" | bash "$SCRIPT" /tmp/no-such-flow-components >/dev/null 2>&1; then
  fail "missing root should exit non-zero"
fi
pass "missing root exits non-zero"

# Case 2: empty stdin → empty matrix.
root=$(make_root "grid:1")
out=$(printf '' | bash "$SCRIPT" "$root")
[ "$(echo "$out" | jq -r '.include | length')" = "0" ] \
  || fail "empty stdin: matrix not empty"
rm -rf "$root"
pass "empty stdin emits empty matrix"

# Case 3: unknown overlay name → exit non-zero.
root=$(make_root "grid:1")
if echo "nonexistent" | bash "$SCRIPT" "$root" >/dev/null 2>&1; then
  fail "unknown overlay should exit non-zero"
fi
rm -rf "$root"
pass "unknown overlay name rejected"

# Case 4: single module → 1 shard with that module path.
root=$(make_root "grid:5")
out=$(echo "grid" | bash "$SCRIPT" "$root")
[ "$(echo "$out" | jq -r '.include | length')" = "1" ] || fail "1 module != 1 shard"
[ "$(echo "$out" | jq -r '.include[0].modules')" = "vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests" ] \
  || fail "1-module path"
[ "$(echo "$out" | jq -r '.include[0].shard')" = "1/1" ] \
  || fail "1-module shard id"
rm -rf "$root"
pass "1 module -> 1 shard with correct module path"

# Case 5: module with no src/test/java still counts as 0 and is included.
root=$(mktemp -d)
mkdir -p "$root/vaadin-empty-flow-parent/vaadin-empty-flow-integration-tests"  # no src/test/java
out=$(echo "empty" | bash "$SCRIPT" "$root")
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
out=$(echo "big med small tiny" | bash "$SCRIPT" "$root")
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
out=$(echo "$names" | bash "$SCRIPT" "$root")
n=$(echo "$out" | jq -r '.include | length')
[ "$n" = "12" ] || fail "20-module 30-each: got $n shards (want 12)"
all_modules=$(echo "$out" | jq -r '.include[].modules' | tr ',' '\n' | sort -u)
total=$(echo "$all_modules" | wc -l | tr -d ' ')
[ "$total" = "20" ] || fail "20-module cap: $total unique modules across shards (want 20)"
rm -rf "$root"
pass "20 modules at 30 ITs each -> 12 shards covering all modules"

# Case 8: MAX_SHARDS=4 override.
root=$(make_root "a:30" "b:30" "c:30" "d:30" "e:30" "f:30")
out=$(MAX_SHARDS=4 bash "$SCRIPT" "$root" <<<"a b c d e f")
n=$(echo "$out" | jq -r '.include | length')
[ "$n" = "4" ] || fail "MAX_SHARDS=4: got $n shards"
rm -rf "$root"
pass "MAX_SHARDS env override respected"

# Case 9: TARGET_PER_SHARD=10 on 3 modules of 10 each -> 3 shards.
root=$(make_root "a:10" "b:10" "c:10")
out=$(TARGET_PER_SHARD=10 bash "$SCRIPT" "$root" <<<"a b c")
n=$(echo "$out" | jq -r '.include | length')
[ "$n" = "3" ] || fail "TARGET_PER_SHARD=10 on 3x10: got $n shards (want 3)"
rm -rf "$root"
pass "TARGET_PER_SHARD env override respected"

# Case 10: MAX_SHARDS=0 must be rejected.
root=$(make_root "grid:1")
if MAX_SHARDS=0 bash "$SCRIPT" "$root" <<<"grid" >/dev/null 2>&1; then
  fail "MAX_SHARDS=0 should be rejected"
fi
rm -rf "$root"
pass "MAX_SHARDS=0 rejected"

echo "All tests pass."
```

- [ ] **Step 1.2: Run tests; expect FAIL on at least the first case.**

```bash
bash scripts/test-compute-it-matrix.sh
```

Expected: `FAIL` (the existing script signature reads a directory positional arg, not stdin, and emits `tests:` keys instead of `modules:`). The exact case that fails first doesn't matter — what matters is that we see failures before changing the implementation.

- [ ] **Step 1.3: Replace `scripts/compute-it-matrix.sh` with the new module-LPT version.**

Write `scripts/compute-it-matrix.sh` with this exact content:

```bash
#!/usr/bin/env bash
# Reads overlay component short names from stdin (whitespace-separated) and
# emits a GH Actions matrix JSON to stdout. Modules are LPT bin-packed by
# *IT.java file count into at most MAX_SHARDS buckets, aiming for
# TARGET_PER_SHARD classes per shard.
#
# Env overrides:
#   MAX_SHARDS        — default 12 (hard cap on parallel shards)
#   TARGET_PER_SHARD  — default 35 (per-shard class count target)
#
# Positional arg: flow-components root (default flow-components). Each input
# name is mapped to <root>/vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests
# and its *IT.java file count is read from src/test/java/ (counts as 0 if the
# directory is absent).

set -euo pipefail

MAX_SHARDS="${MAX_SHARDS:-12}"
TARGET_PER_SHARD="${TARGET_PER_SHARD:-35}"
ROOT="${1:-flow-components}"

if ! [[ "$MAX_SHARDS" =~ ^[1-9][0-9]*$ ]]; then
  echo "::error::MAX_SHARDS must be a positive integer (got: $MAX_SHARDS)" >&2
  exit 1
fi
if ! [[ "$TARGET_PER_SHARD" =~ ^[1-9][0-9]*$ ]]; then
  echo "::error::TARGET_PER_SHARD must be a positive integer (got: $TARGET_PER_SHARD)" >&2
  exit 1
fi

if [ ! -d "$ROOT" ]; then
  echo "::error::flow-components root not found at $ROOT" >&2
  exit 1
fi

# Read overlay short names from stdin as whitespace-separated tokens.
input=$(cat)
read -r -a names <<<"$input"
if [ "${#names[@]}" -eq 0 ]; then
  echo '{"include":[]}'
  exit 0
fi

# Build (count, module-path) pairs.
pairs=()
for n in "${names[@]}"; do
  module="vaadin-${n}-flow-parent/vaadin-${n}-flow-integration-tests"
  abs="$ROOT/$module"
  if [ ! -d "$abs" ]; then
    echo "::error::IT module not found: $abs" >&2
    exit 1
  fi
  if [ -d "$abs/src/test/java" ]; then
    count=$(find "$abs/src/test/java" -name '*IT.java' | wc -l | tr -d ' ')
  else
    count=0
  fi
  pairs+=("$count $module")
done

# Sort descending by count (LPT input order). Ties break alphabetically.
mapfile -t sorted < <(printf '%s\n' "${pairs[@]}" | sort -k1,1nr -k2,2)

total=0
for p in "${sorted[@]}"; do
  c="${p%% *}"
  total=$(( total + c ))
done

n=$(( (total + TARGET_PER_SHARD - 1) / TARGET_PER_SHARD ))
[ "$n" -lt 1 ] && n=1
[ "$n" -gt "$MAX_SHARDS" ] && n=$MAX_SHARDS
[ "$n" -gt "${#sorted[@]}" ] && n="${#sorted[@]}"

declare -a bucket_modules bucket_counts
for ((i=0; i<n; i++)); do
  bucket_modules[$i]=""
  bucket_counts[$i]=0
done

# LPT: place each module into the currently-lightest bucket.
for p in "${sorted[@]}"; do
  c="${p%% *}"
  m="${p#* }"
  lightest=0
  for ((i=1; i<n; i++)); do
    if [ "${bucket_counts[$i]}" -lt "${bucket_counts[$lightest]}" ]; then
      lightest=$i
    fi
  done
  [ -n "${bucket_modules[$lightest]}" ] && bucket_modules[$lightest]+=","
  bucket_modules[$lightest]+="$m"
  bucket_counts[$lightest]=$(( bucket_counts[$lightest] + c ))
done

json='{"include":['
for ((k=0; k<n; k++)); do
  [ $k -gt 0 ] && json+=','
  json+='{"shard":"'$((k+1))'/'$n'","modules":"'${bucket_modules[$k]}'"}'
done
json+=']}'
echo "$json"
```

- [ ] **Step 1.4: Run tests; expect PASS.**

```bash
bash scripts/test-compute-it-matrix.sh
```

Expected: `ok:` lines for every case, ending with `All tests pass.`

- [ ] **Step 1.5: Sanity-check against the real overlay set.**

```bash
bash scripts/overlay-component-names.sh | bash scripts/compute-it-matrix.sh | jq .
```

Expected: valid JSON with an `include` array whose entries each have `shard` (e.g. `"1/N"`) and `modules` (comma-separated `vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests` paths). The shard count should be between 1 and 12.

If this command errors with "IT module not found", the issue is that `overlay-component-names.sh` is emitting a short name that doesn't have a matching IT module under `flow-components/`. Check the overlay set in `flow-components-overlay/` against the flow-components submodule layout before continuing.

- [ ] **Step 1.6: Commit.**

```bash
git add scripts/compute-it-matrix.sh scripts/test-compute-it-matrix.sh
git commit -m "$(cat <<'EOF'
refactor(matrix): LPT-pack overlay modules instead of merged IT classes

compute-it-matrix.sh now reads overlay short names from stdin and emits
{shard, modules} entries built by Longest-Processing-Time bin-packing on
per-module *IT.java counts. Replaces the previous merged-tree walk that
emitted {shard, tests} for the common-WAR sharding path.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Rewrite `.github/workflows/validation.yml`

**Files:**
- Modify: `.github/workflows/validation.yml`

All workflow changes ship as one commit because they are co-dependent: removing `package-war` without updating `its.needs` breaks the workflow, and updating `its` to consume `modules` without changing the matrix step's input emits a malformed matrix.

The current file has six top-level jobs: `install`, `unit`, `wtr`, `package-war`, `its`, `results`. The end state has five: `install`, `unit`, `wtr`, `its`, `results`.

### Steps

- [ ] **Step 2.1: Remove the "Merge overlay ITs into integration-tests/" step from `install`.**

Open `.github/workflows/validation.yml`. Find the step labeled `Merge overlay ITs into integration-tests/` inside the `install` job (currently around lines 80–86) and delete it in its entirety. That step looks like:

```yaml
      - name: Merge overlay ITs into integration-tests/
        env:
          COMPONENTS: ${{ inputs.components }}
        run: |
          names=$(COMPONENTS="$COMPONENTS" bash scripts/overlay-component-names.sh)
          echo "Merging ITs for: $names"
          cd flow-components && node scripts/mergeITs.js $names
```

After deletion the `Workspace install` save-cache step is immediately followed by the `Compute IT matrix` step.

- [ ] **Step 2.2: Update the "Compute IT matrix" step to feed overlay names through stdin.**

Find the `Compute IT matrix` step in `install` (currently around lines 88–97). Replace its body so it derives names from `overlay-component-names.sh` (honouring the `components` input) and pipes them into the new `compute-it-matrix.sh`:

```yaml
      - name: Compute IT matrix
        id: matrix
        env:
          COMPONENTS: ${{ inputs.components }}
        run: |
          names=$(COMPONENTS="$COMPONENTS" bash scripts/overlay-component-names.sh)
          echo "Overlay names: $names"
          matrix=$(echo "$names" | bash scripts/compute-it-matrix.sh)
          echo "$matrix" | jq .
          {
            echo 'value<<EOF'
            echo "$matrix"
            echo 'EOF'
          } >> "$GITHUB_OUTPUT"
```

- [ ] **Step 2.3: Delete the entire `package-war` job.**

Find the block starting with `  package-war:` (currently around line 213) and ending at the blank line before `  its:` (currently around line 306). Delete the whole block. The `its` job's `needs:` will be updated in the next step.

- [ ] **Step 2.4: Update the `its` job — drop `package-war` from `needs`, drop the WAR-cache restore step, and replace the run command.**

Find `  its:` and apply three edits inside that job:

1. Change `needs: [install, package-war]` to `needs: [install]`.

2. Delete the entire `Restore WAR cache` step:

```yaml
      - name: Restore WAR cache
        uses: actions/cache/restore@v5
        with:
          key: ${{ needs.package-war.outputs.war-cache-key }}
          path: |
            flow-components/integration-tests/pom.xml
            flow-components/integration-tests/target
          fail-on-cache-miss: true
```

3. Replace the `Run IT shard` step body. Find:

```yaml
      - name: Run IT shard
        env:
          SHARD_TESTS: ${{ matrix.tests }}
        run: |
          cd flow-components && mvn -pl integration-tests -Drun-it \
            jetty:start-war failsafe:integration-test jetty:stop failsafe:verify \
            -Dvaadin.productionMode \
            -DskipFrontend \
            -Dfailsafe.forkCount=4 \
            -Dcom.vaadin.testbench.Parameters.testsInParallel=2 \
            -Dfailsafe.rerunFailingTestsCount=2 \
            -Dmaven.test.redirectTestOutputToFile=true \
            -Dtest.reuseDriver=true \
            -Dit.test="$SHARD_TESTS" \
            -B -ntp
```

Replace with:

```yaml
      - name: Run IT shard
        env:
          SHARD_MODULES: ${{ matrix.modules }}
        run: |
          cd flow-components && mvn verify -am \
            -pl "$SHARD_MODULES" \
            -Drun-it -Drelease \
            -Dvaadin.productionMode -Dvaadin.force.production.build=true \
            -Dfailsafe.forkCount=4 \
            -Dcom.vaadin.testbench.Parameters.testsInParallel=2 \
            -Dfailsafe.rerunFailingTestsCount=2 \
            -Dmaven.test.redirectTestOutputToFile=true \
            -Dtest.reuseDriver=true \
            -DskipUnitTests \
            -B -ntp
```

Changes vs. the original:
- `SHARD_TESTS` → `SHARD_MODULES` (the matrix key changed).
- `mvn -pl integration-tests jetty:start-war failsafe:integration-test jetty:stop failsafe:verify` → `mvn verify -am -pl "$SHARD_MODULES"`. Each module's POM already binds Jetty start/stop to the `verify` phase.
- `-Dit.test="$SHARD_TESTS"` removed (no per-class filter — every IT class in each selected module runs).
- `-DskipFrontend` removed (modular mode needs the per-module frontend build).
- `-Drelease`, `-Dvaadin.force.production.build=true`, and `-DskipUnitTests` added to match the production-mode build semantics of the previous `package-war` step and to avoid double-running unit tests.

- [ ] **Step 2.5: Update upload paths in `its` to scan per-module `target/` directories.**

Find `Upload failsafe reports` in `its` and change the `path:` from `flow-components/integration-tests/target/failsafe-reports/TEST-*.xml` to `flow-components/**/target/failsafe-reports/TEST-*.xml`:

```yaml
      - name: Upload failsafe reports
        if: always()
        uses: actions/upload-artifact@v6
        with:
          name: failsafe-reports-${{ steps.shardid.outputs.value }}
          path: flow-components/**/target/failsafe-reports/TEST-*.xml
          retention-days: 1
          if-no-files-found: ignore
```

Find `Upload error screenshots` in `its` and change the `path:` from `flow-components/integration-tests/error-screenshots/` to `flow-components/**/error-screenshots/`:

```yaml
      - name: Upload error screenshots
        if: failure()
        uses: actions/upload-artifact@v6
        with:
          name: error-screenshots-${{ steps.shardid.outputs.value }}
          path: flow-components/**/error-screenshots/
          retention-days: 5
          if-no-files-found: ignore
```

- [ ] **Step 2.6: Update `results` job — drop `package-war` from `needs`.**

Find the `results` job's `needs:` line (currently `needs: [install, unit, wtr, package-war, its]`) and change it to:

```yaml
    needs: [install, unit, wtr, its]
```

No other change is required in `results` — the artifact-name patterns (`failsafe-reports-*`, `error-screenshots-*`) match the new uploads identically, and the dorny-reporter `path:` globs scan whatever the download steps put into `failsafe-reports/`.

- [ ] **Step 2.7: Validate the workflow YAML parses.**

```bash
python3 -c "import yaml, sys; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "YAML OK"
```

Expected: `YAML OK`. If you see a YAML parse error, check the indentation around the deleted blocks — empty lines between jobs are fine, but stray two-space indents under a job header that no longer has nested keys will break parsing.

- [ ] **Step 2.8: Sanity-check the final job set.**

```bash
python3 -c "import yaml; d=yaml.safe_load(open('.github/workflows/validation.yml')); print(sorted(d['jobs'].keys()))"
```

Expected: `['install', 'its', 'results', 'unit', 'wtr']` (five jobs, alphabetically). Specifically, `package-war` must not appear.

- [ ] **Step 2.9: Sanity-check the `its` job's needs and matrix wiring.**

```bash
python3 - <<'PY'
import yaml
d = yaml.safe_load(open('.github/workflows/validation.yml'))
its = d['jobs']['its']
print('needs:', its['needs'])
assert its['needs'] == ['install'], f"its.needs should be [install], got {its['needs']}"
strat = its['strategy']['matrix']
assert '${{ fromJson(needs.install.outputs.it-matrix) }}' in strat, f"matrix wiring: {strat}"
print('OK')
PY
```

Expected: `needs: ['install']` and `OK`.

- [ ] **Step 2.10: Commit.**

```bash
git add .github/workflows/validation.yml
git commit -m "$(cat <<'EOF'
refactor(ci): switch IT sharding to per-module Maven reactor

Drop the package-war job and the global mergeITs.js call. Each its shard
now runs `mvn verify -am -pl <modules>` directly against its assigned
overlay IT modules. Upload paths scan per-module target/ trees.

The install job now pipes overlay short names into compute-it-matrix.sh,
which LPT-packs them into <=12 module-keyed shards.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Push the branch and open the evaluation PR

**Files:** none (git/gh operations only).

The PR description carries the evaluation procedure and a placeholder table that the evaluator fills in as runs accumulate.

### Steps

- [ ] **Step 3.1: Check branch and push.**

```bash
git status
git log --oneline -3
```

Expected: clean working tree; the last two commits are the spec, the matrix script + tests, and the workflow changes (or similar — at minimum the two commits this plan produced should be there).

```bash
git push -u origin proto/module-based-sharding
```

Expected: push succeeds. If the remote rejects with "branch already exists", a previous attempt left it there — confirm with `git log origin/proto/module-based-sharding..HEAD` that local has new commits, then push without `-u`.

- [ ] **Step 3.2: Open the PR with the evaluation procedure in the description.**

```bash
gh pr create --base main --head proto/module-based-sharding \
  --title "refactor(ci): module-based IT sharding" \
  --body "$(cat <<'EOF'
## Motivation

Evaluate whether the common-WAR sharding in `validation.yml` still pays for itself now that the npm-workspace rollout has dropped per-module install cost. Replace it with module-based Maven sharding (`mvn verify -am -pl <modules>` per shard) and compare wall-clock against the unchanged common-WAR baseline.

See `docs/superpowers/specs/2026-06-09-module-based-it-sharding-design.md` for the design.

## Changes

- `scripts/compute-it-matrix.sh` rewritten to read overlay short names from stdin and LPT-pack modules into <=12 shards by *IT.java count.
- `scripts/test-compute-it-matrix.sh` rewritten for the new contract (`include[].modules` instead of `include[].tests`).
- `.github/workflows/validation.yml`:
  - `package-war` job and `mergeITs.js` invocation removed.
  - `install`.`Compute IT matrix` pipes overlay names into the rewritten script.
  - `its` runs `mvn verify -am -pl <modules>` per shard; upload paths scan per-module `target/`.
  - `results.needs` and `its.needs` no longer reference `package-war`.

## Evaluation procedure

`pull_request` triggers run the workflow from the **base branch** (`main`), so the automatic checks on this PR continue to use the common-WAR `validation.yml`. To exercise the modular workflow on the same SHA, dispatch the workflow from this PR's branch via the Actions tab (`Run workflow` → branch = `proto/module-based-sharding` → leave inputs empty).

Capture `>= 3 runs` of each (push trivial commits to re-trigger PR runs; dispatch repeatedly for modular runs). Then fill in the table below.

### Wall-clock comparison (fill in before review)

| Variant      | Run #1 | Run #2 | Run #3 | Median | p90 | Cache state |
|--------------|--------|--------|--------|--------|-----|-------------|
| Common-WAR   |        |        |        |        |     |             |
| Modular      |        |        |        |        |     |             |

End-to-end = workflow start to `results` job done.

### Decision

Adopt modular unless its median end-to-end wall-clock exceeds the common-WAR median by more than 10%. Document the decision in one paragraph here before requesting review.

---

Generated with Claude Code
EOF
)"
```

Expected: PR URL printed to stdout. Copy it into the next step.

- [ ] **Step 3.3: Trigger the first modular dispatch.**

From the Actions tab on GitHub (the link is in the PR's "Checks" section), select the `Validation` workflow → `Run workflow` → branch `proto/module-based-sharding` → leave the `components` input blank → `Run workflow`. This kicks off the first modular run on the PR's HEAD SHA.

Alternative via CLI:

```bash
gh workflow run validation.yml --ref proto/module-based-sharding
```

Expected: a new workflow run appears under the PR within ~30s.

- [ ] **Step 3.4: Babysit the first run pair (one push-triggered + one dispatch).**

Watch both runs to green. The common-WAR baseline should pass with whatever main currently produces; the modular run should also pass — if it doesn't, the likely culprits are:

- **`mvn verify` resolving artifacts not in `~/.m2/repository/com/vaadin`**. The install cache only contains `com/vaadin` paths. If a module references something else (e.g. `org.webjars`), the install job needs to widen the cache path. Add the missing path to the install job's cache and rerun.
- **Per-module frontend build failing because the `pnpm` / Vite cache state isn't in the install cache.** The current install cache includes `node_modules`, `web-components/node_modules`, and `web-components/.yarn`. If `mvn verify` hits a missing dep, widen the install cache path.
- **Port 8080 collisions** if Maven's reactor tries to run multiple modules' Jetty servers in parallel. The reactor runs sequentially by default (`-T 1`) so this should not happen; if it does, add `-T 1` explicitly to the `mvn verify` command.
- **`fail-on-cache-miss: true` triggering on a stale cache key** if some unrelated input changed. Re-push to force a fresh install run.

Once both pass at least once, the workflow plumbing is correct and you can proceed to the multi-run capture.

- [ ] **Step 3.5: Collect timing data and fill in the PR table.**

For each completed `Validation` run:

```bash
gh run list --workflow validation.yml --branch proto/module-based-sharding --limit 10
```

For a specific run ID, get per-job timings:

```bash
gh run view <run-id> --json jobs --jq '.jobs[] | {name, status, conclusion, startedAt, completedAt}'
```

End-to-end wall-clock = `results.completedAt - install.startedAt` (or whichever job started first). Record the same for the push-triggered baseline runs (use `--branch proto/module-based-sharding` and filter `event: pull_request` vs `event: workflow_dispatch` to distinguish).

Edit the PR description (via `gh pr edit <number> --body-file -` with the updated markdown piped in, or the GitHub UI) to fill in the table and the decision paragraph.

- [ ] **Step 3.6: Hand off for review.**

Once the PR description has at least three runs of each variant and a decision paragraph, request review. Reviewer's job is to confirm the timings, not to re-run the eval.

---

## Self-Review

This plan was checked against `docs/superpowers/specs/2026-06-09-module-based-it-sharding-design.md`:

- **Spec §Overview & Goals** — Task 2 deletes `package-war` and the global merge step (goals 1, 2). Task 1 implements LPT bin-packing (goal 3). Task 3 documents the evaluation procedure (goal 4).
- **Spec §Evaluation Procedure** — Reproduced verbatim in Task 3's PR description template.
- **Spec §Changes to `.github/workflows/validation.yml`** — Task 2 steps 2.1–2.6 each map to a bullet in this section. Step 2.4 covers the three sub-edits to `its` (drop `package-war` from `needs`, drop WAR-cache restore, replace run command). Step 2.5 covers the upload-path changes.
- **Spec §Changes to `scripts/compute-it-matrix.sh`** — Task 1 step 1.3 contains the full rewritten script as specified.
- **Spec §Verification step 1** — Task 1 step 1.4 runs the test suite which covers every case from the spec (missing root, empty input, missing module, single module, LPT distribution, MAX_SHARDS cap, env overrides, MAX_SHARDS=0 rejection, module without src/test/java).
- **Spec §Verification step 2** — Task 3 steps 3.3–3.4 cover the workflow-behaviour checks (auto common-WAR run, dispatched modular run, scoped `components` input).
- **Spec §Verification step 3** — Task 3 step 3.5 covers the evaluation-data capture.

No placeholders or "implement later" steps. Every code/command block is the exact content to write or run. Type/key consistency: the matrix emits `modules:` in Task 1 and the `its` job consumes `${{ matrix.modules }}` in Task 2 — matched. The `SHARD_MODULES` env var in Task 2 step 2.4 matches the variable used in the `mvn` command.