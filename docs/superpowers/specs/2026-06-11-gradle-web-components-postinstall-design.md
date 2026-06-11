# Gradle web-components Post-Install Design Spec

## Overview

The `install` job in `.github/workflows/validation.yml` currently runs two extra steps after `./gradlew install` to finish setting up `web-components/`. Both compensate for things that `npm install` at the workspace root cannot do under the workspace's hoisting + `ignore-scripts=true` configuration, and both have been confirmed reproducible locally — a developer running `./gradlew install` followed by `cd web-components && npm test` hits the same broken state CI did before these steps were added.

This spec moves both steps into the Gradle build as finalizers on the existing `npmInstall` task, so `./gradlew install` becomes the canonical install path for local and CI work alike. The CI workflow loses two steps; the local developer gains the same fixes for free.

## Goals

1. **Single install entry point.** `./gradlew install` (and `./gradlew npmInstall` alone) leaves the workspace in a fully-working state — no follow-up shell commands needed before running web-components tests.
2. **CI-local parity.** The validation workflow's install job stops carrying logic that exists nowhere else; the workflow YAML returns to pure orchestration.
3. **Idempotent re-runs.** Re-running `./gradlew install` against an already-installed workspace is a no-op (Gradle's UP-TO-DATE check skips both new tasks; `patch -N` skips already-applied hunks if the check is bypassed).
4. **Mirrors existing workspace patterns.** The new tasks follow the same shape as the existing `syncFlowOverlays` task: a thin `tasks.register<Exec>` wrapper around a shell script in `scripts/`.

## Non-Goals

- Modifying anything inside the `web-components/` submodule (`patches/`, `wtr-utils.js`, `.npmrc`, `package.json`). The compensation lives in the workspace.
- Replacing `patch-package` more broadly. `patch-package` works fine in the web-components repo itself; we only need to apply its patches when web-components is consumed as a workspace member (where its `postinstall` is suppressed by the root `.npmrc`).
- Removing `ignore-scripts=true` from the root `.npmrc`. The comment in `.npmrc` explains why it must stay: without it, web-components' `postinstall` runs `patch-package` against `web-components/node_modules/` which is empty under hoisting and the postinstall fails.
- Caching the patched node_modules separately in Gradle's build cache. CI already restores `node_modules/` and `web-components/node_modules/` via the workspace install cache; Gradle's local UP-TO-DATE check is sufficient on the developer side.
- Changing the CI cache key. The existing key already includes `web-components/patches/**` in its `hashFiles`, so a patch change still invalidates the cache and forces `./gradlew install` (and therefore the new finalizers) to re-run.

## Background

### Why patches need re-applying

`web-components/package.json` declares `"postinstall": "patch-package"`. The submodule ships `web-components/patches/`:

- `@web+test-runner-visual-regression+0.10.0.patch` — rewrites `index.d.ts`'s `export * from './browser/commands.mjs';` to `export * from './browser/commands';`. Without this, `tsc` under `moduleResolution: bundler` (which `web-components/tsconfig.json` uses) cannot resolve the `.mjs` extension and `lint:types` fails with `TS7016: Could not find a declaration file for module './browser/commands.mjs'` (16 errors).
- `@web+rollup-plugin-html+2.3.0.patch` — minor build fix used by `dev:build`.
- `lerna+9.0.6.patch` — adjusts `savePrefix` behavior.

When `web-components` is consumed as an npm workspace member from the workspace root, npm runs install at the root. The root's `.npmrc` sets `ignore-scripts=true` because `patch-package`'s `postinstall` (which looks at `<cwd>/node_modules/` relative to where it runs) targets the empty `web-components/node_modules/` directory under hoisting and errors out. The patches therefore never reach the hoisted `<root>/node_modules/@web/...` location where they're needed.

### Why the bin symlink needs creating

`web-components/wtr-utils.js:59` hardcodes:

```javascript
const pathToLerna = path.normalize('./node_modules/.bin/lerna');
const output = execSync(`${pathToLerna} la --since origin/main --json --loglevel silent`);
```

This module is loaded at config-evaluation time by `web-test-runner-it.config.js` and the visual-regression configs. From `cwd=web-components/`, `./node_modules/.bin/lerna` resolves to `web-components/node_modules/.bin/lerna` — which doesn't exist under workspace hoisting. The lerna binary lives at `<root>/node_modules/.bin/lerna`.

A relative symlink `web-components/node_modules/.bin → ../../node_modules/.bin` restores the lookup transparently: `wtr-utils.js`'s execSync resolves through the symlink to the hoisted binary and works without modification.

### Current task graph

`build.gradle.kts` declares:

```
./gradlew install
  ├── :flow-components:install
  │     └── :flow-components:syncFlowOverlays
  ├── :web-components:install               (no-op)
  └── :npmInstall
        └── :flow-components:syncFlowOverlays  (shared, same task)
```

The two CI-only steps run after `./gradlew install` finishes — Gradle has no knowledge of them.

## Changes

Four files touched in the workspace root; the spec doc itself counts as a fifth. No submodule changes.

### `scripts/apply-web-components-patches.sh` (new)

```bash
#!/usr/bin/env bash
# Applies web-components/patches/*.patch to the workspace-root node_modules.
# Idempotent: `-N` skips already-applied hunks. Run from any working
# directory; the script resolves the workspace root from its own location.
set -euo pipefail
cd "$(dirname "$0")/.."
for p in web-components/patches/*.patch; do
  echo "Applying $p"
  patch -p1 -d . -N < "$p"
done
```

Pattern mirrors `scripts/sync-flow-overlays.sh`: locally executable, no hidden state. `-N` makes re-runs no-ops when the hunks are already applied; without it, `patch` prompts (in TTY) or exits non-zero (in CI), either of which would fail the build on a re-run when patches are present from a previous install.

### `scripts/setup-web-components-bin.sh` (new)

```bash
#!/usr/bin/env bash
# Creates web-components/node_modules/.bin -> ../../node_modules/.bin so
# upstream code in web-components/wtr-utils.js can resolve the hoisted
# lerna binary via its hardcoded `./node_modules/.bin/lerna` relative path.
# Idempotent: `ln -snf` overwrites or creates as needed.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p web-components/node_modules
ln -snf ../../node_modules/.bin web-components/node_modules/.bin
```

`mkdir -p web-components/node_modules` is necessary because the npm workspace install does not create that directory (web-components has no own deps that escape hoisting). The directory is otherwise empty, so the symlink does not conflict with anything npm writes there.

### `build.gradle.kts` — two new tasks + finalizer wiring

Append two task registrations and wire them as finalizers on the existing `npmInstall`:

```kotlin
val applyWebComponentsPatches = tasks.register<Exec>("applyWebComponentsPatches") {
    description = "Applies web-components/patches/*.patch to node_modules. " +
        "Required because workspace `.npmrc` disables postinstall scripts."
    group = "build"
    workingDir = rootDir
    commandLine("bash", "scripts/apply-web-components-patches.sh")
    inputs.dir("web-components/patches")
    inputs.dir("node_modules/@web")
    outputs.file("node_modules/@web/test-runner-visual-regression/index.d.ts")
    outputs.file("node_modules/@web/test-runner-visual-regression/dist/visualDiffCommand.js")
    outputs.file("node_modules/@web/rollup-plugin-html/dist/output/emitAssets.js")
    outputs.file("node_modules/lerna/dist/index.js")
}

val symlinkWebComponentsBin = tasks.register<Exec>("symlinkWebComponentsBin") {
    description = "Creates web-components/node_modules/.bin symlink to the " +
        "hoisted workspace-root bin, so upstream code finds lerna at the " +
        "relative path it expects."
    group = "build"
    workingDir = rootDir
    commandLine("bash", "scripts/setup-web-components-bin.sh")
    inputs.dir("node_modules/.bin")
    outputs.file("web-components/node_modules/.bin")
}

tasks.named("npmInstall") {
    finalizedBy(applyWebComponentsPatches, symlinkWebComponentsBin)
}
```

`finalizedBy` is the right wiring: it runs the post-install tasks whenever `npmInstall` runs, whether triggered directly (`./gradlew npmInstall`) or transitively via the root `install` task. `dependsOn` from the root `install` task would also work but wouldn't cover the direct-invocation case.

`inputs`/`outputs` declarations give Gradle's UP-TO-DATE check enough information to skip the tasks on re-run. Neither task is marked `@Cacheable` — the outputs are filesystem-specific (a relative symlink; patched files in `node_modules/`) and have no value across machines.

If a new patch file is added to `web-components/patches/`, the `inputs.dir("web-components/patches")` declaration invalidates the task automatically. The `outputs.file(…)` entries cover all currently-patched files; adding a new patch would require adding the corresponding output file to the task declaration (or the task would not detect drift on the new patched file). Today there are three patch files producing four patched-file outputs; the developer adding a fourth patch updates this list.

### `.github/workflows/validation.yml` — remove two steps

Remove these blocks from the `install` job (currently after the `Workspace install` step):

```yaml
- name: Apply web-components patches
  if: steps.cache.outputs.cache-hit != 'true'
  working-directory: web-components
  run: |
    for p in patches/*.patch; do
      echo "Applying $p"
      patch -p1 -d .. < "$p"
    done

- name: Symlink web-components/node_modules/.bin to workspace root
  if: steps.cache.outputs.cache-hit != 'true'
  run: |
    mkdir -p web-components/node_modules
    ln -snf ../../node_modules/.bin web-components/node_modules/.bin
```

No other workflow changes. The install cache key already includes `web-components/patches/**` in `hashFiles`, so cache invalidation continues to work correctly.

### `docs/superpowers/specs/2026-06-10-wc-validation-design.md` — narrative update

Two paragraphs in the §Shared Job Prelude region describe why the install job applies patches and creates the symlink. Replace with a single sentence pointing at the Gradle finalizers in `build.gradle.kts`. The detailed rationale lives in this spec (§Background).

## Verification

### Locally

1. **Clean slate.** `./gradlew clean` removes `node_modules/`. Confirm `web-components/node_modules/` does not exist.
2. **Fresh install.** `./gradlew install` — both `applyWebComponentsPatches` and `symlinkWebComponentsBin` execute (not UP-TO-DATE on first run). The console output shows them under the `build` group.
3. **Patched state.** `cat node_modules/@web/test-runner-visual-regression/index.d.ts` reads `export * from './browser/commands';` (patched form, no `.mjs`).
4. **Symlink correctness.** `readlink web-components/node_modules/.bin` prints `../../node_modules/.bin`.
5. **Downstream usage.** `cd web-components && npm run lint:types` exits 0 (was 16 errors before this change).
6. **Integration config load.** `cd web-components && npm test -- --config web-test-runner-it.config.js --all` runs through to test completion (previously failed at config-load with `node_modules/.bin/lerna: not found`).
7. **Idempotent re-run.** `./gradlew install` a second time. Both tasks report `UP-TO-DATE`.
8. **Direct `npmInstall` invocation.** `./gradlew npmInstall` alone still triggers the finalizers (verifies the `finalizedBy` wiring, not just transitive `dependsOn`).

### In CI (push to `ci/wc-validation`)

9. **Full pipeline green** with the two YAML steps removed.
10. **No regression in install timing.** The install job's wall-clock should be unchanged or marginally faster — patches + symlink now run inside `./gradlew install`'s process rather than as separate workflow steps with their own setup overhead.
11. **Cache hit on re-push.** A re-push that doesn't change install inputs hits the install cache; the install step (and therefore the Gradle finalizers) is skipped entirely via the existing `if: steps.cache.outputs.cache-hit != 'true'` gate on the `Workspace install` step.
12. **Cache miss after patch change.** Adding or modifying a file in `web-components/patches/` invalidates the cache (via `hashFiles('web-components/patches/**')`), forces a fresh install, and the finalizer tasks re-run successfully.

## Future Work

- **Generalize for other workspace members' patches.** If a future submodule also ships patches, generalize the script to accept a patches directory as an argument (`scripts/apply-patches.sh <dir>`) and register one task per submodule.
- **Make the bin symlink unnecessary.** Filing a request upstream (`web-components/wtr-utils.js`) to use `path` resolution that walks up to find the workspace root, or to read the binary path via `require.resolve`, would obsolete `setup-web-components-bin.sh` entirely. Tracked separately.
- **Roll patches into the cache key contents, not just the cache key inputs.** Currently the cache key invalidates on patch changes via `hashFiles`. If the patches step ever became expensive enough to matter, we could split it into its own cacheable task with finer-grained inputs — but at <1s per task, this is premature.

## References

- `build.gradle.kts` — root project where the new tasks register.
- `gradle/flow-components.gradle.kts` — defines `syncFlowOverlays`, the pattern this spec mirrors.
- `scripts/sync-flow-overlays.sh` — the shape `apply-web-components-patches.sh` and `setup-web-components-bin.sh` follow.
- `.npmrc` (workspace root) — explains why `ignore-scripts=true` is set and why patches must be applied separately.
- `.github/workflows/validation.yml` — the workflow losing two install-job steps.
- `docs/superpowers/specs/2026-06-10-wc-validation-design.md` — the parent spec describing the validation workflow; this spec amends its narrative.
- `web-components/patches/` — the patches dir applied by this design.
- `web-components/wtr-utils.js:59` — the file with the hardcoded `./node_modules/.bin/lerna` lookup the symlink compensates for.
