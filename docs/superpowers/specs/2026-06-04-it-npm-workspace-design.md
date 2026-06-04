# Flow Integration Tests npm Workspace Design Spec

## Overview

Set up an npm workspace at the components-workspace root that lets `flow-components` integration-test (IT) modules consume `@vaadin/*` packages directly from the local `web-components/` submodule instead of from the npm registry. A single `npm install` at the workspace root creates a hoisted `node_modules/` tree shared across all workspace members, with `@vaadin/*` packages resolved to the local `web-components/packages/*` sources via `file:` URLs.

This solves the "cross-repo integration testing" need carried forward from the components-workspace design spec — testing unreleased web-components changes against flow-components IT tests without publishing.

## Goals

1. **Local IT testing against unreleased web-components** — IT tests pick up local edits to `web-components/packages/*` without an intervening publish step.
2. **Single install command** — `npm install` (or `./gradlew install`) at the workspace root sets up the entire frontend dependency graph for both submodules.
3. **No modifications to tracked submodule files** — all net-new files live in the workspace repo; reach into the submodule only via symlinks.
4. **Pilot first, then scale** — prove the design on 4 representative IT modules before rolling out to the remaining 48.

## Non-Goals

- Modifying anything tracked inside the `web-components/` or `flow-components/` submodules.
- Replacing `flow-maven-plugin` or any part of Vaadin Flow's build pipeline. The workspace install pre-populates state; Flow's plugin keeps working unchanged.
- Switching `web-components/` from yarn-classic to npm. The submodule keeps yarn-1.x for its own dev workflow; the outer workspace uses npm independently.
- Publishing the workspace or any of its overlay `package.json` files to a registry. They are all `private: true`.
- Production-bundle / release-build concerns. This spec targets local IT development; production-bundle behavior is out of scope.

## Tooling Choices

| Choice | Decision |
|---|---|
| Workspace tool | npm (9+) |
| Workspace location | `components-workspace/` root |
| Dep style for IT modules | Explicit `file:` URLs into `web-components/packages/<name>` |
| Cross-package version pinning | Inherited from web-components (`25.2.0-beta1`, Lerna-synced) |
| Coexistence with Flow | Workspace owns deps; `flow-maven-plugin` reuses what is already installed |

Why npm and not yarn:
- Matches Hilla's reference workspace tool.
- Matches the default tool used by `flow-maven-plugin`.
- web-components keeps yarn-classic for its own internal workflow; outer workspace uses npm independently.

## Architecture

### Overlay tree

`flow-components` IT modules currently have no per-module `package.json`. Adding one is a flow-components contribution that would normally be committed inside the submodule. We can't do that here — submodule tracked files belong to upstream. Instead:

1. **Real `package.json` files live in the workspace repo** under `flow-components-overlay/<parent>/<it-module>/package.json`. The directory layout mirrors the submodule path so the mapping is obvious.
2. **Symlinks inside the submodule** point from the corresponding submodule path to the overlay file: `flow-components/<parent>/<it-module>/package.json` → relative path into `flow-components-overlay/`.
3. **The submodule's own committed `.gitignore`** already silences these symlinks — line 28 of `flow-components/.gitignore` ignores `**/package*json` (un-ignoring only the two files at the submodule root, which is exactly the existing convention for Flow's auto-generated per-module package.json files).

No `.git/info/exclude` editing. No new `.gitignore` files created inside the submodule. The submodule's `git status` stays clean because the existing rules already cover our overlay case.

### Setup step

The overlay symlinks must be materialized after each fresh `git submodule update --init`. A small idempotent shell script (`scripts/sync-flow-overlays.sh`) reads a list of overlay targets and creates the symlinks. Wired as a Gradle task (`syncFlowOverlays`) that runs as a prerequisite of `:flow-components:install`, so most contributors never invoke it directly — `./gradlew install` covers it.

### Install flow

1. `syncFlowOverlays` — creates / verifies the symlinks (fast no-op when nothing has changed).
2. `npm install` at the workspace root — npm reads the workspace globs, treats `web-components/packages/*` and `flow-components-overlay/<parent>/<it>` directories as workspace members, resolves the IT modules' `file:` deps to their workspace-sibling counterparts, hoists shared transitive deps to the workspace-root `node_modules/`.
3. Vaadin Flow's `flow-maven-plugin` (when IT tests run via Maven) reads the now-existing per-module `package.json`, finds it already declares exactly what the Java `@NpmPackage` annotations require, and treats the npm step as a no-op.

## File Layout

```
components-workspace/
├── package.json                              # NEW — npm workspace root
├── package-lock.json                         # NEW — committed
├── node_modules/                             # gitignored
│
├── flow-components-overlay/                  # NEW — workspace-tracked overlay tree
│   ├── README.md                             # one-paragraph orientation
│   ├── overlays.txt                          # newline-separated list of overlay targets
│   ├── vaadin-button-flow-parent/
│   │   └── vaadin-button-flow-integration-tests/
│   │       └── package.json
│   ├── vaadin-grid-flow-parent/
│   │   └── vaadin-grid-flow-integration-tests/
│   │       └── package.json
│   ├── vaadin-combo-box-flow-parent/
│   │   └── vaadin-combo-box-flow-integration-tests/
│   │       └── package.json
│   └── vaadin-date-picker-flow-parent/
│       └── vaadin-date-picker-flow-integration-tests/
│           └── package.json
│
├── scripts/                                  # NEW
│   └── sync-flow-overlays.sh                 # idempotent symlink creator
│
├── gradle/
│   └── flow-components.gradle.kts            # extended: syncFlowOverlays task
│
├── web-components/                           # submodule — tracked files untouched
│   └── packages/<70 packages>                # workspace members via glob
│
└── flow-components/                          # submodule — tracked files untouched
    └── vaadin-button-flow-parent/
        └── vaadin-button-flow-integration-tests/
            └── package.json -> ../../../../flow-components-overlay/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json
```

`.gitignore` additions at the workspace root:

```
node_modules
```

`package.json` and `package-lock.json` at the workspace root are tracked (they define the workspace).

## package.json Shapes

### Workspace root

```json
{
  "name": "components-workspace",
  "private": true,
  "version": "0.0.0",
  "workspaces": [
    "web-components/packages/*",
    "flow-components-overlay/*/*"
  ]
}
```

- `flow-components-overlay/*/*` matches `flow-components-overlay/<parent>/<it-module>`, where each IT module's `package.json` lives in the overlay tree.
- The workspace glob points at the **overlay** path, not the submodule path. The symlinks inside the submodule are not workspace members — they are just files visible to Flow's plugin when Maven runs. npm reads workspace members from the overlay tree where the real files live.

### Per-IT-module (example: date-picker)

`flow-components-overlay/vaadin-date-picker-flow-parent/vaadin-date-picker-flow-integration-tests/package.json`:

```json
{
  "name": "@vaadin-flow-integration-tests/date-picker",
  "private": true,
  "version": "0.0.0",
  "dependencies": {
    "@vaadin/date-picker": "file:../../../web-components/packages/date-picker"
  }
}
```

- Names use a `@vaadin-flow-integration-tests/*` scope so they cannot collide with real `@vaadin/*` packages.
- `private: true` + `version: 0.0.0` makes accidental publishing impossible.
- The `file:` path is **relative to the overlay file's location** (three `..` segments back to workspace root, then into `web-components/packages/<name>`).
- IT modules declare **only the direct `@vaadin/*` packages their Java sources reference via `@NpmPackage`**. Transitive deps resolve through the workspace automatically; we do not list them.

### Discovery rule for dependencies

For an IT module at `vaadin-X-flow-parent/vaadin-X-flow-integration-tests/`, grep the corresponding Java sources for `@NpmPackage(value = "@vaadin/...")`. The distinct values are the dependencies list. Their versions all match `25.2.0-beta1` (Lerna-synced across web-components).

For the pilot we do this by hand for the four modules. An automated generator script that derives these from annotations is a future-work item.

## Setup Script

`scripts/sync-flow-overlays.sh` is an idempotent Bash script (~50 lines, no Node dependency) that:

1. Reads `flow-components-overlay/overlays.txt` (one relative path per line, e.g., `vaadin-button-flow-parent/vaadin-button-flow-integration-tests`).
2. For each entry:
   - **Source**: `flow-components-overlay/<path>/package.json` — must exist; errors out if not.
   - **Target**: `flow-components/<path>/package.json` — created as a symlink.
   - **Symlink target value**: a relative path from the target's directory back into the overlay tree.
3. If the target already exists and is a symlink pointing at the correct path → skip silently.
4. If the target exists and is something else (regular file, wrong symlink) → fail loudly with the path; do not overwrite. The user resolves manually.
5. Prints one status line per overlay (`created` / `up-to-date` / `error`).

Bash, not Node, so it runs without any installed deps — important because it must succeed before `npm install`.

## Gradle Integration

Two changes to `gradle/flow-components.gradle.kts`:

```kotlin
val syncFlowOverlays = tasks.register<Exec>("syncFlowOverlays") {
    description = "Materializes workspace-tracked package.json overlays as symlinks inside flow-components/ IT modules."
    group = "build"
    workingDir = rootDir
    commandLine("bash", "scripts/sync-flow-overlays.sh")
}

tasks.named("install") {
    dependsOn(syncFlowOverlays)
}
```

One new task at the workspace root (`build.gradle.kts`) for the actual npm install:

```kotlin
import com.github.gradle.node.npm.task.NpmTask

// (in the existing project(":web-components") { ... } configure block we already applied
// the Node plugin to :web-components. The root project also needs it.)

apply(plugin = "com.github.node-gradle.node")
extensions.configure<com.github.gradle.node.NodeExtension> {
    download.set(false)
    nodeProjectDir.set(rootDir)
    workDir.set(layout.buildDirectory.dir("nodejs"))
    npmWorkDir.set(layout.buildDirectory.dir("npm"))
    yarnWorkDir.set(layout.buildDirectory.dir("yarn"))
}

val npmInstall = tasks.register<NpmTask>("npmInstall") {
    description = "Installs all workspace npm dependencies."
    group = "build"
    args.set(listOf("install"))
    dependsOn(":flow-components:syncFlowOverlays")
    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
}

tasks.named("install") {
    dependsOn(npmInstall)
}
```

After this change, `./gradlew install` runs:

1. `:flow-components:syncFlowOverlays` — creates / verifies overlay symlinks.
2. `npmInstall` — `npm install` at workspace root.
3. `:web-components:install` — `yarn install` inside the web-components submodule (unchanged; for web-components devs' own workflow).
4. `:flow-components:install` — still the no-op Maven placeholder.

The `inputs`/`outputs` declarations on `npmInstall` let Gradle skip it when `package.json` and `package-lock.json` haven't changed and `node_modules/` exists.

## Coexistence with `flow-maven-plugin`

When `mvn install` runs in an IT module, Flow's plugin:

1. Scans Java sources for `@NpmPackage` / `@JsModule` annotations.
2. Writes / updates the IT module's `package.json` with the deps it found.
3. Runs `npm install` (or `pnpm install` if `vaadin.pnpm.enable=true`).
4. Bundles the frontend via Vite.

The workspace approach is known-compatible (confirmed from prior Vaadin engineering experience):

- **Flow's plugin writes `package.json` but does not replace `file:` paths it encounters.** Our hand-authored deps with `file:` URLs survive Flow's writes.
- **`npm install` is mostly a no-op when the workspace is already set up.** npm detects the existing workspace and `node_modules/` tree, treating subsequent installs as deduplication passes.

Flow plugin properties we set / verify during the pilot:

- `vaadin.pnpm.enable=false` — force npm; matches our workspace tool.
- `vaadin.frontend.hotdeploy=true` — reduces some build-time package.json rewriting; aligns with the existing local-dev workflow.

Per-IT-module `node_modules/` resolution: Vite and Node's module resolver walk up the directory tree, so deps hoisted to the workspace-root `node_modules/` are visible from inside any IT module without an in-module `node_modules/` symlink.

## Pilot Module Selection

Four IT modules in the first cut, each chosen to exercise a distinct case:

| Module | What it proves |
|---|---|
| `vaadin-button-flow-integration-tests` | Simplest baseline — single `@vaadin/*` dep with minimal transitives. |
| `vaadin-grid-flow-integration-tests` | Complex case — grid has many direct `@NpmPackage` annotations. Tests deeper deps lists. |
| `vaadin-combo-box-flow-integration-tests` | Overlay/dropdown — combo-box pulls in `@vaadin/overlay`, `@vaadin/item`, `@vaadin/lit-renderer`. Tests deeper transitives. |
| `vaadin-date-picker-flow-integration-tests` | Cross-component transitive — `@vaadin/date-picker` itself depends on `@vaadin/button` and others. Confirms npm resolves transitive deps to workspace members and that a local edit to button surfaces in date-picker without re-install. |

`flow-components-overlay/overlays.txt`:

```
vaadin-button-flow-parent/vaadin-button-flow-integration-tests
vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests
vaadin-combo-box-flow-parent/vaadin-combo-box-flow-integration-tests
vaadin-date-picker-flow-parent/vaadin-date-picker-flow-integration-tests
```

## Verification

After implementation, the pilot is considered successful when:

1. From a fresh clone with no prior `node_modules/`:
   - `./gradlew install` completes without errors.
   - `flow-components/vaadin-{button,grid,combo-box,date-picker}-flow-parent/<it-module>/package.json` exists as a symlink into the overlay tree.
   - The workspace-root `node_modules/@vaadin/button` (and the other three) is a symlink into `web-components/packages/<name>`.
2. `cd flow-components && git status --short` is clean (no untracked overlay artifacts).
3. `cd web-components && git status --short` is clean.
4. A local edit to `web-components/packages/button/src/...` is reflected when the date-picker IT module is rebuilt — without an intervening `npm install`.
5. `mvn install -pl vaadin-button-flow-parent/vaadin-button-flow-integration-tests -DskipTests` completes inside `flow-components/` without overwriting the overlay symlink. After it runs, the symlink is still a symlink (not converted to a regular file).

## Future Work

- **Generator script** that derives each IT module's package.json from Java `@NpmPackage` annotations. Would let us roll out the remaining 48 IT modules mechanically.
- **Full rollout** to all 52 IT modules.
- **Sibling-component modules** (e.g., `vaadin-charts-flow-svg-generator` — already has its own tracked package.json with Node-25 issues; needs separate handling).
- **CI integration** of the workspace install + the Gradle `install` task as a single pipeline step.
- **Removing the existing `LOCAL_WEB_COMPONENTS_PATH` env-var / Vite-plugin mechanism** once the workspace approach is proven and covers all IT modules.

## Implementation Steps

1. Add `flow-components-overlay/README.md` describing the directory's purpose.
2. Add `flow-components-overlay/overlays.txt` with the four pilot module paths.
3. Hand-author the four overlay `package.json` files, deriving deps from each IT module's Java `@NpmPackage` annotations.
4. Write `scripts/sync-flow-overlays.sh` and verify it is idempotent.
5. Extend `gradle/flow-components.gradle.kts` with the `syncFlowOverlays` task and wire it into `:flow-components:install`.
6. Extend root `build.gradle.kts` to apply the Node plugin and add the `npmInstall` task, wired into the root aggregate `install`.
7. Add the workspace root `package.json` with the two workspace globs.
8. Add `node_modules` to the workspace root `.gitignore`.
9. Run `./gradlew install` end-to-end. Verify the four pilot IT modules' symlinks resolve, the workspace `node_modules/` has the `@vaadin/*` symlinks, and both submodules' git status is clean.
10. Run `mvn install -DskipTests` on one pilot IT module (e.g., button). Verify the overlay symlink is preserved.
11. Edit `web-components/packages/button/src/vaadin-button.js` (a no-op edit), rebuild the date-picker IT module's frontend, confirm the edit propagates.
12. Commit.
13. Update workspace `README.md` and `CLAUDE.md` to document the new install command and the overlay mechanism.

## References

- Previous spec: `docs/superpowers/specs/2026-06-04-common-gradle-build-design.md` — the Gradle build this design extends.
- Hilla's npm workspace structure: https://github.com/vaadin/hilla (reference for npm + workspaces + Java test packages in the same workspace).
- web-components yarn-classic workspace: `web-components/package.json` and `web-components/lerna.json`.
- flow-components `.gitignore` line 28 (`**/package*json`) — the rule that lets the overlay symlinks coexist with the submodule without any further git-config gymnastics.
