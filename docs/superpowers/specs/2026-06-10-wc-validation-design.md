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
    ├──► [unit]                   Java unit tests        (unchanged)
    ├──► [wtr]                    flow-components WTR    (unchanged)
    ├──► [its]                    Selenium IT shards     (cap 12 → 6)
    ├──► [wc-verify]              yarn lint + test:snapshots + test:it
    ├──► [wc-unit]                matrix: chrome | firefox | webkit
    └──► [wc-visual]              yarn test:base + test:lumo + test:aura

[install, unit, wtr, its, wc-verify, wc-unit, wc-visual] ──► [results]
```

All three new jobs `needs: install` and fan out in parallel with `unit`, `wtr`, and `its`. The `results` job extends its `needs:` list to include them and adds them to the trailing "fail if any test failed" check.

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

## `wc-verify` — lint, snapshots, integration

Single job, ~10-min budget. Runs three steps sequentially inside `web-components/`. Lint goes first because it's the fastest fail.

```yaml
wc-verify:
  name: WC Verify
  needs: install
  runs-on: ubuntu-latest
  timeout-minutes: 15
  steps:
    - <shared prelude>

    - name: Lint
      working-directory: web-components
      run: yarn lint

    - name: Snapshot tests
      working-directory: web-components
      env:
        COMPONENTS: ${{ inputs.components }}
      run: |
        if [ -z "${COMPONENTS:-}" ]; then
          yarn test:snapshots --all
        else
          for c in $COMPONENTS; do
            yarn test:snapshots --group "$c"
          done
        fi

    - name: Integration tests
      working-directory: web-components
      env:
        COMPONENTS: ${{ inputs.components }}
      run: |
        if [ -z "${COMPONENTS:-}" ]; then
          yarn test:it --all
        else
          for c in $COMPONENTS; do
            yarn test:it --group "$c"
          done
        fi
```

`yarn lint` runs across the whole repo regardless of `COMPONENTS` — lint has no per-component scope. The `--all` flag bypasses `wtr-utils.js`'s `getChangedPackages()` early-exit so the job has signal on PRs that don't touch `web-components/`.

## `wc-unit` — browser matrix

Three matrix entries, one per browser. Chrome uses the runner's pre-installed Chromium; Firefox and WebKit need a `playwright install --with-deps` step before tests run.

```yaml
wc-unit:
  name: WC Unit (${{ matrix.browser }})
  needs: install
  runs-on: ubuntu-latest
  timeout-minutes: 30
  strategy:
    fail-fast: false
    matrix:
      include:
        - browser: chrome
          cmd: yarn test
        - browser: firefox
          cmd: yarn test:firefox
          playwright: firefox
        - browser: webkit
          cmd: yarn test:webkit
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

`fail-fast: false` lets one browser's failure not mask another's. Per-matrix-entry checks appear on the PR check tab as `WC Unit (chrome)`, `WC Unit (firefox)`, `WC Unit (webkit)`.

## `wc-visual` — base, Lumo, Aura

Single job running inside the Playwright docker container that upstream `visual-tests.yml` uses. Sequential steps for each theme; each wrapped in `nick-fields/retry@v3` to absorb visual flakes the same way upstream does.

```yaml
wc-visual:
  name: WC Visual
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

    - name: Visual tests — base
      uses: nick-fields/retry@v3
      with:
        timeout_minutes: 20
        retry_wait_seconds: 60
        max_attempts: 3
        command: |
          cd web-components
          if [ -z "${COMPONENTS:-}" ]; then
            yarn test:base --all
          else
            for c in $COMPONENTS; do yarn test:base --group "$c"; done
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
            yarn test:lumo --all
          else
            for c in $COMPONENTS; do yarn test:lumo --group "$c"; done
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
            yarn test:aura --all
          else
            for c in $COMPONENTS; do yarn test:aura --group "$c"; done
          fi
      env:
        COMPONENTS: ${{ inputs.components }}

    - name: Upload failed screenshots
      if: failure()
      uses: actions/upload-artifact@v6
      with:
        name: wc-visual-screenshots
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

## Components Filter Semantics

`workflow_dispatch.inputs.components` is already declared in the workflow (consumed by `install` for matrix narrowing and by `wtr` for `node scripts/wtr.js $COMPONENTS`). The new jobs extend the same input semantics:

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

Today the workspace ships with 4 overlay modules and well under 210 IT classes total, so the cap doesn't kick in yet — IT matrix stays at 4 shards. The cap matters once the overlay set grows: each shard packs ~2× the work it would have under the old cap. The existing `timeout-minutes: 120` on the `its` job accommodates this.

Verification of the cap change is offline: pipe a synthetic 250-class list through the script (see §Verification step 7) and confirm the output has exactly 6 buckets.

## `results` Job Changes

Two surgical edits in the existing `results` job. No new dorny steps — pass/fail comes from job conclusions, not test reports.

1. Extend the `needs:` list:

   ```yaml
   needs: [install, unit, wtr, its, wc-verify, wc-unit, wc-visual]
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
       [[ "${{ needs.wc-verify.result }}" == "failure" ]] && failed=true
       [[ "${{ needs.wc-unit.result }}"   == "failure" ]] && failed=true
       [[ "${{ needs.wc-visual.result }}" == "failure" ]] && failed=true
       [[ "$failed" == "true" ]] && exit 1 || exit 0
   ```

`needs.<matrix-job>.result` is `failure` if **any** matrix entry failed (GitHub Actions semantics) — that's the behavior we want for `wc-unit`.

The existing `if: always() && needs.install.result == 'success'` gate stays unchanged: `results` runs whenever `install` succeeds and lets us aggregate even partial failures downstream.

## Caching

No changes to the cache key or cached paths. The existing install cache already includes `web-components/node_modules` and `web-components/.yarn`, populated by `yarn install` inside the submodule during `./gradlew install`. Visual tests' `node_modules` come from the same restore.

`web-components/packages/*/dist` is **not** in the cache and does not need to be. All web-components tests run against `src/` directly via `@web/dev-server-esbuild`; no test reads `dist/`. (Flow-components tests separately consume `web-components/packages/*` via npm-workspace symlinks, which resolve to the source tree, not to `dist/`.)

## Fork Gating

Only `wc-visual` has a fork gate (`if: github.repository_owner == 'vaadin'`), matching upstream `visual-tests.yml`. The lint/snapshot/integration/unit suites run on forks the same way they would on internal PRs — they don't depend on registered runners or secrets.

Forks contributing to this repo will see:

- `wc-verify`, `wc-unit (chrome|firefox|webkit)` — run normally.
- `wc-visual` — skipped (not failed). `needs.wc-visual.result` is `skipped`, which the trailing failure check does not treat as `failure`, so `Collect results` stays green on visual-test skip.

## Verification

The change is considered correct when, on the current 4-overlay state, the following all hold on a single test PR:

1. `wc-verify` completes within ~10 minutes; lint, snapshots, and integration each pass.
2. `wc-unit` produces three matrix checks (`chrome`, `firefox`, `webkit`); each passes; `fail-fast: false` is verified by deliberately failing a single browser run and watching the other two complete.
3. `wc-visual` runs on a `vaadin`-owned PR and completes within ~30 minutes (each theme ≤20 minutes with retries); fails-screenshots-on-failure upload to artifacts.
4. A fork-owned PR sees `wc-visual` skipped, not failed; `Collect results` stays green.
5. Deliberate failures in each new job (a lint error, a broken snapshot, a thrown assertion in a unit test, a screenshot diff) each surface as a red `Collect results` on the PR.
6. `workflow_dispatch` with `components: "grid"` runs each new job against only the `grid` group; lint still runs over the whole repo. Job logs show `yarn test:snapshots --group grid`, `yarn test:it --group grid`, `yarn test --group grid` etc.
7. `bash scripts/compute-it-matrix.sh` against a synthetic 250-IT input produces exactly 6 buckets (`1/6 … 6/6`). With the current 4-overlay class count, the live matrix still produces 4 shards.
8. The IT job's `Compute artifact shard id` step still rewrites `/` to `-` correctly (e.g., shard `6/6` → artifact `failsafe-reports-6-6`).
9. Cache hits visible in logs: unchanged-PR re-push reuses the install cache and the new jobs skip their `yarn install` equivalent entirely (no `yarn install` step exists in the new jobs by design).

## Future Work

- **JUnit aggregation for web-components results.** Wire `@web/test-runner-junit-reporter` (or equivalent) into the WTR configs so dorny can publish per-test counts for the new jobs on the PR check tab. Currently we rely on job-level pass/fail only.
- **Per-package sharding inside `wc-unit`.** If unit-test wall time grows past the 30-min budget once more packages migrate to per-package suites, shard the matrix on package rather than browser.
- **Cache Playwright browser downloads.** Firefox/WebKit each do a fresh `playwright install` per job. Caching `~/.cache/ms-playwright` keyed off the Playwright version pinned in `web-components/package.json` would save ~30s per run.
- **Bring up `wc-visual` on forks via a workflow trigger split.** Currently fork PRs lose visual signal. Upstream uses an environment-gated job — workspace could replicate once we evaluate the secret-handling story.
- **Sub-sharding visual tests by theme.** If `wc-visual` wall time becomes a critical-path concern (currently dominated by retries on flakes), split into three parallel jobs (base/Lumo/Aura) — design rejected for the first cut to keep job-count growth modest.

## Implementation Steps

1. Edit `scripts/compute-it-matrix.sh`: change `MAX_SHARDS="${MAX_SHARDS:-12}"` to `MAX_SHARDS="${MAX_SHARDS:-6}"`. Verify offline with a 250-class input.
2. Add the three new jobs (`wc-verify`, `wc-unit`, `wc-visual`) to `.github/workflows/validation.yml` between the `its` job and the `results` job.
3. Extend `results.needs:` to include the three new jobs and extend the trailing "Fail if any test failed" step with the three new conclusion checks.
4. On a throwaway PR, verify the §Verification checklist points 1–6 and 9 end-to-end with the current 4-overlay state.
5. Verify point 7 offline before pushing (cap change has no live signal until the overlay set grows).
6. No `README.md` or branch-protection changes needed — `Collect results` already gates merge and inherits the new jobs via the `needs:` list.

## References

- `.github/workflows/validation.yml` — the workspace workflow being extended.
- `docs/superpowers/specs/2026-06-04-ci-validation-design.md` — the parent CI spec defining the existing pipeline shape; this spec adds to it.
- `docs/superpowers/specs/2026-06-10-wtr-validation-design.md` — most recent prior extension; established the `${{ inputs.components }}` → env-var → loop pattern that this spec re-uses for the new jobs.
- `web-components/.github/workflows/verify.yml` — upstream lint + snapshot + integration job pattern.
- `web-components/.github/workflows/unit-tests.yml` — upstream Chrome/Firefox/WebKit pattern; Playwright install steps borrowed verbatim.
- `web-components/.github/workflows/visual-tests.yml` — upstream visual test pattern; container, env, retry, fork-gate borrowed verbatim.
- `web-components/wtr-utils.js` — defines `getChangedPackages()` and the `--all` / `--group` flag semantics this spec relies on.
- `scripts/compute-it-matrix.sh` — the only existing file edited (cap change). All other changes are inside `.github/workflows/validation.yml`.
