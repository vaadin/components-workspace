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

- **No A/B harness in the workflow file.** The two approaches are not selectable at runtime. There is no same-SHA comparison either: `pull_request` events run the PR-branch's workflow file, so the modular workflow is what the PR exercises. The baseline is recent `main`-branch runs of the previous common-WAR `validation.yml`.
- **No sibling scripts for the evaluation.** An earlier spec draft proposed `-modular` siblings on the assumption that the baseline would auto-run on the PR. With that assumption gone, the canonical `scripts/compute-it-matrix.sh` simply holds the modular logic.
- **No new metrics tooling.** Wall-clock comparison uses GitHub Actions' existing per-job timings shown in the UI. No StatsD, no run-summary CSV, no extra annotations.
- **No change to `unit`, `wtr`, `install`, or `results`.** Modular sharding is only the IT layer.
- **No change to `flow-components/scripts/mergeITs.js` itself.** It stays in the submodule for whatever upstream uses it for. The workspace just stops invoking it on CI.
- **No frontend-cache sharing across shards.** A future optimization if eval shows per-module Vite/Flow `build-frontend` cost dominates, but out of scope for this first cut — measuring honestly is the goal.

## Evaluation Procedure

An earlier draft of this spec assumed a same-SHA A/B comparison was possible because GH Actions would auto-run `main`'s `validation.yml` against the PR. That assumption was wrong:

> For `pull_request` events on PRs from the **same repository**, GitHub Actions runs the workflow file from the PR's head commit (the merge ref), not the base branch. Only `pull_request_target` reads the workflow from the base branch. So a PR that modifies `validation.yml` runs its own modified version on every push — there is no automatic same-SHA baseline.

Given that, the evaluation compares **PR runs** of the modular `validation.yml` against **recent main-branch runs** of the previous common-WAR `validation.yml`:

1. Open the PR with the modular changes against `main`.
2. The automatic `pull_request` run executes the PR's modular `validation.yml`. Let it finish.
3. Push trivial commits (or rebase) to re-trigger ≥3 PR runs. Each is a modular run.
4. Collect ≥3 recent `main`-branch runs of `validation.yml` from before this PR. Those are the common-WAR baseline. Restrict to runs that succeeded so failures don't skew timings.
5. Record per-run end-to-end wall-clock (from workflow start to `results` job done) and per-job wall-clock for each major job. Include cache-hit/miss state so noise from cold caches isn't mis-attributed.
6. Paste a small comparison table into the PR description: median, p90, sample count for each variant.

The comparison is across different SHAs (PR HEAD vs. recent main commits), so runner load and submodule drift add noise. The ≥3-runs-each + median-and-p90 approach is intended to dampen that noise.

**Decision rule:** adopt modular unless its median end-to-end wall-clock exceeds the matched-window common-WAR median by more than 10%. Operational simplicity (one fewer job, no `mergeITs.js` on CI, smaller workflow surface) is a tiebreaker — if the two approaches are within 10%, modular wins.

If modular loses, revert is `git revert` on the PR's merge commit; the previous common-WAR workflow returns intact.

## Changes to `.github/workflows/validation.yml`

The diff touches only the IT path. All other jobs (`install`, `unit`, `wtr`, `results`) are unchanged.

### Removed: `package-war` job

Delete the entire `package-war` job block (currently lines 213–306 of `validation.yml`). With no common WAR, there is no WAR cache to populate. Downstream `its` no longer needs the `war-cache-key` output.

### Removed: "Merge overlay ITs" step in `install`

The current `install` job runs `node scripts/mergeITs.js` at the end so `compute-it-matrix.sh` has a populated `integration-tests/src/test/java/` tree to scan. Modular mode reads the overlay module list directly, so this step is deleted along with the `COMPONENTS` env wiring on it.

### Modified: `install` job's matrix step

The canonical `scripts/compute-it-matrix.sh` is rewritten (see §Changes to `scripts/compute-it-matrix.sh`) to take a list of overlay component short names from stdin and emit a module-keyed matrix. The install job pipes `overlay-component-names.sh` into it:

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

`overlay-component-names.sh` is unchanged — it already produces the same shape the new `compute-it-matrix.sh` consumes.

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
          -Drun-it \
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
- **No `-Drelease`.** The common-WAR path passes `-Drelease` to suppress per-component IT modules in the reactor (the merged `integration-tests/` module subsumes them). Each `vaadin-<component>-flow-parent/pom.xml` declares its IT submodule inside a `default` profile activated by `<name>!release</name>`. Passing `-Drelease` deactivates that profile, removes the IT module from the reactor, and `-pl vaadin-X-flow-parent/vaadin-X-flow-integration-tests` then fails with "Could not find the selected project in the reactor". Modular mode wants the per-component IT modules in the reactor, so `-Drelease` must NOT be set. (The `unit` job's `mvn test -Drelease` is unaffected — it stays as is.)
- **Workspace must include `web-components`' nested workspaces.** The original workspace setup listed only `web-components` and `web-components/packages/*` as outer-workspace members, but `web-components/package.json` itself declares nested workspaces (`test/*`, `packages/*`, `api-docs`, `dev`). With those nested members invisible to the outer workspace, npm install hoisted some packages to the workspace-root `node_modules/` while others landed in `web-components/node_modules/`. Vite running inside an IT module then resolved `@vaadin/*` correctly (workspace-root) but failed on transitive deps that npm had put in `web-components/node_modules/`. Fix: flatten the nested workspaces into the root `package.json` (`web-components/test/*`, `web-components/dev`, `web-components/api-docs`) so a single hoist tree covers everything. After this fix, only the workspace-root `node_modules/` (plus a small `flow-components/node_modules/` for `@types/*` non-hoistables) exists.
- **`TaskCopyTemplateFiles` needs a grid-specific symlink.** `flow-components/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/.../TemplatedColumnsPage.java` is a `LitTemplate` subclass that declares `@JsModule("@vaadin/grid/src/vaadin-grid-column-group.js")`. Flow's `build-frontend` invokes `TaskCopyTemplateFiles` (in `flow-build-tools`), which calls `FrontendUtils.resolveFrontendPath(npmFolder=<IT module>, path, frontendDir)`. That lookup checks `<IT>/node_modules/<path>`, `<IT>/frontend/<path>`, and `<IT>/frontend/generated/jar-resources/<path>` only — it does NOT walk up to the workspace root. Under workspace hoisting `@vaadin/grid` lives at `<workspace-root>/node_modules/@vaadin/grid` and the lookup misses, failing with `Unable to locate file @vaadin/grid/src/vaadin-grid-column-group.js`. There is no Flow plugin parameter to skip just this task (`vaadin.skip` would skip the entire plugin and break IT bundling). Fix: `scripts/sync-flow-overlays.sh` creates one targeted symlink at `flow-components/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/node_modules/@vaadin/grid → ../../../../../web-components/packages/grid`. This is cycle-safe (the target is a workspace-member source dir with no `node_modules/` subdir, so tree walks bottom out). If another IT module ever triggers the same `TaskCopyTemplateFiles` failure mode, add a similarly narrow entry — no blanket `<IT>/node_modules/@vaadin/*` symlinks for every overlay.
- Report and screenshot upload paths gain `**` since reports now land in each module's own `target/`, not under a single synthetic `integration-tests/target/`. The `results` job's download step already uses `merge-multiple: true` so this transparently works for its consumer.

### Modified: `results` job

`needs:` drops `package-war` (now `[install, unit, wtr, its]`). No other change — the artifact-name patterns (`failsafe-reports-*`, `error-screenshots-*`) match the new uploads identically.

## Changes to `scripts/compute-it-matrix.sh`

Rewritten in place. The previous script walked a merged source tree populated by `mergeITs.js` and emitted class-keyed shards (`{shard, tests}`). The new script reads overlay short component names from stdin (e.g. `grid date-picker combo-box`), resolves each to its `vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests` path under `flow-components/`, counts `*IT.java` files in `src/test/java/`, LPT-packs the modules into ≤12 shards, and prints a GH Actions matrix JSON keyed by `{shard, modules}`.

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

Differences vs. the previous `scripts/compute-it-matrix.sh`:

- Reads from stdin (overlay names) instead of walking a merged source tree.
- Emits a `modules` key per shard (comma-separated Maven module paths) instead of `tests` (comma-separated FQ class names).
- LPT bin-packing keyed on per-module IT class count instead of round-robin distribution. Round-robin worked acceptably for class-level sharding because every class had ≈similar cost; modules vary by 30× (some have 1 IT, some have 30+), so LPT is required to keep wall-clock balanced.

`scripts/test-compute-it-matrix.sh` is rewritten in place with the cases listed in §Verification.

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
   - `bash scripts/test-compute-it-matrix.sh` covers: empty input → `{"include":[]}`; single module → 1 shard; missing module path → `::error::` + non-zero exit; many modules with skewed IT counts → LPT distributes them so every module appears exactly once and the cap of 12 shards is honoured; `MAX_SHARDS` and `TARGET_PER_SHARD` env overrides take effect; `MAX_SHARDS=0` rejected; newline-separated stdin is handled the same as space-separated.
   - `bash scripts/test-overlay-component-names.sh` unchanged and still passes (the script wasn't touched).
   - `./gradlew install` succeeds locally (workspace install path unaffected).
   - A local one-shard reproduction works: `cd flow-components && mvn verify -am -pl vaadin-button-flow-parent/vaadin-button-flow-integration-tests -Drun-it -Dvaadin.productionMode -Dvaadin.force.production.build=true -DskipUnitTests` succeeds. Note the **absence of `-Drelease`** — passing it disables the `default` profile in the parent POM that declares the IT module, and Maven reports "Could not find the selected project in the reactor".

2. **Workflow behaviour** on the PR:
   - The automatic `pull_request` run executes the PR's modular `validation.yml`. Matrix produces between 1 and 12 shards depending on overlay count. Each shard runs its module set to green (or, on red, reports failing TEST-*.xml from per-module `target/failsafe-reports/`).
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

1. Rewrite `scripts/compute-it-matrix.sh` in place: read overlay names from stdin, LPT-pack by per-module `*IT.java` count, emit a `{shard, modules}` matrix.
2. Rewrite `scripts/test-compute-it-matrix.sh` in place with the cases listed in §Verification step 1.
3. Flatten `web-components`' nested workspaces into the root `package.json` (`web-components/test/*`, `web-components/dev`, `web-components/api-docs`); delete `package-lock.json` and re-run `npm install` so the lockfile regenerates with a single hoist tree. Without this, Vite running in an IT module sees split resolution (some deps at workspace root, others under `web-components/node_modules/`) and fails. See §Notable differences for context.
4. Edit `.github/workflows/validation.yml`:
   - Delete the `package-war` job.
   - Delete the "Merge overlay ITs" step from `install`.
   - Update `Compute IT matrix` step to pipe overlay names into `scripts/compute-it-matrix.sh`.
   - Drop `package-war` from `its.needs` and `results.needs`.
   - Drop the WAR-cache restore step from `its`.
   - Replace the `its` "Run IT shard" command with the modular `mvn verify -am -pl <modules>` invocation. **Do not pass `-Drelease`** — see §Notable differences for the rationale.
   - Add `setup-node@v6` with `node-version: '24'` to the `its` job (per-shard frontend builds need a pinned Node).
   - Update upload paths in `its` to use `flow-components/**/target/failsafe-reports/TEST-*.xml` and `flow-components/**/error-screenshots/`.
   - Update the dorny IT path in `results` to `failsafe-reports/**/TEST-*.xml` to match the new nested upload layout.
   - Add `flow-components/**/node_modules` to every cache path list (1 save + 4 restores) so any non-hoisted per-IT or `flow-components/node_modules/@types/*` content propagates from install to shards.
5. Make `:web-components:install` a true no-op in `gradle/web-components.gradle.kts` (drop its `dependsOn(":npmInstall")`); the root `:install` task still triggers `:npmInstall` directly, so the workspace install happens exactly once.
6. Open the PR. The automatic `pull_request` run executes the new modular `validation.yml` against the PR HEAD — that's the data point for the modular variant.
7. Push trivial commits to accumulate ≥3 PR runs.
8. Collect ≥3 recent successful `main`-branch runs of the previous common-WAR `validation.yml` for the baseline. Fill in the PR description's comparison table.
9. Reviewer sanity-checks the timing data and either approves the modular path or reverts via `git revert` on the merge commit.

## References

- `docs/superpowers/specs/2026-06-04-ci-validation-design.md` — the workflow this spec modifies; its §Pipeline Shape, §Install Job, and §Results Job remain authoritative for the parts not touched here.
- `docs/superpowers/specs/2026-06-04-it-npm-workspace-design.md`, `docs/superpowers/specs/2026-06-05-it-npm-workspace-complete-rollout.md` — the npm-workspace work that motivated this evaluation.
- `flow-components/scripts/mergeITs.js` — the upstream synthesizer that this spec stops calling from CI (it remains in the submodule for upstream use).
- `flow-components/CLAUDE.md` §Building and Testing — the per-module Maven incantations that modular shards now invoke directly.
- GitHub Actions docs on `pull_request` workflow source: workflow file is taken from the base branch for security; `workflow_dispatch` always runs the file from the dispatched ref. This asymmetry is what makes the same-SHA evaluation possible without a parallel workflow.
