# Module-Based IT Sharding Design Spec

## Overview

The current workspace `validation.yml` shards integration tests by **IT class**: a `package-war` job runs `mergeITs.js` to fold every overlay module's IT sources into a single synthetic `integration-tests/` Maven module, packages one common WAR, caches it, and each of up-to-12 `its` shards restores that WAR and runs `mvn -pl integration-tests jetty:start-war failsafe:integration-test ...` with a `-Dit.test=...` filter naming the IT classes assigned to that shard.

This spec replaces that approach with **module-based sharding**: drop the common WAR, drop the merge step, and have each shard run `mvn verify -am -pl <module-list>` directly against a subset of overlay IT Maven modules. Each shard builds and tests its modules end-to-end — including their own per-module WARs and frontends — without sharing anything across the matrix.

The motivation is the recently-landed npm-workspace rollout (`docs/superpowers/specs/2026-06-04-it-npm-workspace-design.md`, `docs/superpowers/specs/2026-06-05-it-npm-workspace-complete-rollout.md`). Before that work, every flow-components IT module would resolve its `@vaadin/*` deps from the registry and run its own `npm install`, which made repeating per-module setup across shards expensive. The common-WAR pattern amortized that setup to once. Now that all overlay modules share dependencies through workspace symlinks installed once in the workspace install job, the per-shard setup cost is much lower — possibly low enough that the common WAR no longer pays for itself.

The deliverable is a PR that implements modular sharding **and** captures enough timing data to decide whether to keep it.

## Goals

1. **Replace** the common-WAR path in `validation.yml` with module-based sharding. No coexistence, no toggle.
2. **Preserve** every other aspect of the workflow: `install`, `unit`, `wtr`, and `results` jobs are unchanged; the `components` `workflow_dispatch` input still scopes the run; the ≤12-shard cap holds; reports are aggregated by the same `dorny/test-reporter` pipeline.
3. **Pack modules into shards by LPT** (Longest-Processing-Time bin-packing keyed on per-module IT class count) so wall-clock per shard stays roughly balanced even though overlay modules have wildly different IT counts.
4. **Enable a fair evaluation** by leveraging GitHub Actions' workflow-version semantics: PR-triggered runs use `validation.yml` from `main` (the common-WAR baseline), while manual `workflow_dispatch` on the PR branch runs the modular workflow against the same SHA. The spec documents this procedure so the evaluator can compare medians without setting up a parallel workflow.

## Non-Goals

- **No A/B harness in the workflow file.** The two approaches are not selectable at runtime. The same SHA can be tested both ways only because GH Actions sources PR workflows from the target branch — a built-in property, not something this spec adds.
- **No new metrics tooling.** Wall-clock comparison uses GitHub Actions' existing per-job timings shown in the UI. No StatsD, no run-summary CSV, no extra annotations.
- **No change to `unit`, `wtr`, `install`, or `results`.** Modular sharding is only the IT layer.
- **No change to `flow-components/scripts/mergeITs.js` itself.** It stays in the submodule for whatever upstream uses it for. The workspace just stops invoking it on CI.
- **No frontend-cache sharing across shards.** A future optimization if eval shows per-module Vite/Flow `build-frontend` cost dominates, but out of scope for this first cut — measuring honestly is the goal.

## Evaluation Procedure

This is the part that justifies "evaluate" in the spec title. The mechanic relies on a GH Actions property worth stating explicitly:

> For `pull_request` triggers, GitHub Actions runs the workflow file **from the target branch**, not the PR branch. So a PR landing modular changes still runs the old common-WAR `validation.yml` automatically on every push. To exercise the PR branch's modified workflow on the same SHA, the evaluator manually `workflow_dispatch`es from the PR branch via the Actions tab.

Procedure:

1. Open the PR with the modular changes against `main`.
2. Let the automatic `pull_request` run finish. This is a **common-WAR baseline** run on the PR's HEAD SHA, executed from `main`'s `validation.yml`.
3. From the Actions tab, dispatch `Validation` workflow with branch = PR branch and no inputs. This is a **modular run** on the same SHA, executed from the PR branch's `validation.yml`.
4. Repeat both ≥3 times to dampen runner-load noise. Pushing trivial commits to the PR is the simplest way to re-trigger step 2.
5. Record per-run end-to-end wall-clock (from workflow start to `results` job done) and per-job wall-clock for each major job.
6. Paste a small comparison table into the PR description: median, p90, sample count for each variant. Include cache-hit/miss state so noise from cold caches doesn't get mis-attributed.

**Decision rule:** adopt modular unless its median end-to-end wall-clock exceeds the common-WAR median by more than 10%. Operational simplicity (one fewer job, no `mergeITs.js` on CI, smaller workflow surface) is a tiebreaker — if the two approaches are within 10%, modular wins.

If modular loses, revert is `git revert` on the PR's merge commit; the previous common-WAR workflow returns intact.

## Changes to `.github/workflows/validation.yml`

The diff touches only the IT path. All other jobs (`install`, `unit`, `wtr`, `results`) are unchanged.

### Removed: `package-war` job

Delete the entire `package-war` job block (currently lines 213–306 of `validation.yml`). With no common WAR, there is no WAR cache to populate. Downstream `its` no longer needs the `war-cache-key` output.

### Removed: "Merge overlay ITs" step in `install`

The current `install` job runs `node scripts/mergeITs.js` at the end so `compute-it-matrix.sh` has a populated `integration-tests/src/test/java/` tree to scan. Modular mode reads the overlay module list directly, so this step is deleted along with the `COMPONENTS` env wiring on it.

### Modified: `install` job's matrix step

`scripts/compute-it-matrix.sh` is rewritten (see next section) to take a list of overlay component short names and emit a module-keyed matrix. The install job pipes `overlay-component-names.sh` into the matrix script:

```yaml
- name: Compute IT matrix
  id: matrix
  env:
    COMPONENTS: ${{ inputs.components }}
  run: |
    names=$(COMPONENTS="$COMPONENTS" bash scripts/overlay-component-names.sh)
    matrix=$(echo "$names" | bash scripts/compute-it-matrix.sh)
    echo "$matrix" | jq .
    {
      echo 'value<<EOF'
      echo "$matrix"
      echo 'EOF'
    } >> "$GITHUB_OUTPUT"
```

`overlay-component-names.sh` already filters by the `COMPONENTS` input, so the `workflow_dispatch` UX is unchanged.

### Modified: `its` job

`needs:` drops `package-war` (which is gone) and keeps `install`. The WAR-cache restore step is deleted. The shard execution step changes from a single-module `-Dit.test=` filtered invocation to a multi-module reactor invocation:

```yaml
its:
  name: IT ${{ matrix.shard }}
  needs: [install]
  if: needs.install.outputs.it-matrix != '' && fromJson(needs.install.outputs.it-matrix).include[0] != null
  runs-on: ubuntu-latest
  timeout-minutes: 90
  env:
    TB_LICENSE: ${{ secrets.TB_LICENSE }}
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

    - name: Compute artifact shard id
      id: shardid
      env:
        SHARD: ${{ matrix.shard }}
      run: |
        value="${SHARD//\//-}"
        echo "value=$value" >> "$GITHUB_OUTPUT"

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

    - name: Upload failsafe reports
      if: always()
      uses: actions/upload-artifact@v6
      with:
        name: failsafe-reports-${{ steps.shardid.outputs.value }}
        path: flow-components/**/target/failsafe-reports/TEST-*.xml
        retention-days: 1
        if-no-files-found: ignore

    - name: Upload error screenshots
      if: failure()
      uses: actions/upload-artifact@v6
      with:
        name: error-screenshots-${{ steps.shardid.outputs.value }}
        path: flow-components/**/error-screenshots/
        retention-days: 5
        if-no-files-found: ignore
```

Notable differences from the current `its` job:

- `mvn verify` (full lifecycle) instead of `mvn -pl integration-tests jetty:start-war failsafe:integration-test jetty:stop failsafe:verify`. Each IT module's POM already binds those goals to the `verify` phase, so the reactor handles start/stop per module.
- `-am` (also-make) ensures any inter-module deps a freshly-checked-out overlay module references get built from source in this shard. In practice this is a no-op since `~/.m2/repository/com/vaadin` is restored from the install cache, but `-am` is cheap insurance for cross-module references that bypass the install cache (e.g. SNAPSHOT shading).
- `-DskipUnitTests` keeps the shard focused on ITs — the `unit` job already covers them, and re-running them per shard would inflate wall-clock.
- `-Dvaadin.productionMode -Dvaadin.force.production.build=true` forces a production frontend build per module. This is the cost we are measuring.
- Report and screenshot upload paths gain `**` since reports now land in each module's own `target/`, not under a single synthetic `integration-tests/target/`. The `results` job's download step already uses `merge-multiple: true` so this transparently works for its consumer.

### Modified: `results` job

`needs:` drops `package-war` (now `[install, unit, wtr, its]`). No other change — the artifact-name patterns (`failsafe-reports-*`, `error-screenshots-*`) match the new uploads identically.

## Changes to `scripts/compute-it-matrix.sh`

Rewritten to work in module mode. Input is a whitespace-separated list of overlay short component names on stdin (e.g. `grid date-picker combo-box`). The script resolves each to its `vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests` path under `flow-components/`, counts `*IT.java` files in `src/test/java/`, LPT-packs the modules into ≤12 shards, and prints a GH Actions matrix JSON.

```bash
#!/usr/bin/env bash
# Reads overlay component short names from stdin and emits a GH Actions matrix
# JSON to stdout. Modules are LPT bin-packed by IT class count across at most
# MAX_SHARDS buckets, aiming for TARGET_PER_SHARD classes per shard.
#
# Env overrides:
#   MAX_SHARDS        — default 12 (hard cap on parallel shards)
#   TARGET_PER_SHARD  — default 35 (per-shard class count target before the
#                       hard cap takes over)
#
# Positional arg: flow-components root (default flow-components/). Each input
# name is mapped to <root>/vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests
# and its *IT.java file count is read from src/test/java/.
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

# Read overlay short names from stdin.
read -r -a names < <(cat)
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

# Sort descending by count for LPT.
mapfile -t sorted < <(printf '%s\n' "${pairs[@]}" | sort -k1,1 -nr -k2,2)

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

Differences vs. today's script:

- Reads from stdin (overlay names) instead of walking a merged source tree.
- Emits a `modules` key per shard (comma-separated Maven module paths) instead of `tests` (comma-separated FQ class names).
- LPT bin-packing keyed on per-module IT class count instead of round-robin distribution. Round-robin worked acceptably for class-level sharding because every class had ≈similar cost; modules vary by 30× (some have 1 IT, some have 30+), so LPT is required to keep wall-clock balanced.

`scripts/test-compute-it-matrix.sh` is updated accordingly — see §Verification for the cases it covers.

## Workspace Gradle Build

No changes required. The `:install` task still installs everything the IT shards need (`~/.m2/repository/com/vaadin`, `node_modules`, `web-components/.yarn`, etc.). The `mergeITs.js` invocation only happened in CI, not in the Gradle layer.

## Cost Analysis

The honest accounting of where time moves:

| Cost                          | Common-WAR (today)              | Modular (this spec)                          |
| ----------------------------- | ------------------------------- | -------------------------------------------- |
| `mergeITs.js` execution       | once in `install`               | none                                         |
| Maven JVM startup per shard   | 1×                              | 1× (still one invocation per shard)          |
| WAR packaging                 | once in `package-war`           | once per module per shard                    |
| Frontend build (Vite + Flow)  | once for the giant bundle       | once per module per shard                    |
| Jetty start/stop              | once per shard                  | once per module per shard                    |
| `failsafe:integration-test`   | one batch per shard             | one batch per module per shard               |
| WAR cache hit/miss            | matters; large cache item       | n/a (no cache)                               |
| `install` cache               | same                            | same                                         |
| Workflow surface (lines, jobs)| 6 jobs, ~530 lines              | 5 jobs, ~400 lines                           |

The bet: per-module frontend builds cost less now than the common WAR's monolithic Vite build, because (a) each module touches only its own components so the bundle graph is smaller, and (b) the workspace npm install has already populated `node_modules` so there is no `npm install` per module to re-incur. The evaluation confirms or falsifies this.

The risks:

- **Per-module Vite/Flow flat overhead.** If the per-module frontend build has a flat overhead (parser warm-up, plugin init, etc.) that doesn't scale down with bundle size, then N modules × flat-overhead > 1 module × big-overhead. The decision rule (>10% tolerance) gives modular some headroom for this without strictly requiring it to win.
- **Per-module Jetty start/stop cost.** Common-WAR runs one `jetty:start-war` per shard; modular runs one per module in the shard. Each Jetty boot loads the Vaadin/Spring runtime, which typically costs several seconds. With ~4 modules per shard at the 12-shard cap, this is tens of seconds added per shard — small relative to the IT phase itself, but worth measuring. If the eval shows it dominates, §Future Work documents a concrete mitigation (per-shard scoped `mergeITs.js`) that recovers the one-Jetty-per-shard property without re-introducing a global `package-war` job.

## Verification

The PR is correct when:

1. **Static checks** pass:
   - `bash scripts/test-compute-it-matrix.sh` covers: empty input → `{"include":[]}`; single module → 1 shard; many modules with skewed IT counts → LPT distributes counts within `max-min ≤ 1` slot for the largest module; `MAX_SHARDS=3` honoured; missing module path → `::error::` + non-zero exit.
   - `bash scripts/test-overlay-component-names.sh` unchanged and still passes (the script wasn't touched).
   - `./gradlew install` succeeds locally (workspace install path unaffected).
   - A local one-shard reproduction works: `cd flow-components && mvn verify -am -pl vaadin-button-flow-parent/vaadin-button-flow-integration-tests -Drun-it ...` succeeds against a freshly-installed cache.

2. **Workflow behaviour** on the PR:
   - The automatic `pull_request` run executes the unchanged common-WAR `validation.yml` from `main`. (Baseline; should pass green or surface the same failures as recent main runs.)
   - A `workflow_dispatch` against the PR branch executes the new modular `validation.yml`. Matrix produces between 1 and 12 shards depending on overlay count. Each shard runs its module set to green (or, on red, reports failing TEST-*.xml from per-module `target/failsafe-reports/`).
   - `workflow_dispatch` with `components: "grid"` produces a 1-shard matrix containing only the grid IT module.
   - Cache restore in the IT shards uses the install cache and succeeds (`fail-on-cache-miss: true` honoured).
   - The `results` job collects failsafe reports from all shards, deduplicates them via `merge-multiple: true`, and surfaces them in the `Integration Tests` dorny check.

3. **Evaluation data** is in the PR description by the time the PR is marked ready-for-review:
   - ≥3 common-WAR baseline run timings (end-to-end + per-job).
   - ≥3 modular run timings on the same SHA.
   - Median + p90 for both; explicit cache hit/miss state per run.
   - One-paragraph conclusion: adopt or revert, with reference to the >10% rule.

## Future Work

- **Per-shard scoped `mergeITs.js`.** If evaluation shows per-module Jetty start/stop dominates, recover the one-Jetty-per-shard property without re-introducing the global `package-war` job. Each shard's matrix entry would carry the component short names (not module paths); the shard's first step runs `node scripts/mergeITs.js <names>` to build a scoped `integration-tests/` module for just its components, then runs one `mvn -pl integration-tests jetty:start-war failsafe:integration-test jetty:stop failsafe:verify` cycle. Scoped WAR build runs in parallel across shards; frontend graph per shard is smaller than the global common WAR. The matrix script's contract changes from "modules per shard" to "component names per shard" — the LPT bin-packing logic is identical, only the leaf payload differs.
- **Frontend-build cache across shards.** If evaluation shows per-module Vite cost dominates, populate a shared `flow-components/**/target/frontend/generated/` cache in `install` and restore it in each shard. Requires per-module key fragments since module frontends differ.
- **Historical-timing LPT.** Once a few weeks of modular runs accumulate, swap IT class count for historical wall-clock as the LPT input. Closer match to actual per-shard cost than class count alone.
- **`merge_group` trigger.** Currently the PR runs the common-WAR baseline and the merge-queue (if enabled later) would too. Adding modular under `merge_group` is a follow-up after the eval lands.
- **Reuse across components-workspace and flow-components.** If modular wins decisively here, upstream `flow-components/validation.yml` could adopt the same pattern — `mergeITs.js` is genuinely flow-components-internal, and removing it from both CI paths simplifies the cross-repo story.

## Implementation Steps

1. Rewrite `scripts/compute-it-matrix.sh` to read overlay names from stdin and emit a module-keyed matrix using LPT.
2. Update `scripts/test-compute-it-matrix.sh` with the new test cases listed in §Verification step 1.
3. Edit `.github/workflows/validation.yml`:
   - Delete the `package-war` job.
   - Delete the "Merge overlay ITs" step from `install`.
   - Update `Compute IT matrix` step to pipe overlay names into the new script.
   - Drop `package-war` from `its.needs` and `results.needs`.
   - Drop the WAR-cache restore step from `its`.
   - Replace the `its` "Run IT shard" command with the modular `mvn verify -am -pl <modules>` invocation.
   - Update upload paths in `its` to use `flow-components/**/target/failsafe-reports/TEST-*.xml` and `flow-components/**/error-screenshots/`.
4. Open the PR. Let the automatic baseline run.
5. Trigger ≥3 `workflow_dispatch` runs on the PR branch. Push trivial commits to re-trigger ≥3 baseline runs.
6. Fill in the PR description with the timing table and a one-paragraph decision.
7. Reviewer sanity-checks the timing data and either approves the modular path or reverts.

## References

- `docs/superpowers/specs/2026-06-04-ci-validation-design.md` — the workflow this spec modifies; its §Pipeline Shape, §Install Job, and §Results Job remain authoritative for the parts not touched here.
- `docs/superpowers/specs/2026-06-04-it-npm-workspace-design.md`, `docs/superpowers/specs/2026-06-05-it-npm-workspace-complete-rollout.md` — the npm-workspace work that motivated this evaluation.
- `flow-components/scripts/mergeITs.js` — the upstream synthesizer that this spec stops calling from CI (it remains in the submodule for upstream use).
- `flow-components/CLAUDE.md` §Building and Testing — the per-module Maven incantations that modular shards now invoke directly.
- GitHub Actions docs on `pull_request` workflow source: workflow file is taken from the base branch for security; `workflow_dispatch` always runs the file from the dispatched ref. This asymmetry is what makes the same-SHA evaluation possible without a parallel workflow.
