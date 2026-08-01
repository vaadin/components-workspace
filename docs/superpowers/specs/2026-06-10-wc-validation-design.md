# web-components Validation Design Spec

## Overview

Extend the workspace `.github/workflows/validation.yml` to validate the `web-components` submodule alongside the `flow-components` checks it already runs. Today the workflow runs Java unit tests, flow-components WTR, and Selenium IT shards — all flow-components-side. This spec adds three sibling jobs that exercise web-components' own test suites (lint, snapshot, integration, unit across three browsers, visual across three themes) so every workspace PR has full pass/fail signal for both submodules.

The trigger for this spec is that workspace PRs now routinely pair flow-components and web-components changes; without web-components-side validation, the workspace pipeline cannot catch:

- Paired-branch regressions where unreleased web-components changes break against the flow-components pointer the same PR moves to.
- Drift between the workspace install path (`./gradlew install`, npm-workspace symlinks) and the upstream install path web-components CI exercises.
- Overlay-layout regressions where web-components packages still build standalone but misbehave when consumed via the workspace's symlink overlay.

The work also reduces the IT shard cap from 12 to 6 so the new parallel load fits inside org runner concurrency without slowing overall wall-clock time on PRs that fan out the full IT matrix.

## Goals

1. **Run every web-components test suite on every workspace PR.** Lint, snapshot, integration, unit (Chrome/Firefox/WebKit), and visual (base/Lumo/Aura). Default behavior is `--all`; auto-scoping based on changed packages is bypassed so workspace-only PRs still get overlay-sanity signal.
2. **Honor the existing `workflow_dispatch.inputs.components` filter.** The same input that narrows the IT matrix narrows the new jobs to per-component runs via `yarn test --group <name>`.
3. **Reuse the existing install cache.** The new jobs `needs: install` and restore the cache already populated by `./gradlew install` — no separate `yarn install` step.
4. **Keep result aggregation simple.** Pass/fail signals via job exit code only. No dorny test-reporter wiring. The existing `results` job extends its `needs:` list and trailing failure check.
5. **Cap IT shards at 6 (down from 12).** Make room for the new parallel jobs without exceeding the workspace's effective parallel budget; pack more IT classes per shard instead.

## Non-Goals

- Replacing web-components' own CI. Upstream `verify.yml`, `unit-tests.yml`, `visual-tests.yml` keep running on web-components PRs. The workspace jobs are additive — they exercise the workspace install path.
- Dorny JUnit aggregation for the new jobs. WTR doesn't emit JUnit by default in this repo; wiring it would touch the web-components test runner config. Out of scope for the first cut.
- Re-architecting `wtr-utils.js`'s `getChangedPackages()` logic. We sidestep it by passing `--all`; we do not change the upstream auto-scoping behavior.
- Scheduled (`cron`) or `merge_group` triggers. Inherits the existing trigger model.
- Per-package sharding of unit/visual tests inside a single browser/theme. Each runs as one job; no nested shard matrix.
- Updating the workspace `README.md` or branch-protection settings. Those are tracked under §Implementation Steps but not part of the design proper.

## Pipeline Shape

```
[install]
    │
    ├──► [flow-components-unit]               Java unit tests
    ├──► [flow-components-wtr]                flow-components WTR
    ├──► [flow-components-its]                Selenium IT shards (cap 6)
    ├──► [web-components-verify]              yarn lint + test:snapshots + test:it
    ├──► [web-components-unit]                matrix: chrome | firefox | webkit
    └──► [web-components-visual]              yarn test:base + test:lumo + test:aura

[install, flow-components-unit, flow-components-wtr, flow-components-its,
 web-components-verify, web-components-unit, web-components-visual] ──► [results]
```

All six per-submodule jobs `needs: install` and fan out in parallel. The `results` job consumes their conclusions and the trailing failure check fails the gate if any job reports `failure` (`skipped` is not treated as a failure, so fork PRs that skip `web-components-visual` keep `results` green).

## Shared Job Prelude

All three new jobs use the same first four steps:

```yaml
- uses: actions/checkout@v6
  with:
    submodules: recursive
    fetch-depth: 0

- uses: actions/setup-node@v6
  with:
    node-version: '24'

- uses: actions/cache/restore@v5
  with:
    key: ${{ needs.install.outputs.cache-key }}
    path: |
      ~/.m2/repository/com/vaadin
      node_modules
      web-components/node_modules
      web-components/.yarn
      flow-components/**/node_modules
      flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
    fail-on-cache-miss: true
```

`fetch-depth: 0` matches upstream `web-components/.github/workflows/`'s checkout config. Strictly speaking, the `--all` flag in `wtr-utils.js` short-circuits before `getChangedPackages()` runs, so a shallow checkout would also work today. Full history is kept as defense-in-depth — a future change to the config that re-enters the lerna path on `--all` would silently fail on a depth-1 checkout.

No overlay-symlink sync step. Overlay symlinks live inside the `flow-components/` submodule and are only needed by flow-components-side tests.

JDK setup is not needed — web-components tests are pure Node.

All test commands inside `web-components/` use `npm`, not `yarn`. The workspace-level `npm install` (run in the `install` job) hoists shared devDependencies into the workspace-root `node_modules/.bin/`, leaving `web-components/node_modules/.bin/` empty. npm 7+ understands workspaces and resolves binaries from the workspace root when invoked from a workspace member; yarn 1.x does not. Running `cd web-components && npm test -- --config <theme>.config.js` therefore picks up `web-test-runner` from the hoisted root tree without any additional install step. For commands like `yarn lint` (whose script body is `npm-run-all --parallel lint:*`), `npm run lint` works for the same reason.

`web-components/package.json`'s `test:snapshots`/`test:it`/`test:firefox`/`test:webkit` scripts are thin yarn wrappers around `yarn test --config <config>.js`. The workflow does **not** call those wrappers (calling them via npm would still invoke yarn internally, which would then fail to resolve the workspace-root bins). Instead, the workflow invokes the underlying `web-test-runner` form directly: `npm test -- --config web-test-runner-<config>.config.js`.

The install job applies `web-components/patches/` after `./gradlew install`. The workspace root's `.npmrc` sets `ignore-scripts=true` (because web-components' `postinstall` runs `patch-package` against its own `node_modules`, which doesn't exist when devDeps are hoisted to the workspace root). The patches are still required — at minimum, `@web+test-runner-visual-regression+0.10.0.patch` rewrites a `.mjs` extension in `index.d.ts` that TypeScript's `bundler` module resolution refuses to follow, breaking `lint:types`.

The install job applies the patches with the system `patch` binary rather than `patch-package`, because `patch-package` 8.x hardcodes its target to `<cwd>/node_modules` — running it from the workspace root muddles the attribution (patch-package is a `web-components` devDependency), and running it from `web-components/` fails outright because the hoisted `node_modules` lives one level up. The workflow loops over `web-components/patches/*.patch` with `working-directory: web-components` and `patch -p1 -d .. < "$p"`. The `-d ..` directs patch at the workspace root, where `node_modules/@web/...` actually lives. The patched state lands in the install cache and every downstream job consumes it.

Immediately after the patches step, the install job creates the symlink `web-components/node_modules/.bin -> ../../node_modules/.bin`. `web-components/wtr-utils.js` (which is loaded at config-evaluation time by `web-test-runner-it.config.js` and the visual configs) hardcodes the path `./node_modules/.bin/lerna` and shells out to it via `execSync`. From `cwd=web-components/` that path resolves to `web-components/node_modules/.bin/lerna`, which under workspace hoisting is missing. The symlink restores the lookup transparently — `wtr-utils.js`'s `getChangedPackages()` call resolves `./node_modules/.bin/lerna` through the link to the hoisted root binary. The symlink is part of the cached `web-components/node_modules` path, so it persists into every downstream job.

## `web-components-verify` — lint, snapshots, integration

Single job, ~10-min budget. Runs three steps sequentially inside `web-components/`. Lint goes first because it's the fastest fail.

```yaml
web-components-verify:
  name: Web Components Verify
  needs: install
  runs-on: ubuntu-latest
  timeout-minutes: 15
  steps:
    - <shared prelude>

    - name: Lint
      working-directory: web-components
      run: npm run lint

    - name: Snapshot tests
      working-directory: web-components
      env:
        COMPONENTS: ${{ inputs.components }}
      run: |
        if [ -z "${COMPONENTS:-}" ]; then
          npm test -- --config web-test-runner-snapshots.config.js --all
        else
          for c in $COMPONENTS; do
            npm test -- --config web-test-runner-snapshots.config.js --group "$c"
          done
        fi

    - name: Integration tests
      working-directory: web-components
      env:
        COMPONENTS: ${{ inputs.components }}
      run: |
        if [ -z "${COMPONENTS:-}" ]; then
          npm test -- --config web-test-runner-it.config.js --all
        else
          for c in $COMPONENTS; do
            npm test -- --config web-test-runner-it.config.js --group "$c"
          done
        fi
```

`npm run lint` runs across the whole repo regardless of `COMPONENTS` — lint has no per-component scope. The `--all` flag bypasses `wtr-utils.js`'s `getChangedPackages()` early-exit so the job has signal on PRs that don't touch `web-components/`.

## `web-components-unit` — browser matrix

Three matrix entries, one per browser. Chrome uses the runner's pre-installed Chromium; Firefox and WebKit need a `playwright install --with-deps` step before tests run.

```yaml
web-components-unit:
  name: Web Components Unit (${{ matrix.browser }})
  needs: install
  runs-on: ubuntu-latest
  timeout-minutes: 30
  strategy:
    fail-fast: false
    matrix:
      include:
        - browser: chrome
          cmd: npm test --
        - browser: firefox
          cmd: npm test -- --config web-test-runner-firefox.config.js
          playwright: firefox
        - browser: webkit
          cmd: npm test -- --config web-test-runner-webkit.config.js
          playwright: webkit
  steps:
    - <shared prelude>

    - name: Install Playwright browser
      if: matrix.playwright
      working-directory: web-components
      run: npx playwright install ${{ matrix.playwright }} --with-deps

    - name: Run unit tests
      working-directory: web-components
      env:
        COMPONENTS: ${{ inputs.components }}
        CMD: ${{ matrix.cmd }}
      run: |
        if [ -z "${COMPONENTS:-}" ]; then
          $CMD --all
        else
          for c in $COMPONENTS; do
            $CMD --group "$c"
          done
        fi
```

`fail-fast: false` lets one browser's failure not mask another's. Per-matrix-entry checks appear on the PR check tab as `Web Components Unit (chrome)`, `Web Components Unit (firefox)`, `Web Components Unit (webkit)`.

## `web-components-visual` — base, Lumo, Aura

Single job running inside the Playwright docker container that upstream `visual-tests.yml` uses. Sequential steps for each theme; each wrapped in `nick-fields/retry@v3` to absorb visual flakes the same way upstream does.

```yaml
web-components-visual:
  name: Web Components Visual
  needs: install
  runs-on: ubuntu-latest
  timeout-minutes: 90
  if: github.repository_owner == 'vaadin'
  container:
    image: mcr.microsoft.com/playwright:v1.60.0-noble
    options: --ipc=host
  env:
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: 1
  steps:
    - uses: actions/checkout@v6
      with:
        submodules: recursive
        fetch-depth: 0

    - name: Fix git safe directory
      run: git config --global --add safe.directory $GITHUB_WORKSPACE

    - name: Fetch origin/main
      run: git fetch origin main

    - uses: actions/setup-node@v6
      with:
        node-version: '24'

    - name: Install zstd
      run: apt-get update && apt-get install -y zstd

    - <restore install cache, same as web-components-verify/web-components-unit>

    - name: Visual tests — base
      uses: nick-fields/retry@v3
      with:
        timeout_minutes: 20
        retry_wait_seconds: 60
        max_attempts: 3
        command: |
          cd web-components
          if [ -z "${COMPONENTS:-}" ]; then
            npm test -- --config web-test-runner-base.config.js --all
          else
            for c in $COMPONENTS; do npm test -- --config web-test-runner-base.config.js --group "$c"; done
          fi
      env:
        COMPONENTS: ${{ inputs.components }}

    - name: Visual tests — Lumo
      uses: nick-fields/retry@v3
      with:
        timeout_minutes: 20
        retry_wait_seconds: 60
        max_attempts: 3
        command: |
          cd web-components
          if [ -z "${COMPONENTS:-}" ]; then
            npm test -- --config web-test-runner-lumo.config.js --all
          else
            for c in $COMPONENTS; do npm test -- --config web-test-runner-lumo.config.js --group "$c"; done
          fi
      env:
        COMPONENTS: ${{ inputs.components }}

    - name: Visual tests — Aura
      uses: nick-fields/retry@v3
      with:
        timeout_minutes: 20
        retry_wait_seconds: 60
        max_attempts: 3
        command: |
          cd web-components
          if [ -z "${COMPONENTS:-}" ]; then
            npm test -- --config web-test-runner-aura.config.js --all
          else
            for c in $COMPONENTS; do npm test -- --config web-test-runner-aura.config.js --group "$c"; done
          fi
      env:
        COMPONENTS: ${{ inputs.components }}

    - name: Upload failed screenshots
      if: failure()
      uses: actions/upload-artifact@v6
      with:
        name: web-components-visual-screenshots
        path: |
          web-components/packages/*/test/visual/base/screenshots/*/failed/*.png
          web-components/packages/*/test/visual/lumo/screenshots/*/failed/*.png
          web-components/packages/*/test/visual/aura/screenshots/dark/*/failed/*.png
          web-components/packages/*/test/visual/aura/screenshots/default/*/failed/*.png
          web-components/packages/vaadin-lumo-styles/test/visual/screenshots/failed/*.png
        retention-days: 5
        if-no-files-found: ignore
```

`if: github.repository_owner == 'vaadin'` matches the fork-gate in upstream `visual-tests.yml`. Fork PRs cannot reliably reproduce visual screenshots against the workspace runner image; skipping the job (rather than failing it) keeps the `results` job green for legitimate fork contributions.

The container needs `git config --global --add safe.directory` because the Playwright image runs git as a different user than the one that owns the checkout (upstream `visual-tests.yml` does the same). The explicit `git fetch origin main` mirrors upstream behavior; with `--all` passed to every test command, lerna does not actually consult `origin/main` today, but the fetch is cheap and keeps the job structurally identical to upstream — useful if `wtr-utils.js` changes in the submodule.

The `Install zstd` step runs `apt-get install -y zstd` before the cache restore. `actions/cache@v5` uses zstd compression by default and fails to extract the cache archive if the binary is missing — the Playwright Noble image does not include `zstd` out of the box. Adding the install step adds ~5 seconds to the job and lets the cache restore work the same way it does in `web-components-verify` and `web-components-unit`.

The visual-test commands invoke `web-test-runner` directly via `npm test -- --config web-test-runner-<theme>.config.js`, **not** `npm run test:base/lumo/aura`. The `test:<theme>` scripts in `web-components/package.json` wrap each invocation in `./scripts/run-docker-visual-tests.sh`, which provides a deterministic browser environment by launching the Playwright image via `docker run`. Inside the GHA `container:` we are already running in that image, but there is no `docker` CLI available, so the wrapper fails. Calling `npm test -- --config …` bypasses the wrapper and runs the test directly — same pattern upstream `visual-tests.yml` uses.

## Components Filter Semantics

`workflow_dispatch.inputs.components` is already declared in the workflow (consumed by `install` for matrix narrowing and by `flow-components-wtr` for `node scripts/wtr.js $COMPONENTS`). The new jobs extend the same input semantics:

- Empty value (the `pull_request` trigger always has empty input): each job runs `yarn <cmd> --all`, exercising every web-components package that has tests.
- One or more space-separated short names (`"grid"`, `"grid combo-box"`): each job loops over the names and runs `yarn <cmd> --group "$c"` per name.
- An unrecognized name (`"made-up"`): `web-test-runner --group made-up` exits non-zero because no group matches, the job fails, and `results` aggregates the failure. Matches how the IT matrix surfaces unknown names today.

Lint is the only step that ignores `COMPONENTS` — it always lints the whole repo. Per-component lint scoping does not exist in `yarn lint` and adding it would complicate the script for no signal benefit.

## IT Shard Cap Reduction

Single change to `scripts/compute-it-matrix.sh`: flip the default `MAX_SHARDS` from `12` to `6`.

```bash
# scripts/compute-it-matrix.sh, line 23
MAX_SHARDS="${MAX_SHARDS:-6}"
```

Round-robin algorithm is unchanged. With `TARGET_PER_SHARD=35` and `MAX_SHARDS=6`, shard sizing becomes:

| Overlay IT classes | Shards | Classes per shard |
|---|---|---|
| ≤35 | 1 | up to 35 |
| 36–210 | `ceil(count/35)` | ≈35 |
| 211–420 | 6 (capped) | 36–70 |
| 420+ | 6 (capped) | 70+ |

Today the workspace ships with 4 overlay modules and well under 210 IT classes total, so the cap doesn't kick in yet — IT matrix stays at 4 shards. The cap matters once the overlay set grows: each shard packs ~2× the work it would have under the old cap. The existing `timeout-minutes: 120` on the `flow-components-its` job accommodates this.

Verification of the cap change is offline: pipe a synthetic 250-class list through the script (see §Verification step 7) and confirm the output has exactly 6 buckets.

## `results` Job Changes

Two surgical edits in the existing `results` job. No new dorny steps — pass/fail comes from job conclusions, not test reports.

1. Extend the `needs:` list:

   ```yaml
   needs: [install, flow-components-unit, flow-components-wtr, flow-components-its, web-components-verify, web-components-unit, web-components-visual]
   ```

2. Extend the trailing "Fail if any test failed" step to consult the new job conclusions. The new jobs do not have dorny step outputs, so we check `needs.<job>.result` directly:

   ```yaml
   - name: Fail if any test failed
     if: always()
     run: |
       failed=false
       [[ "${{ steps.unit-dorny.outputs.conclusion }}" == "failure" ]] && failed=true
       [[ "${{ steps.wtr-dorny.outputs.conclusion }}"  == "failure" ]] && failed=true
       [[ "${{ steps.it-dorny.outputs.conclusion }}"   == "failure" ]] && failed=true
       [[ "${{ needs.web-components-verify.result }}" == "failure" ]] && failed=true
       [[ "${{ needs.web-components-unit.result }}"   == "failure" ]] && failed=true
       [[ "${{ needs.web-components-visual.result }}" == "failure" ]] && failed=true
       [[ "$failed" == "true" ]] && exit 1 || exit 0
   ```

`needs.<matrix-job>.result` is `failure` if **any** matrix entry failed (GitHub Actions semantics) — that's the behavior we want for `web-components-unit`.

The existing `if: always() && needs.install.result == 'success'` gate stays unchanged: `results` runs whenever `install` succeeds and lets us aggregate even partial failures downstream.

## Caching

No changes to the cache key or cached paths. The existing install cache already includes `web-components/node_modules` and `web-components/.yarn`, populated by `yarn install` inside the submodule during `./gradlew install`. Visual tests' `node_modules` come from the same restore.

`web-components/packages/*/dist` is **not** in the cache and does not need to be. All web-components tests run against `src/` directly via `@web/dev-server-esbuild`; no test reads `dist/`. (Flow-components tests separately consume `web-components/packages/*` via npm-workspace symlinks, which resolve to the source tree, not to `dist/`.)

## Fork Gating

Only `web-components-visual` has a fork gate (`if: github.repository_owner == 'vaadin'`), matching upstream `visual-tests.yml`. The lint/snapshot/integration/unit suites run on forks the same way they would on internal PRs — they don't depend on registered runners or secrets.

Forks contributing to this repo will see:

- `web-components-verify`, `web-components-unit (chrome|firefox|webkit)` — run normally.
- `web-components-visual` — skipped (not failed). `needs.web-components-visual.result` is `skipped`, which the trailing failure check does not treat as `failure`, so `Collect results` stays green on visual-test skip.

## Verification

The change is considered correct when, on the current 4-overlay state, the following all hold on a single test PR:

1. `web-components-verify` completes within ~10 minutes; lint, snapshots, and integration each pass.
2. `web-components-unit` produces three matrix checks (`chrome`, `firefox`, `webkit`); each passes; `fail-fast: false` is verified by deliberately failing a single browser run and watching the other two complete.
3. `web-components-visual` runs on a `vaadin`-owned PR and completes within ~30 minutes (each theme ≤20 minutes with retries); fails-screenshots-on-failure upload to artifacts.
4. A fork-owned PR sees `web-components-visual` skipped, not failed; `Collect results` stays green.
5. Deliberate failures in each new job (a lint error, a broken snapshot, a thrown assertion in a unit test, a screenshot diff) each surface as a red `Collect results` on the PR.
6. `workflow_dispatch` with `components: "grid"` runs each new job against only the `grid` group; lint still runs over the whole repo. Job logs show `yarn test:snapshots --group grid`, `yarn test:it --group grid`, `yarn test --group grid` etc.
7. `bash scripts/compute-it-matrix.sh` against a synthetic 250-IT input produces exactly 6 buckets (`1/6 … 6/6`). With the current 4-overlay class count, the live matrix still produces 4 shards.
8. The IT job's `Compute artifact shard id` step still rewrites `/` to `-` correctly (e.g., shard `6/6` → artifact `failsafe-reports-6-6`).
9. Cache hits visible in logs: unchanged-PR re-push reuses the install cache and the new jobs skip their `yarn install` equivalent entirely (no `yarn install` step exists in the new jobs by design).

## Future Work

- **JUnit aggregation for web-components results.** Wire `@web/test-runner-junit-reporter` (or equivalent) into the WTR configs so dorny can publish per-test counts for the new jobs on the PR check tab. Currently we rely on job-level pass/fail only.
- **Per-package sharding inside `web-components-unit`.** If unit-test wall time grows past the 30-min budget once more packages migrate to per-package suites, shard the matrix on package rather than browser.
- **Cache Playwright browser downloads.** Firefox/WebKit each do a fresh `playwright install` per job. Caching `~/.cache/ms-playwright` keyed off the Playwright version pinned in `web-components/package.json` would save ~30s per run.
- **Bring up `web-components-visual` on forks via a workflow trigger split.** Currently fork PRs lose visual signal. Upstream uses an environment-gated job — workspace could replicate once we evaluate the secret-handling story.
- **Sub-sharding visual tests by theme.** If `web-components-visual` wall time becomes a critical-path concern (currently dominated by retries on flakes), split into three parallel jobs (base/Lumo/Aura) — design rejected for the first cut to keep job-count growth modest.

## Implementation Steps

1. Edit `scripts/compute-it-matrix.sh`: change `MAX_SHARDS="${MAX_SHARDS:-12}"` to `MAX_SHARDS="${MAX_SHARDS:-6}"`. Verify offline with a 250-class input (§Verification step 7) before pushing — the cap change has no live signal until the overlay set grows past 210 IT classes.
2. Add the three new jobs (`web-components-verify`, `web-components-unit`, `web-components-visual`) to `.github/workflows/validation.yml` between the `flow-components-its` job and the `results` job.
3. Extend `results.needs:` to include the three new jobs and extend the trailing "Fail if any test failed" step with the three new conclusion checks.
4. Push the changes on the current branch (`ci/wc-validation`) and open a PR from that branch to `main`. This is the live validation PR — not a throwaway. The PR's own pipeline run is what we use to verify §Verification checklist points 1–6 and 9 end-to-end against the current 4-overlay state. We merge the same PR after the run goes green.
5. No `README.md` or branch-protection changes needed — `Collect results` already gates merge and inherits the new jobs via the `needs:` list.

## References

- `.github/workflows/validation.yml` — the workspace workflow being extended.
- `docs/superpowers/specs/2026-06-04-ci-validation-design.md` — the parent CI spec defining the existing pipeline shape; this spec adds to it.
- `docs/superpowers/specs/2026-06-10-wtr-validation-design.md` — most recent prior extension; established the `${{ inputs.components }}` → env-var → loop pattern that this spec re-uses for the new jobs.
- `web-components/.github/workflows/verify.yml` — upstream lint + snapshot + integration job pattern.
- `web-components/.github/workflows/unit-tests.yml` — upstream Chrome/Firefox/WebKit pattern; Playwright install steps borrowed verbatim.
- `web-components/.github/workflows/visual-tests.yml` — upstream visual test pattern; container, env, retry, fork-gate borrowed verbatim.
- `web-components/wtr-utils.js` — defines `getChangedPackages()` and the `--all` / `--group` flag semantics this spec relies on.
- `scripts/compute-it-matrix.sh` — the only existing file edited (cap change). All other changes are inside `.github/workflows/validation.yml`.
