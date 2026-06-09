# IT npm Workspace: Complete Rollout Design Spec

## Overview

Extend the npm workspace overlay from the 4-module pilot (button, grid, combo-box, date-picker) to all remaining `flow-components` integration-test modules whose primary component ships a `@vaadin/*` package that lives in `web-components/packages/`. This completes the architecture established in `2026-06-04-it-npm-workspace-design.md` and brings the full IT surface under local-package resolution.

## Goals

1. **Full coverage** — every IT module that has a corresponding `@vaadin/*` package in `web-components/packages/` gets an overlay `package.json` that resolves it locally via `file:` URL.
2. **Automated derivation** — a generator script reads `@NpmPackage` annotations from each component's Java source and writes minimal overlay `package.json` files, eliminating hand-authoring for ~48 modules.
3. **CI verification** — the existing GitHub Actions validation pipeline on `verify-ci` (the `validation.yml` workflow) is used to confirm that IT tests pass end-to-end with local packages. The `workflow_dispatch` `components` input enables targeted re-runs per component group.
4. **No manual lockfile editing** — a single `npm install --ignore-scripts` at the workspace root regenerates `package-lock.json` from the updated overlay tree.

## Non-Goals

- Modules that have no `@vaadin/*` package in `web-components/packages/` (see Exclusions).
- Changing how the `flow-maven-plugin` or Selenium IT runner works.
- Supporting modules in commercial-only repos (`spreadsheet`, `ai-components`).

## Scope

### Included modules (all not yet in pilot)

All IT modules under `flow-components/vaadin-*-flow-parent/vaadin-*-flow-integration-tests/` whose main component class declares at least one `@NpmPackage(value = "@vaadin/…")` annotation that resolves to a package in `web-components/packages/`:

accordion, app-layout, aura-theme, avatar, badge, board, breadcrumbs, card, charts, checkbox, confirm-dialog, context-menu, crud, custom-field, dashboard, date-time-picker, details, dialog, field-highlighter, form-layout, grid-pro, icons, list-box, login, lumo-theme, map, markdown, master-detail-layout, menu-bar, messages, notification, ordered-layout, popover, progress-bar, radio-button, renderer, rich-text-editor, select, side-nav, slider, split-layout, tabs, text-field, time-picker, upload, virtual-list

> **Note on `map`:** its main class declares both `@vaadin/map` (exists in web-components) and third-party deps (`ol`, `proj4`). The overlay `package.json` lists only `@vaadin/map` as a `file:` dep. `ol` and `proj4` continue to be resolved from the registry during `flow:build-frontend`, exactly as today.

### Excluded modules

| Module | Reason |
|---|---|
| `vaadin-spreadsheet-flow-integration-tests` | No `@NpmPackage` annotation in the Java source; Spreadsheet ships its own npm bundle without a `web-components/packages/spreadsheet` counterpart |
| `vaadin-ai-components-flow-integration-tests` | No `@NpmPackage` annotation found; component has no web-components peer |

### Already in pilot (unchanged)

button, grid, combo-box, date-picker — their overlay `package.json` files already exist and are not regenerated.

## Generator Script

A new Node script at `scripts/generate-overlays.js` automates the authoring step. It is the only significant net-new piece of code.

### Inputs

- `flow-components/` submodule root (discovers candidate IT modules by directory scan; reads their primary component Java source for `@NpmPackage`)
- `web-components/packages/` (determines which `@vaadin/*` names are available locally)
- `flow-components-overlay/` (existing overlay files mark which IT modules are already covered)

### Algorithm

1. List every `@vaadin/<name>` for which `web-components/packages/<name>/` exists. This becomes the uniform `dependencies` list for every overlay.
2. Discover candidates: scan `flow-components/` for every `vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/` directory.
3. For each candidate:
   1. Locate the main component Java source: `flow-components/vaadin-<name>-flow-parent/vaadin-<name>-flow/src/**/*.java`.
   2. Extract all `@NpmPackage(value = "@vaadin/<pkg>", …)` annotations.
   3. Filter to those where `web-components/packages/<pkg>/` exists.
   4. If no matching packages: skip this module (it appears in the skip log only).
   5. Otherwise write `flow-components-overlay/vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/package.json`:

```json
{
  "name": "@vaadin-flow-integration-tests/vaadin-<name>-flow-integration-tests",
  "version": "0.0.0",
  "private": true,
  "dependencies": {
    "@vaadin/a11y-base": "file:../../../web-components/packages/a11y-base",
    "@vaadin/accordion": "file:../../../web-components/packages/accordion",
    "…": "file:../../../web-components/packages/…",
    "@vaadin/virtual-list": "file:../../../web-components/packages/virtual-list"
  }
}
```

Every overlay's `dependencies` object lists **every** `@vaadin/*` package from step 1, so the entire `@vaadin/*` graph (direct and transitive) resolves to the local workspace.

The `@NpmPackage` check in step 3 only gates **whether** the module gets an overlay. The dependency content is uniform across every overlay.

The root `package.json` `workspaces` array does not need updating per module: the positive glob `flow-components/*-flow-parent/*-flow-integration-tests` automatically picks up any new module. To exclude a module from npm workspaces (typically because it has no overlay and its Flow-generated leftover `package.json` would trip up the install), add a negative-glob entry there manually.

### Output

- A `package.json` file written under `flow-components-overlay/<parent>/<it>/` for every IT module that passed step 3. Existing files are overwritten — re-running the script restores the canonical shape.
- A summary to stdout: modules written, modules skipped.

### Idempotence

Running the script twice in a row produces identical output. Existing overlay files are overwritten so the script can also be used to **reset** the overlay tree after Flow's maven plugin has merged its own deps into the symlinked overlay during a local build.

## File Path Convention

The `file:` relative paths must point from the overlay file's location to the web-components package. The overlay files live two levels deep under `flow-components-overlay/`:

```
flow-components-overlay/vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/package.json
```

From there, `../../../../../web-components/packages/<pkg>` navigates:

| Segment | Traversal |
|---|---|
| `..` | up from IT module dir |
| `..` | up from parent dir |
| `..` | up from `flow-components-overlay/` |
| `..` | up from workspace root (no — stays at root) |

Actually the correct depth: the file is 3 levels deep inside the workspace root (`flow-components-overlay/<parent>/<it>/package.json`), so the path back to the workspace root is `../../..` and then into `web-components/packages/<pkg>`. Full path: `"file:../../../web-components/packages/<pkg>"`.

> **Verify against the pilot:** The existing button overlay uses `"file:../../../web-components/packages/button"`. The generator must use exactly this depth.

## Install and Build After Generation

After the generator runs:

```bash
npm install --ignore-scripts
```

This updates `package-lock.json` with all new workspace members hoisted into `node_modules/`. The lockfile is committed.

To fully validate the result locally before pushing, run the full build:

```bash
./gradlew build --no-daemon
```

This runs in order: `syncFlowOverlays` (materializes all new symlinks), `npm install` at the workspace root (workspace deps for `web-components`, `web-components/packages/*`, and every IT module member), and `mvn -DskipTests install` in flow-components (installs all `com.vaadin:*` JARs into local Maven repo). web-components is source-published TypeScript/Lit; no separate yarn or build step is needed for it from the outer Gradle build. The compiled Maven JARs are required for WAR packaging; the source TypeScript under `web-components/packages/*/src/` is consumed directly by `flow:build-frontend`'s Vite step at IT runtime.

`scripts/sync-flow-overlays.sh` discovers overlays by scanning `flow-components-overlay/` for every `vaadin-*-flow-parent/vaadin-*-flow-integration-tests/package.json`, so it picks up newly generated entries automatically.

## CI Verification Strategy

The `validation.yml` workflow on the `verify-ci` branch provides the verification surface. After the overlay files and lockfile are committed, verification proceeds in two stages:

### Stage 1: Matrix smoke-test (targeted)

Use the `workflow_dispatch` `components` input to run a representative subset:

```
accordion avatar checkbox notification text-field dialog
```

These cover components with varying dep counts and transitive complexity. If the IT shards pass for these, the overlay derivation logic is sound.

### Stage 2: Full run

Trigger the workflow without specifying `components` (empty = all overlay modules). This exercises every newly-added overlay module through:

1. `mergeITs.js` — merges overlay IT sources into `integration-tests/` build target
2. `package-war` — Vaadin production bundle build (confirms `flow-maven-plugin` coexistence)
3. IT shard matrix — Selenium tests against the packaged WAR

A green `Collect results` job on the full run is the acceptance gate.

### What a green run proves

- `npm install` correctly hoisted all new `@vaadin/*` packages from `file:` URLs.
- `flow-maven-plugin` accepted the pre-populated `node_modules/` and did not re-install from registry.
- The IT tests themselves exercised the locally-resolved component code.

## Rollout Sequence

1. Run `scripts/generate-overlays.js` — produces all new overlay `package.json` files under `flow-components-overlay/`.
2. Run `npm install --ignore-scripts` — updates `package-lock.json`.
3. Run `./gradlew build --no-daemon` — verifies symlinks materialize cleanly, npm resolves correctly, web-components TypeScript compiles, and all flow-components Maven modules install successfully.
4. Commit: `flow-components-overlay/` (new files) and updated `package-lock.json`.
5. Push to `verify-ci` branch — triggers CI (which runs `./gradlew build` in the install job, covering both submodule builds).
6. Stage 1 targeted run: pass → proceed. Fail → debug one component at a time.
7. Stage 2 full run: pass → merge to `main`.

## Success Criteria

- `flow-components-overlay/` contains overlay directories for all included modules (46 net new + 4 existing = 50 total — minus any module whose Java source has no `@vaadin/*` `@NpmPackage` match, such as `renderer`).
- `npm ls --workspaces` shows no `UNMET DEPENDENCY` warnings for any overlay member.
- `./gradlew install` from a clean state (after `git submodule update --init`) completes without errors.
- Full CI run on `verify-ci` is green (all IT shards pass, no `package-war` failures).

## Relation to Existing Specs and Scripts

| Artifact | Role |
|---|---|
| `2026-06-04-it-npm-workspace-design.md` | Foundational architecture; `Workspace root` section updated to reflect that workspace members live at submodule paths (via symlinks), matched by a positive glob plus negative-glob exclusions |
| `flow-components-overlay/` | Source of truth for overlay membership: each `vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/package.json` directly represents an overlay (no separate list file) |
| `scripts/sync-flow-overlays.sh` | Discovers overlays by scanning `flow-components-overlay/` |
| `scripts/generate-overlays.js` | Automates overlay authoring; discovers candidate IT modules by scanning `flow-components/` and skips ones that already have an overlay file |
| `.github/workflows/validation.yml` | Verification pipeline (unchanged) |
| `package.json` (workspace root) | Updated once with a positive glob over `flow-components/*-flow-parent/*-flow-integration-tests` and negative-glob exclusions for `ai-components`, `renderer`, `spreadsheet` |
| `package-lock.json` | Updated after generator run |
