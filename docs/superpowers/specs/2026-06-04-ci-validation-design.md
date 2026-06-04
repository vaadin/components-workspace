# CI Validation Design Spec

## Overview

Add a GitHub Actions workflow at the workspace root (`vaadin/components-workspace`) that validates the npm-workspace overlay approach end-to-end against flow-components' Selenium integration tests (ITs). The workflow takes the workspace through the same install path a developer uses locally (`./gradlew install`), then runs unit tests, Web Test Runner (WTR) tests, and Selenium ITs in parallel against the cached install state.

The trigger for this spec is the rollout of the npm workspace from its current 4-module pilot (`docs/superpowers/specs/2026-06-04-it-npm-workspace-design.md`) to all 52 flow-components IT modules. Running the full Selenium IT suite locally for each rollout step is too slow. CI takes over that role.

## Goals

1. **Validate every workspace PR.** Each PR that touches `flow-components-overlay/`, the workspace Gradle build, or the submodule pointers runs the validation pipeline.
2. **Mirror flow-components/validation.yml shape** so contributors recognise the structure: install → fast-checks → IT shards → results.
3. **Cap parallel IT jobs at 12.** The shard matrix grows from 4 to 12 as the rollout fills out, then stays at 12 with multiple modules packed per shard.
4. **Share install artifacts.** `npm install`, `yarn install`, and `mvn install` outputs are computed once in the install job and re-used by every downstream job via `actions/cache`.
5. **Scope ITs to overlay modules only.** Modules not yet rolled out are skipped — they offer no new signal vs. flow-components' own CI.

## Non-Goals

- Replacing flow-components' own `validation.yml`. That workflow keeps running upstream against the registry-published `@vaadin/*` packages. The workspace workflow is additive — it validates the local-overlay path.
- Running web-components own tests (unit, snapshot, visual, WTR). Those run upstream in web-components' own CI.
- Scheduled (`cron`) or `merge_group` triggers. Out of scope for the first cut; can be added later if drift detection becomes a need.
- Sub-sharding inside a single overlay module. Each shard runs whole modules end-to-end; if a single module exceeds the 90-minute job timeout, fan it out then.

## Trigger Model

```yaml
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
```

`pull_request` and `workflow_dispatch` only. No nightly or merge-queue trigger. The `components` input filters the IT matrix; the `debug` input controls Maven verbosity.

## Pipeline Shape

Six top-level jobs, dependency edges shown:

```
[install]
    │
    ├──────────────► [unit]            Java unit tests
    │
    ├──────────────► [wtr]             flow-components WTR
    │
    └──► [package-war] ──► [its]       Selenium ITs, parallel matrix (≤12)
                                │
[install, unit, wtr, package-war, its] ──► [results]
```

- `install` is the only job without a `needs:` edge. Everything else cache-restores its outputs.
- `unit`, `wtr`, and `package-war` start in parallel the moment `install` completes.
- `its` waits for `package-war` (it needs the integration-tests WAR cache).
- `results` runs with `if: always() && needs.install.result == 'success'` so failures in any leaf still get a report.

## Caching Strategy

One cache key per workspace commit. All five install-output paths are saved under a single key in the install job and restored in every downstream job.

### Cache key

```
${{ runner.os }}-workspace-install-${{ hashFiles(
  'package.json',
  'package-lock.json',
  'flow-components-overlay/**',
  'web-components/yarn.lock',
  'gradle/**',
  'build.gradle.kts',
  'settings.gradle.kts'
) }}-${{ github.sha }}
```

Including `github.sha` makes the key change on every workspace commit, which is necessary because submodule pointers are gitlinks (not file content) — `hashFiles` cannot see when a PR bumps the flow-components or web-components SHA without touching any other file. The cost is reduced cross-PR cache sharing; the benefit is correctness: every workspace commit gets a fresh install pass.

### Cached paths

| Path | Populated by |
|---|---|
| `~/.m2/repository/com/vaadin` | `mvn install` inside flow-components |
| `node_modules` (workspace root) | `npm install` in install job |
| `web-components/node_modules` | `yarn install` inside web-components |
| `web-components/.yarn` | `yarn install` inside web-components |
| `flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated` | `mvn install` in flow-components |

The overlay symlinks inside flow-components are **not** cached. They live inside the submodule working tree (which is reset on every checkout) and are cheap to recreate via `scripts/sync-flow-overlays.sh`. Each downstream job runs the sync script after restoring the cache.

### Restore in downstream jobs

```yaml
- uses: actions/cache/restore@v5
  with:
    key: ${{ needs.install.outputs.cache-key }}
    path: |
      ~/.m2/repository/com/vaadin
      node_modules
      web-components/node_modules
      web-components/.yarn
      flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
    fail-on-cache-miss: true

- run: bash scripts/sync-flow-overlays.sh
```

`fail-on-cache-miss: true` makes a missing cache surface as a clear job failure rather than silently re-running install logic. If the install job failed, downstream jobs skip via cache miss.

## Install Job

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

    - name: Restore cache (skip work if hit)
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

    - name: Save cache
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

    - name: Compute IT matrix
      id: matrix
      env:
        COMPONENTS: ${{ inputs.components }}
      run: |
        matrix=$(bash scripts/compute-it-matrix.sh)
        echo "$matrix" | jq .
        {
          echo 'value<<EOF'
          echo "$matrix"
          echo 'EOF'
        } >> "$GITHUB_OUTPUT"
```

`./gradlew install` runs in sequence:

1. `:flow-components:syncFlowOverlays` — materialize overlay symlinks.
2. `:npmInstall` — `npm install` at workspace root; populates `node_modules/`.
3. `:web-components:install` — `yarn install` inside the submodule.
4. `:flow-components:install` — `mvn -DskipTests install` (full reactor).

The Gradle layer is what ties these together — the workflow does not re-invent the ordering.

## Fast-Check Branches

Three parallel leaves off `install`. Each is a single job, no matrix.

### `unit` — Java unit tests

```yaml
unit:
  needs: install
  runs-on: ubuntu-latest
  timeout-minutes: 30
  steps:
    - checkout (recursive submodules)
    - setup-java 21
    - cache restore (fail-on-cache-miss: true)
    - sync overlay symlinks
    - run: cd flow-components && mvn test -Drelease -T 4 -Dsurefire.parallel=classes -Dsurefire.threadCount=2 -DskipSvgChartsBuild -B -ntp
    - upload-artifact: surefire-reports (**/target/surefire-reports/TEST-*.xml)
```

Runs the full flow-components unit-test suite. Not overlay-scoped — unit tests are fast and don't depend on the overlay path.

### `wtr` — Web Test Runner

```yaml
wtr:
  needs: install
  runs-on: ubuntu-latest
  timeout-minutes: 30
  steps:
    - checkout (recursive submodules)
    - setup-node 24
    - cache restore (fail-on-cache-miss: true)
    - sync overlay symlinks
    - run: cd flow-components && node scripts/wtr.js
    - upload-artifact: wtr-reports (**/wtr-results.xml)
```

WTR runs all WTR-eligible components in flow-components. Like `unit`, not overlay-scoped.

### `package-war` — package the integration-tests WAR

```yaml
package-war:
  needs: install
  runs-on: ubuntu-latest
  timeout-minutes: 30
  outputs:
    war-cache-key: ${{ steps.warkey.outputs.value }}
  steps:
    - checkout (recursive submodules)
    - setup-java 21
    - cache restore (fail-on-cache-miss: true)
    - sync overlay symlinks
    - install TestBench license  (from TB_LICENSE secret)
    - name: Merge ITs into integration-tests/
      run: cd flow-components && node scripts/mergeITs.js
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
    - name: Package WAR
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

The packaged WAR is the input every IT shard restores. Keyed off pom.xml + IT java sources, so unrelated PR pushes (overlay-only) hit the cache.

## IT Shards

Matrix is computed in the install job via `scripts/compute-it-matrix.sh`. The script reads `flow-components-overlay/overlays.txt`, optionally filters by `COMPONENTS`, counts IT classes per module, and packs into ≤12 buckets using a longest-job-first greedy heuristic.

### `scripts/compute-it-matrix.sh`

```bash
#!/usr/bin/env bash
# Reads flow-components-overlay/overlays.txt and emits a GH Actions matrix
# JSON to stdout. Packs the overlay list into at most MAX_SHARDS buckets;
# heaviest module first into the currently-smallest bucket gives roughly
# balanced wall-clock per shard.
#
# Env overrides:
#   MAX_SHARDS  — default 12
#   COMPONENTS  — space-separated short names to keep (e.g. "grid date-picker")
#
# The flow-components/ submodule must be checked out so IT class counts can
# be read from src/test/java.
set -euo pipefail

MAX_SHARDS="${MAX_SHARDS:-12}"
COMPONENTS="${COMPONENTS:-}"
SOURCE="${1:-flow-components-overlay/overlays.txt}"

if [ ! -f "$SOURCE" ]; then
  echo "::error::Overlay list not found at $SOURCE" >&2
  exit 1
fi

mapfile -t paths < <(grep -vE '^[[:space:]]*(#|$)' "$SOURCE")

if [ -n "$COMPONENTS" ]; then
  filtered=()
  for p in "${paths[@]}"; do
    name=$(basename "$p" | sed 's/^vaadin-//; s/-flow-integration-tests$//')
    for want in $COMPONENTS; do
      [ "$name" = "$want" ] && filtered+=("$p") && break
    done
  done
  paths=("${filtered[@]}")
fi

count=${#paths[@]}
[ "$count" -eq 0 ] && { echo '{"include":[]}'; exit 0; }

n=$count
[ "$n" -gt "$MAX_SHARDS" ] && n=$MAX_SHARDS

declare -a annotated
for p in "${paths[@]}"; do
  c=$(find "flow-components/$p/src/test/java" -name '*IT.java' 2>/dev/null | wc -l | tr -d ' ')
  annotated+=("${c:-0}|$p")
done
mapfile -t annotated < <(printf '%s\n' "${annotated[@]}" | sort -t'|' -k1,1nr)

declare -a paths_bkt names_bkt load
for ((i=0; i<n; i++)); do paths_bkt[$i]=""; names_bkt[$i]=""; load[$i]=0; done

for entry in "${annotated[@]}"; do
  c="${entry%%|*}"
  p="${entry#*|}"
  name=$(basename "$p" | sed 's/^vaadin-//; s/-flow-integration-tests$//')
  min=0
  for ((i=1; i<n; i++)); do
    [ "${load[$i]}" -lt "${load[$min]}" ] && min=$i
  done
  [ -n "${paths_bkt[$min]}" ] && { paths_bkt[$min]+=";"; names_bkt[$min]+=","; }
  paths_bkt[$min]+="$p"
  names_bkt[$min]+="$name"
  load[$min]=$((load[$min] + c))
done

json='{"include":['
for ((k=0; k<n; k++)); do
  [ $k -gt 0 ] && json+=','
  json+='{"shard":"'$((k+1))'/'$n'","names":"'${names_bkt[$k]}'","paths":"'${paths_bkt[$k]}'","weight":"'${load[$k]}'"}'
done
json+=']}'
echo "$json"
```

### Shard execution

```yaml
its:
  name: IT ${{ matrix.shard }} (${{ matrix.names }})
  needs: [install, package-war]
  if: needs.install.outputs.it-matrix != '' && fromJson(needs.install.outputs.it-matrix).include[0] != null
  runs-on: ubuntu-latest
  timeout-minutes: 90
  strategy:
    fail-fast: false
    matrix: ${{ fromJson(needs.install.outputs.it-matrix) }}
  steps:
    - checkout (recursive submodules)
    - setup-java 21
    - cache restore (install cache, fail-on-cache-miss: true)
    - cache restore (WAR cache from package-war, fail-on-cache-miss: true)
    - sync overlay symlinks
    - install TestBench license
    - name: Run shard
      env:
        PATHS: ${{ matrix.paths }}
      run: |
        IFS=';' read -ra MODULES <<< "$PATHS"
        failed=()
        for p in "${MODULES[@]}"; do
          echo "::group::Module $p"
          if ! ( cd "flow-components/$p" && mvn \
              jetty:start-war failsafe:integration-test jetty:stop failsafe:verify \
              -Drun-it -Dvaadin.productionMode -DskipFrontend \
              -Dfailsafe.forkCount=4 \
              -Dcom.vaadin.testbench.Parameters.testsInParallel=2 \
              -Dfailsafe.rerunFailingTestsCount=2 \
              -Dtest.reuseDriver=true \
              -B -ntp ); then
            failed+=("$(basename "$p")")
          fi
          echo "::endgroup::"
        done
        if [ ${#failed[@]} -gt 0 ]; then
          echo "::error::Failed modules in this shard: ${failed[*]}"
          exit 1
        fi
    - name: Upload failsafe reports
      if: always()
      uses: actions/upload-artifact@v6
      with:
        name: failsafe-reports-${{ matrix.shard }}
        path: flow-components/**/target/failsafe-reports/TEST-*.xml
        retention-days: 1
        if-no-files-found: ignore
    - name: Upload error screenshots
      if: failure()
      uses: actions/upload-artifact@v6
      with:
        name: error-screenshots-${{ matrix.shard }}
        path: flow-components/**/error-screenshots/
        retention-days: 5
        if-no-files-found: ignore
```

### Shard sizing

| Overlays.txt size | Shards | Modules per shard |
|---|---|---|
| 4 (today) | 4 | 1 |
| 12 | 12 | 1 |
| 26 | 12 | ~2 |
| 52 (full rollout) | 12 | ~4–5 (largest first) |

The 90-minute timeout accommodates 4–5 modules per shard at the upper end. If a single module's ITs ever exceed 90 minutes alone, the longest-job-first heuristic already places it in its own shard.

## Results Job

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
    - uses: actions/download-artifact@v8
      continue-on-error: true
      with: { name: surefire-reports, path: surefire-reports }
    - uses: actions/download-artifact@v8
      continue-on-error: true
      with: { name: wtr-reports, path: wtr-reports }
    - uses: actions/download-artifact@v8
      continue-on-error: true
      with: { pattern: failsafe-reports-*, merge-multiple: true, path: failsafe-reports }
    - uses: actions/download-artifact@v8
      continue-on-error: true
      with: { pattern: error-screenshots-*, merge-multiple: true, path: error-screenshots }

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

The pruning step matches flow-components' behaviour: on a red build, only failing reports are kept so the dorny report focuses on failures; on a green build, all reports are kept so every suite shows as passed.

## Secrets and Repository Configuration

- **`TB_LICENSE`** — required. Format `username/proKey`. Used by `package-war` and every `its` shard to install the TestBench license at `~/.vaadin/proKey`. Must be added to `vaadin/components-workspace` (or inherited from the `vaadin` org).
- **Default `GITHUB_TOKEN`** — sufficient for artifact deletion in the `results` job; no extra PAT needed.
- **Concurrency** — default org runner concurrency is enough; matrix is capped at 12 jobs at peak.

Branch protection on `main` should require the `Collect results` check before merge.

## Verification

The workflow is considered correct when, on the current 4-overlay state:

1. A PR that only touches workspace docs (no overlay changes) runs the pipeline to green in under ~30 minutes on average.
2. The IT matrix produces exactly 4 shards (`1/4` … `4/4`), one per overlay module.
3. Cache hits visible in logs: an unchanged PR re-push reuses the install and WAR caches; only `unit`, `wtr`, and the `its` shards re-run their actual test steps.
4. The TestBench license step writes `~/.vaadin/proKey` and no IT shard logs a licensing warning.
5. A deliberately failing IT (e.g., a thrown assertion in one button IT) surfaces in the dorny "Integration Tests" report on the PR. On red builds, the report-prune step keeps only the failing TEST-*.xml files, so the dorny check focuses on failures rather than scrolling through hundreds of passing tests.
6. `workflow_dispatch` with `components: "grid date-picker"` produces a 2-shard matrix, skipping button and combo-box.

After the rollout grows `overlays.txt`, the same workflow validates the new state without any further plumbing — only the matrix-compute script's output changes.

## Future Work

- **Scheduled run** against latest submodule heads. Adds a nightly `cron:` trigger that bumps both submodules to upstream `main` before installing, catching cross-repo drift even without a workspace PR.
- **Merge queue (`merge_group`) support.** Skip `pull_request` once we move to merge-queue gating to avoid double runs.
- **Sub-sharding for huge modules.** If a single module's IT suite ever exceeds the 90-minute shard timeout, split it by IT-class count (mirror flow-components' `TARGET_PER_SHARD=35` approach within a single module).
- **Cross-PR change detection.** If a workspace PR only changes a single overlay's `package.json`, run ITs only for that one module — analogous to flow-components' "modified components" detection.
- **Workspace lint job.** Add a fast pre-flight job that verifies every entry in `overlays.txt` has a matching `flow-components-overlay/<path>/package.json`, the symlinks would resolve, and the `file:` deps point at existing `web-components/packages/<name>/` directories.
- **Cached Chrome/Chromedriver.** Currently relies on the runner image's pre-installed Chrome. If runner upgrades cause IT flakiness, pin a specific version via `browser-actions/setup-chrome`.

## Implementation Steps

1. Add `scripts/compute-it-matrix.sh` (executable, content as in §IT Shards).
2. Add `.github/workflows/validation.yml` with the six jobs described in §Pipeline Shape, §Install Job, §Fast-Check Branches, §IT Shards, §Results Job.
3. Add the `TB_LICENSE` repository or org-level secret.
4. On a throwaway PR, verify a green end-to-end run with the current 4 overlays. Capture timing for the install job and each IT shard.
5. Add the workflow file's path to `CODEOWNERS` if a CODEOWNERS file is later introduced.
6. Update workspace `README.md` to mention the CI workflow and link this spec.
7. Configure branch protection on `main` to require `Collect results`.
8. Document the `workflow_dispatch` inputs in the workflow `name:` description so they're discoverable from the Actions tab.

## References

- `flow-components/.github/workflows/validation.yml` — the template this design borrows from; shard logic, dorny aggregation, license handling, and report pruning are all adapted from there.
- `flow-components/.github/workflows/checkstyle-check.yml` — single-job pattern; informs the fast-check leaves.
- `web-components/.github/workflows/verify.yml` and `unit-tests.yml` — Node + yarn install patterns; not directly reused since the workspace owns `npm install`.
- `docs/superpowers/specs/2026-06-04-it-npm-workspace-design.md` — the rollout this workflow is intended to validate.
- `docs/superpowers/specs/2026-06-04-common-gradle-build-design.md` — the Gradle layer the install job invokes.