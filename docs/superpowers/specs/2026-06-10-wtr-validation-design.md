# WTR Validation Re-enable Design Spec

## Overview

The `wtr` job in `.github/workflows/validation.yml` is currently disabled via `if: false` (added in commit `ca1816e`, "ci: skip wtr job temporarily"). This spec covers re-enabling the job, scoping it consistently with the IT job via the existing `workflow_dispatch.inputs.components` filter, and bringing the CI validation spec back in sync with the workflow.

## Goals

1. **Re-enable WTR signal on workspace PRs.** Every PR that touches `flow-components-overlay/`, the workspace Gradle build, or the submodule pointers gets WTR results alongside unit and IT results.
2. **Honor the dispatch-time component filter.** `workflow_dispatch` with `components: "grid"` already narrows the IT matrix; WTR should narrow the same way for the same input.
3. **Keep the spec accurate.** Drop the "currently disabled" callout and the corresponding §Future Work bullet from `2026-06-04-ci-validation-design.md` so the spec describes the workflow as it actually runs.

## Non-Goals

- Adding sharding for WTR. Only four flow-components modules have a `test/` folder (`combo-box`, `renderer`, `grid`, `date-picker`) — all in the overlay set. One job is sufficient.
- Changing `flow-components/scripts/wtr.js` or anything inside the submodules.
- Re-architecting how WTR caches per-IT frontend builds or pre-installing Playwright at install-job time. Per-job install stays as-is.
- Detecting "WTR session produced no XML" in the `results` aggregator. Listed under §Future Work.

## Changes

Three files touched, all in the workspace root. No submodule changes.

### `.github/workflows/validation.yml`

Two edits inside the `wtr` job:

1. Remove the `if: false` line (currently line 153).
2. Pass the dispatch input through to the script via an env-var indirection (matches how the install job already handles the same input, and avoids interpolating workflow-dispatch input directly into a shell command):

   ```yaml
   - name: Run WTR tests
     env:
       COMPONENTS: ${{ inputs.components }}
     run: cd flow-components && node scripts/wtr.js $COMPONENTS
   ```

   When `inputs.components` is empty (the `pull_request` trigger has no such input), `wtr.js` enumerates all flow-components modules that have a `test/` folder — same default behavior as flow-components upstream uses.

The remaining job body (checkout, JDK 21, Node 24, install-cache restore, overlay-symlink sync, TestBench license install, report upload) is correct as-is.

### `docs/superpowers/specs/2026-06-04-ci-validation-design.md`

Two edits to keep the spec aligned with the workflow:

1. In §`wtr` — Web Test Runner, drop the `> **NOTE:** The wtr job is currently disabled via if: false …` block (around line 278).
2. In §Future Work, drop the "Re-enable the `wtr` job" bullet (around line 661).

### `docs/superpowers/specs/2026-06-10-wtr-validation-design.md`

This file.

## Local-Investigation Procedure

Before opening the PR, reproduce the WTR job's exact step sequence on the workstation, against each WTR-eligible overlay module. Push only after all four pass locally.

### Setup

1. Working tree clean on `fix/wtr-validation`. Submodules at the SHAs `main` currently records (already true on this branch).
2. `./gradlew clean install` from the workspace root — same install path the CI job restores from. Populates per-IT `node_modules`, workspace-root `node_modules`, the Maven `com.vaadin:*` cache, and `vaadin-charts-flow-svg-generator`'s generated frontend.
3. `~/.vaadin/proKey` present (TestBench license). If absent:
   ```sh
   mkdir -p ~/.vaadin
   echo '{"username":"<user>","proKey":"<key>"}' > ~/.vaadin/proKey
   ```
   The CI job materializes this from the `TB_LICENSE` secret; locally we use the workstation's license.

### Reproduce, one module at a time

4. `cd flow-components`
5. `node scripts/wtr.js grid` — runs `mvn -DskipTests flow:prepare-frontend flow:build-frontend` inside `vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/`, then `npx playwright install chromium`, then `npx web-test-runner --playwright test/**/*.test.ts --node-resolve`.
6. If it fails, three likely failure modes (resolve in this branch, ahead of the WTR re-enable commit):

   | Symptom | Likely cause | Fix |
   |---|---|---|
   | `flow:build-frontend` cannot resolve `@vaadin/grid` | Overlay symlinks not synced inside the submodule | `bash scripts/sync-flow-overlays.sh` (CI does this; locally `./gradlew install` does it via `syncFlowOverlays`) |
   | `web-test-runner` cannot resolve `@vaadin/grid` from `test/**/*.test.ts` | IT module `node_modules` missing the overlay package symlink | Confirm `web-components/packages/grid` is symlinked into `flow-components/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/node_modules/@vaadin/grid`. If missing, it's a workspace bug, not a WTR bug |
   | Playwright browser launch fails | Missing system deps on the runner image | Add `--with-deps` to the playwright install (`npx playwright install --with-deps chromium`). Defer until observed |

7. Repeat for `combo-box`, `renderer`, `date-picker`. Each module can surface its own quirk.

### Decision gate

Only after all four modules pass locally do we commit the YAML and spec changes and push. Any prerequisite fix (missing symlink, overlay tweak, etc.) goes in this branch as a separate commit ahead of the WTR re-enable commit, so the WTR commit itself stays a one-flag flip.

## Verification

After the PR is open, the change is considered correct when:

1. The `wtr` job appears in the workflow run and is not marked `skipped`.
2. The "Run WTR tests" step exits 0. If any module's WTR exits non-zero, we triage rather than re-disable.
3. The "Upload WTR reports" step uploads a `wtr-reports` artifact containing at least one `wtr-results.xml`.
4. The `results` job's "Publish WTR results" dorny step appears in the PR's checks tab as "WTR Tests", showing per-test counts.
5. `workflow_dispatch` with `components: "grid"` runs WTR over only the grid module; the "Run WTR tests" step log shows `node scripts/wtr.js grid` after env-substitution.
6. The `unit` and `its` jobs still pass — the WTR re-enable does not regress them via the shared install cache or overlay-sync path.

## Future Work

- **WTR setup-error handling in `results`.** Mirror flow-components' detection of "WTR ran but produced no XML" so silent setup failures do not mask as a green check.
- **Cache per-IT `flow:build-frontend` output across runs.** Frontend builds dominate WTR job time. Out of scope until we measure WTR's contribution to overall run time.
- **Per-module WTR check names.** If WTR grows beyond ~4 modules, splitting the dorny report into per-module checks improves PR-page readability.

## References

- `.github/workflows/validation.yml` — the workspace workflow being edited.
- `flow-components/scripts/wtr.js` — the WTR runner the job invokes; defines per-module discovery (any module with an `<it>/test/` folder).
- `flow-components/.github/workflows/validation.yml` — the upstream WTR job pattern; passes `${{ needs.build.outputs.components }}` to `wtr.js` for change-scoped runs.
- `docs/superpowers/specs/2026-06-04-ci-validation-design.md` — the parent CI spec; this spec amends §wtr and §Future Work there.
- Commit `ca1816e` ("ci: skip wtr job temporarily") — the commit this spec undoes.
