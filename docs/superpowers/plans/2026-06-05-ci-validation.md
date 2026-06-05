# CI Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a GitHub Actions workflow at the workspace root that validates the npm-workspace overlay approach end-to-end against flow-components' Selenium ITs, sharded across at most 12 parallel jobs, with `npm install` and `mvn install` artifacts shared via `actions/cache` between every downstream job.

**Architecture:** Six jobs — `install`, `unit`, `wtr`, `package-war`, `its`, `results`. `install` does `./gradlew install` once and emits both a cache key and a matrix JSON. `unit`, `wtr`, and `package-war` run in parallel after `install`. `its` is a matrix job that fans out to ≤12 shards after `package-war`. `results` runs after every leaf, aggregates reports via dorny/test-reporter, and fails the workflow if any check failed. IT classes are merged into `flow-components/integration-tests/` by `mergeITs.js` (overlay-scoped), then sharded by class count.

**Tech Stack:**
- GitHub Actions (`actions/checkout@v6`, `actions/setup-java@v5`, `actions/setup-node@v6`, `actions/cache@v5`, `actions/upload-artifact@v6`, `actions/download-artifact@v8`, `dorny/test-reporter`)
- Bash (matrix-compute + helper scripts)
- `jq` (matrix JSON validation and pretty-printing in CI)
- Existing workspace Gradle build (`./gradlew install`)
- Existing `flow-components/scripts/mergeITs.js` and `flow-components/scripts/wtr.js`

**Reference spec:** `docs/superpowers/specs/2026-06-04-ci-validation-design.md`

---

## Pre-flight check

Run these from the workspace root before starting:

```bash
gh auth status                                              # SSH + repo scope
gh repo view vaadin/components-workspace --json visibility  # PUBLIC
ls flow-components/scripts/mergeITs.js flow-components/scripts/wtr.js
test -x scripts/sync-flow-overlays.sh && echo "sync script ok"
which jq                                                    # jq must be installed locally
```

All five must succeed before proceeding. `jq` is needed for local script testing; the runner image already ships it.

---

### Task 1: Add `scripts/overlay-component-names.sh` with tests

**Files:**
- Create: `scripts/overlay-component-names.sh`
- Create: `scripts/test-overlay-component-names.sh`

- [ ] **Step 1: Write the test harness first**

Create `scripts/test-overlay-component-names.sh` with mode `0755`:

```bash
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
```

- [ ] **Step 2: Run the test and confirm it fails (script does not exist yet)**

```bash
bash scripts/test-overlay-component-names.sh
```

Expected: error like `bash: scripts/overlay-component-names.sh: No such file or directory` or similar non-zero exit.

- [ ] **Step 3: Write `scripts/overlay-component-names.sh`**

Create with mode `0755`:

```bash
#!/usr/bin/env bash
# Emits a space-separated list of overlay short component names to stdout.
#
# Env overrides:
#   COMPONENTS  — space-separated short names to keep (e.g. "grid date-picker")
#
# Reads flow-components-overlay/overlays.txt by default; first positional
# argument overrides the source. Entries starting with # and blank lines are
# ignored.

set -euo pipefail

COMPONENTS="${COMPONENTS:-}"
SOURCE="${1:-flow-components-overlay/overlays.txt}"

if [ ! -f "$SOURCE" ]; then
  echo "::error::Overlay list not found at $SOURCE" >&2
  exit 1
fi

all_names=$(grep -vE '^[[:space:]]*(#|$)' "$SOURCE" \
  | sed 's,.*vaadin-\(.*\)-flow-integration-tests$,\1,')

if [ -z "$COMPONENTS" ]; then
  echo $all_names
  exit 0
fi

out=""
for n in $all_names; do
  for want in $COMPONENTS; do
    if [ "$n" = "$want" ]; then
      out="$out $n"
      break
    fi
  done
done
echo ${out# }
```

- [ ] **Step 4: Re-run the test and confirm it passes**

```bash
chmod +x scripts/overlay-component-names.sh scripts/test-overlay-component-names.sh
bash scripts/test-overlay-component-names.sh
```

Expected output:
```
ok: default lists all overlay names
ok: COMPONENTS filter narrows the set
ok: COMPONENTS filter drops names not in overlays.txt
ok: missing source exits non-zero
All tests pass.
```

- [ ] **Step 5: Commit**

```bash
git add scripts/overlay-component-names.sh scripts/test-overlay-component-names.sh
git commit -m "feat: add overlay-component-names.sh with tests"
```

---

### Task 2: Add `scripts/compute-it-matrix.sh` with tests

**Files:**
- Create: `scripts/compute-it-matrix.sh`
- Create: `scripts/test-compute-it-matrix.sh`

- [ ] **Step 1: Write the test harness first**

Create `scripts/test-compute-it-matrix.sh` with mode `0755`:

```bash
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
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
bash scripts/test-compute-it-matrix.sh
```

Expected: failure on the very first case because the script doesn't exist.

- [ ] **Step 3: Write `scripts/compute-it-matrix.sh`**

Create with mode `0755`:

```bash
#!/usr/bin/env bash
# Reads a directory tree of *IT.java files and emits a GH Actions matrix
# JSON to stdout. Round-robin distributes IT classes across at most
# MAX_SHARDS buckets, aiming for TARGET_PER_SHARD classes per shard.
#
# Env overrides:
#   MAX_SHARDS        — default 12 (hard cap on parallel shards)
#   TARGET_PER_SHARD  — default 35 (per-shard class count target)
#
# Positional arg: root directory to scan (default
# flow-components/integration-tests/src/test/java). The script assumes
# mergeITs.js has already populated that directory when invoked from CI.

set -euo pipefail

MAX_SHARDS="${MAX_SHARDS:-12}"
TARGET_PER_SHARD="${TARGET_PER_SHARD:-35}"
ROOT="${1:-flow-components/integration-tests/src/test/java}"

if [ ! -d "$ROOT" ]; then
  echo "::error::Merged integration-tests source tree not found at $ROOT (did mergeITs.js run?)" >&2
  exit 1
fi

mapfile -t its < <(
  find "$ROOT" -name '*IT.java' -printf '%P\n' \
    | sed -e 's|/|.|g' -e 's|\.java$||' \
    | sort
)
count=${#its[@]}
if [ "$count" -eq 0 ]; then
  echo '{"include":[]}'
  exit 0
fi

n=$(( (count + TARGET_PER_SHARD - 1) / TARGET_PER_SHARD ))
[ "$n" -lt 1 ] && n=1
[ "$n" -gt "$MAX_SHARDS" ] && n=$MAX_SHARDS
[ "$n" -gt "$count" ] && n=$count

declare -a buckets
for ((i=1; i<=n; i++)); do buckets[$i]=""; done
i=1
for t in "${its[@]}"; do
  [ -n "${buckets[$i]}" ] && buckets[$i]+=","
  buckets[$i]+="$t"
  i=$((i+1))
  [ $i -gt $n ] && i=1
done

json='{"include":['
for ((k=1; k<=n; k++)); do
  [ $k -gt 1 ] && json+=','
  json+='{"shard":"'$k'/'$n'","tests":"'${buckets[$k]}'"}'
done
json+=']}'
echo "$json"
```

- [ ] **Step 4: Re-run the test and confirm it passes**

```bash
chmod +x scripts/compute-it-matrix.sh scripts/test-compute-it-matrix.sh
bash scripts/test-compute-it-matrix.sh
```

Expected output:
```
ok: missing root exits non-zero
ok: empty dir emits empty matrix
ok: 1 class -> 1 shard
ok: 35 classes -> 1 shard
ok: 36 classes -> 2 shards
ok: 500 classes -> 12 shards covering all classes
ok: MAX_SHARDS env override respected
All tests pass.
```

- [ ] **Step 5: Commit**

```bash
git add scripts/compute-it-matrix.sh scripts/test-compute-it-matrix.sh
git commit -m "feat: add compute-it-matrix.sh with tests"
```

---

### Task 3: Scaffold `.github/workflows/validation.yml` with empty jobs

**Files:**
- Create: `.github/workflows/validation.yml`

This task lands the workflow's outer shell (name, triggers, concurrency, six placeholder jobs with correct `needs:` edges) so subsequent tasks can fill in one job body at a time. Each placeholder job just `echo`s its name and exits 0.

- [ ] **Step 1: Create the directory and the workflow file**

```bash
mkdir -p .github/workflows
```

Then create `.github/workflows/validation.yml` with this exact content:

```yaml
name: Validation

on:
  pull_request:
    branches: [main]
  workflow_dispatch:
    inputs:
      components:
        description: 'Space-separated names (e.g. "grid combo-box"), empty = all overlay modules'
        required: false
        default: ''
      debug:
        description: 'Verbose Maven output (drop -q)'
        type: boolean
        default: false

concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  install:
    name: Install
    runs-on: ubuntu-latest
    timeout-minutes: 30
    outputs:
      cache-key: ${{ steps.skeleton.outputs.cache-key }}
      it-matrix: ${{ steps.skeleton.outputs.it-matrix }}
    steps:
      - name: Placeholder
        id: skeleton
        run: |
          echo "install placeholder"
          echo "cache-key=placeholder" >> "$GITHUB_OUTPUT"
          echo 'it-matrix={"include":[]}' >> "$GITHUB_OUTPUT"

  unit:
    name: Unit Tests
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - run: echo "unit placeholder"

  wtr:
    name: WTR Tests
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - run: echo "wtr placeholder"

  package-war:
    name: Package WAR
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 30
    outputs:
      war-cache-key: ${{ steps.skeleton.outputs.war-cache-key }}
    steps:
      - name: Placeholder
        id: skeleton
        run: |
          echo "package-war placeholder"
          echo "war-cache-key=placeholder" >> "$GITHUB_OUTPUT"

  its:
    name: IT ${{ matrix.shard }}
    needs: [install, package-war]
    if: needs.install.outputs.it-matrix != '' && fromJson(needs.install.outputs.it-matrix).include[0] != null
    runs-on: ubuntu-latest
    timeout-minutes: 90
    strategy:
      fail-fast: false
      matrix: ${{ fromJson(needs.install.outputs.it-matrix) }}
    steps:
      - run: echo "its placeholder shard=${{ matrix.shard }}"

  results:
    name: Collect results
    needs: [install, unit, wtr, package-war, its]
    if: always() && needs.install.result == 'success'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      checks: write
      actions: write
    steps:
      - run: echo "results placeholder"
```

- [ ] **Step 2: Verify YAML parses**

```bash
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/validation.yml"))' && echo "YAML ok"
```

Expected: `YAML ok`. If `python3` is unavailable, run `actionlint .github/workflows/validation.yml` (if installed) or use any other YAML validator.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "feat: scaffold validation workflow with six placeholder jobs"
```

---

### Task 4: Implement the `install` job

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Replace the `install:` job body**

Find the existing `install:` block (lines starting at `  install:` and ending before `  unit:`) and replace it with this:

```yaml
  install:
    name: Install
    runs-on: ubuntu-latest
    timeout-minutes: 30
    outputs:
      cache-key: ${{ steps.key.outputs.value }}
      it-matrix: ${{ steps.matrix.outputs.value }}
    steps:
      - uses: actions/checkout@v6
        with:
          submodules: recursive
          fetch-depth: 1

      - name: Setup JDK 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: Setup Node
        uses: actions/setup-node@v6
        with:
          node-version: '24'

      - name: Compute cache key
        id: key
        env:
          KEY: ${{ runner.os }}-workspace-install-${{ hashFiles('package.json', 'package-lock.json', 'flow-components-overlay/**', 'web-components/yarn.lock', 'gradle/**', 'build.gradle.kts', 'settings.gradle.kts') }}-${{ github.sha }}
        run: echo "value=$KEY" >> "$GITHUB_OUTPUT"

      - name: Restore install cache (skip work if hit)
        id: cache
        uses: actions/cache/restore@v5
        with:
          key: ${{ steps.key.outputs.value }}
          path: |
            ~/.m2/repository/com/vaadin
            node_modules
            web-components/node_modules
            web-components/.yarn
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated

      - name: Workspace install
        if: steps.cache.outputs.cache-hit != 'true'
        run: ./gradlew install --no-daemon

      - name: Save install cache
        if: steps.cache.outputs.cache-hit != 'true'
        uses: actions/cache/save@v5
        with:
          key: ${{ steps.key.outputs.value }}
          path: |
            ~/.m2/repository/com/vaadin
            node_modules
            web-components/node_modules
            web-components/.yarn
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated

      - name: Merge overlay ITs into integration-tests/
        env:
          COMPONENTS: ${{ inputs.components }}
        run: |
          names=$(COMPONENTS="$COMPONENTS" bash scripts/overlay-component-names.sh)
          echo "Merging ITs for: $names"
          cd flow-components && node scripts/mergeITs.js $names

      - name: Compute IT matrix
        id: matrix
        run: |
          matrix=$(bash scripts/compute-it-matrix.sh)
          echo "$matrix" | jq .
          {
            echo 'value<<EOF'
            echo "$matrix"
            echo 'EOF'
          } >> "$GITHUB_OUTPUT"
```

- [ ] **Step 2: Verify YAML still parses**

```bash
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/validation.yml"))' && echo "YAML ok"
```

Expected: `YAML ok`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "feat: implement install job with cache, mergeITs, and matrix output"
```

---

### Task 5: Implement the `unit` job

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Replace the `unit:` job body**

```yaml
  unit:
    name: Unit Tests
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v6
        with:
          submodules: recursive
          fetch-depth: 1

      - name: Setup JDK 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: Setup Node
        uses: actions/setup-node@v6
        with:
          node-version: '24'

      - name: Restore install cache
        uses: actions/cache/restore@v5
        with:
          key: ${{ needs.install.outputs.cache-key }}
          path: |
            ~/.m2/repository/com/vaadin
            node_modules
            web-components/node_modules
            web-components/.yarn
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
          fail-on-cache-miss: true

      - name: Sync overlay symlinks
        run: bash scripts/sync-flow-overlays.sh

      - name: Run unit tests
        run: |
          cd flow-components && mvn test -Drelease -T 4 \
            -Dsurefire.parallel=classes -Dsurefire.threadCount=2 \
            -B -ntp

      - name: Upload unit test reports
        if: always()
        uses: actions/upload-artifact@v6
        with:
          name: surefire-reports
          path: flow-components/**/target/surefire-reports/TEST-*.xml
          retention-days: 1
          if-no-files-found: ignore
```

Node 24 is required so the `vaadin-charts-flow-svg-generator` module's Node-driven tests can run as part of `mvn test`. Removing the `-DskipSvgChartsBuild` flag lets those tests run with the rest of the suite.

- [ ] **Step 2: Verify YAML parses**

```bash
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/validation.yml"))' && echo "YAML ok"
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "feat: implement unit job"
```

---

### Task 6: Implement the `wtr` job

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Replace the `wtr:` job body**

```yaml
  wtr:
    name: WTR Tests
    if: false
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 30
    env:
      TB_LICENSE: ${{ secrets.TB_LICENSE }}
    steps:
      - uses: actions/checkout@v6
        with:
          submodules: recursive
          fetch-depth: 1

      - name: Setup JDK 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: Setup Node
        uses: actions/setup-node@v6
        with:
          node-version: '24'

      - name: Restore install cache
        uses: actions/cache/restore@v5
        with:
          key: ${{ needs.install.outputs.cache-key }}
          path: |
            ~/.m2/repository/com/vaadin
            node_modules
            web-components/node_modules
            web-components/.yarn
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
          fail-on-cache-miss: true

      - name: Sync overlay symlinks
        run: bash scripts/sync-flow-overlays.sh

      - name: Install TestBench license
        if: env.TB_LICENSE != ''
        run: |
          mkdir -p ~/.vaadin
          user="${TB_LICENSE%%/*}"
          key="${TB_LICENSE#*/}"
          echo "{\"username\":\"${user}\",\"proKey\":\"${key}\"}" > ~/.vaadin/proKey

      - name: Run WTR tests
        run: cd flow-components && node scripts/wtr.js

      - name: Upload WTR reports
        if: always()
        uses: actions/upload-artifact@v6
        with:
          name: wtr-reports
          path: flow-components/**/wtr-results.xml
          retention-days: 1
          if-no-files-found: ignore
```

The job is gated behind `if: false` while a license/setup issue is being triaged. The body is left wired up — JDK 21, Node 24, TestBench license — so re-enabling is a one-line change (remove the `if: false`).

- [ ] **Step 2: Verify YAML parses**

```bash
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/validation.yml"))' && echo "YAML ok"
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "feat: implement wtr job"
```

---

### Task 7: Implement the `package-war` job

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Replace the `package-war:` job body**

```yaml
  package-war:
    name: Package WAR
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 30
    outputs:
      war-cache-key: ${{ steps.warkey.outputs.value }}
    steps:
      - uses: actions/checkout@v6
        with:
          submodules: recursive
          fetch-depth: 1

      - name: Setup JDK 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: Setup Node
        uses: actions/setup-node@v6
        with:
          node-version: '24'

      - name: Restore install cache
        uses: actions/cache/restore@v5
        with:
          key: ${{ needs.install.outputs.cache-key }}
          path: |
            ~/.m2/repository/com/vaadin
            node_modules
            web-components/node_modules
            web-components/.yarn
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
          fail-on-cache-miss: true

      - name: Sync overlay symlinks
        run: bash scripts/sync-flow-overlays.sh

      - name: Install TestBench license
        if: env.TB_LICENSE != ''
        env:
          TB_LICENSE: ${{ secrets.TB_LICENSE }}
        run: |
          mkdir -p ~/.vaadin
          user="${TB_LICENSE%%/*}"
          key="${TB_LICENSE#*/}"
          echo "{\"username\":\"${user}\",\"proKey\":\"${key}\"}" > ~/.vaadin/proKey

      - name: Merge overlay ITs into integration-tests/
        env:
          COMPONENTS: ${{ inputs.components }}
        run: |
          names=$(COMPONENTS="$COMPONENTS" bash scripts/overlay-component-names.sh)
          cd flow-components && node scripts/mergeITs.js $names

      - name: Compute WAR cache key
        id: warkey
        env:
          KEY: ${{ runner.os }}-war-${{ hashFiles('flow-components/**/pom.xml', 'flow-components/integration-tests/src/test/java/**') }}-${{ github.sha }}
        run: echo "value=$KEY" >> "$GITHUB_OUTPUT"

      - name: Restore WAR cache
        id: warcache
        uses: actions/cache/restore@v5
        with:
          key: ${{ steps.warkey.outputs.value }}
          path: |
            flow-components/integration-tests/pom.xml
            flow-components/integration-tests/target

      - name: Package integration-tests WAR
        if: steps.warcache.outputs.cache-hit != 'true'
        run: |
          cd flow-components && mvn package -pl integration-tests \
            -Dvaadin.pnpm.enable -Drun-it -Drelease \
            -Dvaadin.productionMode -Dvaadin.force.production.build=true \
            -Dmaven.test.skip=true -DskipJetty -B -ntp

      - name: Compile IT test sources
        if: steps.warcache.outputs.cache-hit != 'true'
        run: |
          cd flow-components && mvn test-compile -pl integration-tests \
            -Drun-it -DskipFrontend -DskipJetty -DskipUnitTests -B -ntp

      - name: Save WAR cache
        if: steps.warcache.outputs.cache-hit != 'true'
        uses: actions/cache/save@v5
        with:
          key: ${{ steps.warkey.outputs.value }}
          path: |
            flow-components/integration-tests/pom.xml
            flow-components/integration-tests/target
```

- [ ] **Step 2: Verify YAML parses**

```bash
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/validation.yml"))' && echo "YAML ok"
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "feat: implement package-war job with merged-WAR cache"
```

---

### Task 8: Implement the `its` matrix job

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Replace the `its:` job body**

```yaml
  its:
    name: IT ${{ matrix.shard }}
    needs: [install, package-war]
    if: needs.install.outputs.it-matrix != '' && fromJson(needs.install.outputs.it-matrix).include[0] != null
    runs-on: ubuntu-latest
    timeout-minutes: 90
    strategy:
      fail-fast: false
      matrix: ${{ fromJson(needs.install.outputs.it-matrix) }}
    steps:
      - uses: actions/checkout@v6
        with:
          submodules: recursive
          fetch-depth: 1

      - name: Setup JDK 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: Restore install cache
        uses: actions/cache/restore@v5
        with:
          key: ${{ needs.install.outputs.cache-key }}
          path: |
            ~/.m2/repository/com/vaadin
            node_modules
            web-components/node_modules
            web-components/.yarn
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
          fail-on-cache-miss: true

      - name: Restore WAR cache
        uses: actions/cache/restore@v5
        with:
          key: ${{ needs.package-war.outputs.war-cache-key }}
          path: |
            flow-components/integration-tests/pom.xml
            flow-components/integration-tests/target
          fail-on-cache-miss: true

      - name: Sync overlay symlinks
        run: bash scripts/sync-flow-overlays.sh

      - name: Install TestBench license
        if: env.TB_LICENSE != ''
        env:
          TB_LICENSE: ${{ secrets.TB_LICENSE }}
        run: |
          mkdir -p ~/.vaadin
          user="${TB_LICENSE%%/*}"
          key="${TB_LICENSE#*/}"
          echo "{\"username\":\"${user}\",\"proKey\":\"${key}\"}" > ~/.vaadin/proKey

      - name: Compute artifact shard id
        id: shardid
        env:
          SHARD: ${{ matrix.shard }}
        run: |
          value="${SHARD//\//-}"
          echo "value=$value" >> "$GITHUB_OUTPUT"

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

      - name: Upload failsafe reports
        if: always()
        uses: actions/upload-artifact@v6
        with:
          name: failsafe-reports-${{ steps.shardid.outputs.value }}
          path: flow-components/integration-tests/target/failsafe-reports/TEST-*.xml
          retention-days: 1
          if-no-files-found: ignore

      - name: Upload error screenshots
        if: failure()
        uses: actions/upload-artifact@v6
        with:
          name: error-screenshots-${{ steps.shardid.outputs.value }}
          path: flow-components/integration-tests/error-screenshots/
          retention-days: 5
          if-no-files-found: ignore
```

The `Compute artifact shard id` step rewrites the slash in `matrix.shard` (e.g. `3/12`) to a hyphen (`3-12`), because GitHub artifact names reject slashes.

- [ ] **Step 2: Verify YAML parses**

```bash
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/validation.yml"))' && echo "YAML ok"
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "feat: implement IT shard matrix job"
```

---

### Task 9: Implement the `results` aggregator job

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Replace the `results:` job body**

```yaml
  results:
    name: Collect results
    needs: [install, unit, wtr, package-war, its]
    if: always() && needs.install.result == 'success'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      checks: write
      actions: write
    steps:
      - uses: actions/checkout@v6
        with:
          fetch-depth: 1

      - uses: actions/download-artifact@v8
        continue-on-error: true
        with:
          name: surefire-reports
          path: surefire-reports

      - uses: actions/download-artifact@v8
        continue-on-error: true
        with:
          name: wtr-reports
          path: wtr-reports

      - uses: actions/download-artifact@v8
        continue-on-error: true
        with:
          pattern: failsafe-reports-*
          merge-multiple: true
          path: failsafe-reports

      - uses: actions/download-artifact@v8
        continue-on-error: true
        with:
          pattern: error-screenshots-*
          merge-multiple: true
          path: error-screenshots

      - name: Prune passing reports on red builds
        run: |
          has_failures=false
          for dir in surefire-reports wtr-reports failsafe-reports; do
            [ -d "$dir" ] || continue
            if find "$dir" -name '*.xml' | xargs grep -lE 'failures="[1-9]|errors="[1-9]' 2>/dev/null | grep -q .; then
              has_failures=true
              break
            fi
          done
          echo "has_failures=$has_failures" >> "$GITHUB_ENV"
          if [ "$has_failures" = "true" ]; then
            for dir in surefire-reports wtr-reports failsafe-reports; do
              [ -d "$dir" ] || continue
              find "$dir" -name '*.xml' | while read -r f; do
                grep -qE 'failures="[1-9]|errors="[1-9]' "$f" || rm -f "$f"
              done
            done
          fi

      - name: Publish Unit Test results
        id: unit-dorny
        if: hashFiles('surefire-reports/**/TEST-*.xml') != ''
        uses: dorny/test-reporter@a43b3a5f7366b97d083190328d2c652e1a8b6aa2
        with:
          name: Unit Tests
          path: surefire-reports/**/TEST-*.xml
          reporter: java-junit
          list-tests: ${{ env.has_failures == 'true' && 'failed' || 'all' }}
          fail-on-error: 'false'

      - name: Publish WTR results
        id: wtr-dorny
        if: hashFiles('wtr-reports/**/wtr-results.xml') != ''
        uses: dorny/test-reporter@a43b3a5f7366b97d083190328d2c652e1a8b6aa2
        with:
          name: WTR Tests
          path: wtr-reports/**/wtr-results.xml
          reporter: java-junit
          list-tests: ${{ env.has_failures == 'true' && 'failed' || 'all' }}
          fail-on-error: 'false'

      - name: Publish IT results
        id: it-dorny
        if: hashFiles('failsafe-reports/TEST-*.xml') != ''
        uses: dorny/test-reporter@a43b3a5f7366b97d083190328d2c652e1a8b6aa2
        with:
          name: Integration Tests
          path: failsafe-reports/TEST-*.xml
          reporter: java-junit
          list-tests: ${{ env.has_failures == 'true' && 'failed' || 'all' }}
          fail-on-error: 'false'

      - name: Upload merged error screenshots
        if: needs.its.result != 'success'
        uses: actions/upload-artifact@v6
        with:
          name: error-screenshots
          path: error-screenshots/
          retention-days: 5
          if-no-files-found: ignore

      - name: Delete intermediate artifacts
        if: always()
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
          RUN_ID: ${{ github.run_id }}
        run: |
          gh api --paginate "repos/$GH_REPO/actions/runs/$RUN_ID/artifacts" \
            --jq '.artifacts[] | select(
              (.name | startswith("error-screenshots-")) or
              (.name | startswith("failsafe-reports-"))
            ) | "\(.id) \(.name)"' \
          | while read -r id name; do
              gh api -X DELETE "repos/$GH_REPO/actions/artifacts/$id" || true
            done

      - name: Fail if any test failed
        if: always()
        run: |
          failed=false
          [[ "${{ steps.unit-dorny.outputs.conclusion }}" == "failure" ]] && failed=true
          [[ "${{ steps.wtr-dorny.outputs.conclusion }}"  == "failure" ]] && failed=true
          [[ "${{ steps.it-dorny.outputs.conclusion }}"   == "failure" ]] && failed=true
          [[ "$failed" == "true" ]] && exit 1 || exit 0
```

- [ ] **Step 2: Verify YAML parses**

```bash
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/validation.yml"))' && echo "YAML ok"
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "feat: implement results aggregator with dorny reporting"
```

---

### Task 10: Configure the `TB_LICENSE` secret

**Files:** none (out-of-band repo configuration)

This step adds the TestBench license to the `vaadin/components-workspace` repository as a GitHub Actions secret. The license is held by Vaadin engineers — ask the user for the value (format: `username/proKey`) before running the commands.

- [ ] **Step 1: Confirm the secret value with the user**

Do not paste the actual license into the plan or any committed file. Receive it from the user via a private channel.

- [ ] **Step 2: Set the secret via gh CLI**

```bash
# Replace REDACTED with the real "username/proKey" value.
gh secret set TB_LICENSE \
  --repo vaadin/components-workspace \
  --body 'REDACTED'
```

- [ ] **Step 3: Verify the secret is set**

```bash
gh secret list --repo vaadin/components-workspace | grep TB_LICENSE
```

Expected: a line showing `TB_LICENSE` with a recent `Updated` timestamp. The actual value is not displayed (GitHub never echoes secret values).

This task has no commit; it changes only repository configuration.

---

### Task 11: Push to a test branch and verify the workflow end-to-end

**Files:** none (verification only)

This is the integration-test step. The workflow only proves itself when running on GitHub-hosted runners with the real submodule state, TestBench license, and toolchain.

- [ ] **Step 1: Push the current branch to origin**

```bash
git push -u origin HEAD
```

- [ ] **Step 2: Open a draft PR against `main`**

```bash
gh pr create --draft \
  --title "ci: add validation workflow" \
  --body "Tracks the CI rollout per docs/superpowers/specs/2026-06-04-ci-validation-design.md and docs/superpowers/plans/2026-06-05-ci-validation.md."
```

Save the resulting PR URL for reference.

- [ ] **Step 3: Watch the workflow run**

```bash
gh run watch --exit-status \
  $(gh run list --workflow=validation.yml --branch=$(git branch --show-current) --limit 1 --json databaseId --jq '.[0].databaseId')
```

Expected outcomes:
- `install` finishes in ≤25 min on first run (cold cache), ≤2 min on second run (warm cache).
- `unit`, `wtr`, and `package-war` start in parallel after `install`.
- `its` produces between 1 and 12 shards depending on overlay IT count; with the current 4 overlays it should be 1 shard if total IT count is ≤35, otherwise 2.
- `results` runs after all leaves and either passes (green checks on PR) or fails with a dorny report attached.

- [ ] **Step 4: Triage failures**

For any failed step, retrieve the logs:

```bash
gh run view --log-failed
```

Typical issues to expect on first run:
- TB_LICENSE missing → the IT shard logs a license warning; re-check Task 10.
- `mergeITs.js` requires a node arg shape we didn't expect → adjust the install/package-war `Merge overlay ITs` step.
- WAR cache miss on a second push that touched only the overlay → expected; package-war will rebuild.
- Network flakes pulling Maven artifacts → re-run; the next run hits the install cache.

Iterate on the YAML, push fixes, and re-watch until the run is green.

- [ ] **Step 5: Mark PR ready and merge**

Once the workflow has run green at least once, mark the PR ready:

```bash
gh pr ready
```

Merge via the GitHub UI (preserves the dorny check on `main` history).

---

### Task 12: Update README and configure branch protection

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Append a CI section to `README.md`**

Insert this section before the `## Future Plans` heading:

```markdown
## Continuous Integration

The workspace runs `./.github/workflows/validation.yml` on every pull request
to `main`. The workflow installs the workspace via `./gradlew install`, then
runs flow-components unit tests, WTR tests, and Selenium ITs across up to 12
parallel shards using the npm-workspace overlay path.

The IT shard count grows as `flow-components-overlay/overlays.txt` fills out,
capped at 12 parallel jobs. See
`docs/superpowers/specs/2026-06-04-ci-validation-design.md` for the design.

Manual full-suite runs are available via the **Actions** tab (workflow:
*Validation*, button: *Run workflow*). The `components` input filters the IT
matrix to a subset of overlay modules.
```

- [ ] **Step 2: Configure branch protection (manual GitHub UI step)**

Navigate to https://github.com/vaadin/components-workspace/settings/branches and add a branch protection rule on `main` requiring the `Collect results` check before merge. This cannot be scripted reliably via gh CLI without admin token scopes; document the setting and leave it to a human admin.

- [ ] **Step 3: Commit and push the README change**

```bash
git add README.md
git commit -m "docs: document the validation CI workflow"
git push
```

---

## Done

After Task 12 completes:
- `.github/workflows/validation.yml` lives at the workspace root with six jobs.
- `scripts/overlay-component-names.sh`, `scripts/compute-it-matrix.sh`, and their test harnesses live in `scripts/`.
- `TB_LICENSE` is set on the repo and consumed by `package-war` and `its` jobs.
- `README.md` mentions the CI workflow.
- Branch protection on `main` requires the `Collect results` check.
- One green workflow run exists on `main`.

The workflow is now ready to validate further `flow-components-overlay/overlays.txt` rollouts as the npm-workspace approach extends across all 52 IT modules.

---

## Post-verification adjustments

While iterating on PR #1 to make CI work end-to-end, these changes were made on top of the plan above. The plan body was rewritten in place to reflect them (Task 5, Task 6); this section records the rationale.

- **Workspace `package.json` includes both submodule roots.** `web-components` and `flow-components` were added to the `workspaces` array so their root-level devDependencies (xml2js for `mergeITs.js`, the WTR runner, etc.) get hoisted into the workspace `node_modules/` and become discoverable to Node scripts invoked from the workflow.
- **`build.gradle.kts` lists `flow-components/package.json` as an `npmInstall` input.** Submodule pointer bumps that change those devDeps now re-run the install task instead of silently keeping stale state.
- **`unit` job now sets up Node 24 and drops `-DskipSvgChartsBuild`.** `vaadin-charts-flow-svg-generator` runs Node-driven tests as part of `mvn test`; the original plan suppressed them, but they are useful regression signal and the runner already has Node available.
- **`wtr` job now sets up JDK 21 and installs the TestBench license.** WTR-eligible flow-components reuse Maven-built classpath state (needs JDK) and include some Pro-gated features (needs TestBench license).
- **`wtr` job temporarily disabled via `if: false`.** A license/setup issue is being triaged; the job body is otherwise wired up. Re-enable by deleting the `if: false` line. Tracked under §Future Work in the spec.
