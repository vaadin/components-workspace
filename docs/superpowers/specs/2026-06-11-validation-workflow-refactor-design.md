# Workspace Validation Workflow Refactor Design Spec

## Overview

`.github/workflows/validation.yml` has grown to ~700 lines as the workspace gained the web-components submodule's validation jobs alongside the existing flow-components ones. The setup prelude — `actions/checkout` + `actions/setup-node` + (sometimes) `actions/setup-java` + `actions/cache/restore` with the same six-path list — repeats verbatim across six downstream jobs. The COMPONENTS-loop bash pattern (the `if [ -z "${COMPONENTS:-}" ]; then … --all; else for c in …; done` shape) appears in six test invocations. The `results` job's trailing failure check hand-maintains a `[[ … ]]` line per downstream job. Adding the next submodule's jobs would re-duplicate every one of those blocks.

This spec refactors the workflow to push shared setup into composite GitHub Actions under `.github/actions/`, shared bash into real scripts under `scripts/`, and a workflow-level `defaults` block at the top. Each downstream job's body drops to ~10-15 lines focused on the test invocation itself. Adding a new submodule's jobs becomes "copy a small template, swap the test commands" rather than reproducing the prelude.

Along the way the spec folds two install-job steps (web-components patches and bin symlink) into Gradle finalizers — both are reproducible locally and aren't CI-specific — and adds Gradle wrapper caching via `setup-gradle@v5`. These improvements touch the same install path and reinforce the same goal of making `./gradlew install` the canonical, self-contained, fast workspace setup. They ship together with the refactor.

## Goals

1. **Reduce duplication in `validation.yml`.** The setup prelude lives in one composite action, used by all downstream jobs. The six-path cache list lives in one place. Per-job YAML drops from ~30 lines to ~10-15.
2. **Move shared bash to scripts.** The COMPONENTS-loop pattern and the trailing failure check live in `scripts/*.sh` files. Each script is locally executable for debugging — no need to push commits to test what the workflow does.
3. **Prepare for more submodules.** Adding the next submodule's validation jobs is additive: copy a job template, swap the test commands. No re-duplication of setup logic.
4. **Make `./gradlew install` self-contained.** Two install-job steps (patches, bin symlink) move into Gradle finalizers so the workspace is fully set up after one `./gradlew install`, locally as well as in CI.
5. **Cache the Gradle distribution.** No more `gradle-8.10-bin.zip` download on every install-job cold run.

## Non-Goals

- **Per-submodule `workflow_call` split.** Discussed earlier and explicitly deferred — two submodules don't justify the file-count growth yet.
- **Publishing the composite actions for cross-repo reuse.** They live in `.github/actions/` of this repo only.
- **Replacing the GHA install cache with a workspace tarball artifact (hilla's pattern).** Cache is working well after recent fixes; switching introduces churn.
- **Pinning all actions to SHA via Dependabot.** Tracked as future work. The composite extraction reduces the surface, which makes Dependabot a smaller follow-up.
- **`restore-keys:` fallback for partial cache hits.** Deferred — wants careful design and verification that a stale-but-prefix-matching cache is safe.
- **Caching the patched `node_modules/` separately in Gradle's build cache.** CI already restores `node_modules/` via the workspace install cache; Gradle's local UP-TO-DATE check is sufficient.
- **Modifying anything inside the `web-components/` or `flow-components/` submodules.** All changes are in the workspace.

## Background

### Why the workflow is repetitive

Across `flow-components-unit`, `flow-components-wtr`, `flow-components-its`, `web-components-verify`, `web-components-unit`, and `web-components-visual`, the following blocks are byte-identical or near-identical (counts include the `install` job where applicable):

- `actions/checkout@v6` with submodules and fetch-depth: **8 occurrences**.
- `actions/setup-node@v6` with `node-version: '24'`: **7 occurrences**.
- `actions/setup-java@v5` with `java-version: '21'` and `cache: 'maven'`: **4 occurrences**.
- `actions/cache/restore@v5` with `fail-on-cache-miss: true`: **7 occurrences**.
- The six-path cache `path:` list (7 lines each): **8 occurrences**.
- `bash scripts/sync-flow-overlays.sh`: **3 occurrences**.
- TB license install (`mkdir -p ~/.vaadin; … > ~/.vaadin/proKey`, 5 lines): **2 occurrences**.
- The COMPONENTS-loop bash inside `run:` blocks (~9 lines each): **6 occurrences**.

Counting just the cache `path:` list: 8 × 7 = 56 lines of byte-for-byte duplication. Any change to the cached paths touches eight call sites.

### Why patches need re-applying (Gradle-fold context)

`web-components/package.json` declares `"postinstall": "patch-package"`. The submodule ships `web-components/patches/`:

- `@web+test-runner-visual-regression+0.10.0.patch` — rewrites `index.d.ts`'s `export * from './browser/commands.mjs';` to `export * from './browser/commands';`. Without this, `tsc` under `moduleResolution: bundler` (which `web-components/tsconfig.json` uses) cannot resolve the `.mjs` extension and `lint:types` fails with 16 errors.
- `@web+rollup-plugin-html+2.3.0.patch` — minor build fix used by `dev:build`.
- `lerna+9.0.6.patch` — adjusts `savePrefix` behavior.

When `web-components` is consumed as an npm workspace member from the workspace root, npm runs install at the root. The root's `.npmrc` sets `ignore-scripts=true` because `patch-package`'s `postinstall` targets the empty `web-components/node_modules/` under hoisting and errors out. The patches therefore never reach the hoisted `<root>/node_modules/@web/...` location where they're needed unless something else applies them.

### Why the bin symlink needs creating (Gradle-fold context)

`web-components/wtr-utils.js:59` hardcodes:

```javascript
const pathToLerna = path.normalize('./node_modules/.bin/lerna');
const output = execSync(`${pathToLerna} la --since origin/main --json --loglevel silent`);
```

Loaded at config-evaluation time by `web-test-runner-it.config.js` and the visual configs. From `cwd=web-components/`, `./node_modules/.bin/lerna` resolves to `web-components/node_modules/.bin/lerna` — empty under workspace hoisting; lerna lives at `<root>/node_modules/.bin/lerna`. A relative symlink `web-components/node_modules/.bin → ../../node_modules/.bin` makes the lookup work without modifying the submodule.

### Current Gradle task graph

```
./gradlew install
  ├── :flow-components:install
  │     └── :flow-components:syncFlowOverlays
  ├── :web-components:install               (no-op)
  └── :npmInstall
        └── :flow-components:syncFlowOverlays  (shared, same task)
```

The two CI-only post-install steps run after `./gradlew install` finishes — Gradle has no knowledge of them today.

## Changes

The refactor proper (composite actions + scripts + defaults) comes first; the Gradle integration and wrapper-caching improvements follow.

### Composite action: `.github/actions/setup-workspace/action.yml` (new)

Bundles the per-job setup prelude. Inputs:

- `cache-key` (required) — passed in from the caller as `${{ needs.install.outputs.cache-key }}`.
- `fetch-depth` (default `1`) — for jobs that need full history (none currently do).
- `setup-java` (default `'false'`) — toggle JDK 21 + Maven cache. Used by flow-components-* jobs.
- `sync-overlays` (default `'false'`) — toggle `bash scripts/sync-flow-overlays.sh`. Used by flow-components-* jobs.

Steps:

```yaml
name: Setup workspace
description: Common workspace prelude — checkout, Node/Java setup, install cache restore.

inputs:
  cache-key:
    required: true
  fetch-depth:
    default: '1'
  setup-java:
    default: 'false'
  sync-overlays:
    default: 'false'

runs:
  using: composite
  steps:
    - uses: actions/checkout@v6
      with:
        submodules: recursive
        fetch-depth: ${{ inputs.fetch-depth }}

    - if: inputs.setup-java == 'true'
      uses: actions/setup-java@v5
      with:
        java-version: '21'
        distribution: 'temurin'
        cache: 'maven'

    - uses: actions/setup-node@v6
      with:
        node-version: '24'

    - uses: actions/cache/restore@v5
      with:
        key: ${{ inputs.cache-key }}
        path: |
          ~/.m2/repository/com/vaadin
          node_modules
          web-components/node_modules
          web-components/.yarn
          flow-components/**/node_modules
          flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
        fail-on-cache-miss: true

    - if: inputs.sync-overlays == 'true'
      shell: bash
      run: bash scripts/sync-flow-overlays.sh
```

Used by all six downstream jobs. The six-path cache list lives only here. Per-job YAML for setup collapses to `- uses: ./.github/actions/setup-workspace` plus 1-3 inputs.

### Composite action: `.github/actions/install-tb-license/action.yml` (new)

```yaml
name: Install TestBench license
description: Writes ~/.vaadin/proKey from the TB_LICENSE secret.

inputs:
  tb-license:
    required: true

runs:
  using: composite
  steps:
    - if: inputs.tb-license != ''
      shell: bash
      run: bash scripts/install-tb-license.sh "${{ inputs.tb-license }}"
```

Used by `flow-components-wtr` and `flow-components-its`. The bash logic moves to `scripts/install-tb-license.sh`:

```bash
#!/usr/bin/env bash
# Writes ~/.vaadin/proKey from a TB_LICENSE-formatted string (user/key).
set -euo pipefail
license="$1"
mkdir -p ~/.vaadin
user="${license%%/*}"
key="${license#*/}"
printf '{"username":"%s","proKey":"%s"}\n' "$user" "$key" > ~/.vaadin/proKey
```

### Script: `scripts/run-wtr-config.sh` (new)

The COMPONENTS-loop pattern, parameterized by config file. Caller must `cd web-components` first; the script invokes `npm test` from cwd.

```bash
#!/usr/bin/env bash
# Runs web-test-runner with the given config, honoring the COMPONENTS env.
# When COMPONENTS is empty: --all. When set: loop --group per name.
# Pass empty string as config to run the default `npm test` (Chrome unit).
# Caller is responsible for `cd`-ing into web-components/ first.
set -euo pipefail
config="${1:-}"
config_arg=()
[ -n "$config" ] && config_arg=(--config "$config")
if [ -z "${COMPONENTS:-}" ]; then
  npm test -- "${config_arg[@]}" --all
else
  for c in $COMPONENTS; do
    npm test -- "${config_arg[@]}" --group "$c"
  done
fi
```

Each call site collapses from 9 lines to 1:

```yaml
- name: Snapshot tests
  working-directory: web-components
  env:
    COMPONENTS: ${{ inputs.components }}
  run: bash ../scripts/run-wtr-config.sh web-test-runner-snapshots.config.js
```

Used by six step bodies: snapshot tests + integration tests in `web-components-verify`, the Run step in `web-components-unit` (matrix supplies the config), and base/Lumo/Aura visual tests in `web-components-visual`.

For `web-components-unit`'s matrix, entries change from carrying full command strings to just the config filename (chrome's is empty):

```yaml
matrix:
  include:
    - browser: chrome
      config: ''
    - browser: firefox
      config: 'web-test-runner-firefox.config.js'
      playwright: firefox
    - browser: webkit
      config: 'web-test-runner-webkit.config.js'
      playwright: webkit
```

### Script: `scripts/check-results.sh` (new)

Replaces the trailing failure check in the `results` job. Takes `${{ toJson(needs) }}` as input, fails if any entry's `result` is `"failure"`:

```bash
#!/usr/bin/env bash
# Reads a JSON object describing needs results (from GHA's toJson(needs))
# and exits non-zero if any entry's `result` is "failure".
# `skipped` and `cancelled` are not treated as failures.
set -euo pipefail
needs_json="$1"
failed=$(echo "$needs_json" | jq -r '[to_entries[] | select(.value.result == "failure") | .key] | join(",")')
if [ -n "$failed" ]; then
  echo "Failed jobs: $failed"
  exit 1
fi
echo "All needed jobs succeeded or were skipped."
```

The `results` job's failure check collapses from 6 hand-maintained lines to:

```yaml
- name: Fail if any needed job failed
  if: always()
  run: bash scripts/check-results.sh '${{ toJson(needs) }}'
```

Any future job added to `results.needs:` is checked automatically. The existing dorny-step `outputs.conclusion` lines are removed: `needs.<job>.result` aggregates the job's outcome already, so the dorny-specific checks were redundant once a generic scan covers all of `needs`.

### Workflow-level `defaults`

At the top of `validation.yml`, after `concurrency`:

```yaml
defaults:
  run:
    shell: bash
```

Makes the shell explicit (currently relies on the GHA runner default, which is bash on Linux). Costs nothing; useful if a Windows runner is ever considered later.

### Refactored downstream-job shape

Before (`flow-components-wtr`, ~32 lines of body):

```yaml
flow-components-wtr:
  name: Flow Components WTR Tests
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
    - uses: actions/setup-java@v5
      with:
        java-version: '21'
        distribution: 'temurin'
        cache: 'maven'
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
          flow-components/vaadin-charts-flow-parent/...
        fail-on-cache-miss: true
    - run: bash scripts/sync-flow-overlays.sh
    - if: env.TB_LICENSE != ''
      run: |
        mkdir -p ~/.vaadin
        user="${TB_LICENSE%%/*}"
        key="${TB_LICENSE#*/}"
        echo "{\"username\":\"${user}\",\"proKey\":\"${key}\"}" > ~/.vaadin/proKey
    - name: Run WTR tests
      env:
        COMPONENTS: ${{ inputs.components }}
      run: cd flow-components && node scripts/wtr.js $COMPONENTS
```

After (~12 lines):

```yaml
flow-components-wtr:
  name: Flow Components WTR Tests
  needs: install
  runs-on: ubuntu-latest
  timeout-minutes: 30
  steps:
    - uses: ./.github/actions/setup-workspace
      with:
        cache-key: ${{ needs.install.outputs.cache-key }}
        setup-java: 'true'
        sync-overlays: 'true'
    - uses: ./.github/actions/install-tb-license
      with:
        tb-license: ${{ secrets.TB_LICENSE }}
    - name: Run WTR tests
      env:
        COMPONENTS: ${{ inputs.components }}
      run: cd flow-components && node scripts/wtr.js $COMPONENTS
```

Across all six downstream jobs, ~100 net lines saved. The cache `path:` list lives only in the composite.

### Gradle finalizer: web-components patches

`scripts/apply-web-components-patches.sh` (new):

```bash
#!/usr/bin/env bash
# Applies web-components/patches/*.patch to the workspace-root node_modules.
# Idempotent: `-N` skips already-applied hunks.
set -euo pipefail
cd "$(dirname "$0")/.."
for p in web-components/patches/*.patch; do
  echo "Applying $p"
  patch -p1 -d . -N < "$p"
done
```

`build.gradle.kts` adds:

```kotlin
val applyWebComponentsPatches = tasks.register<Exec>("applyWebComponentsPatches") {
    description = "Applies web-components/patches/*.patch to node_modules."
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
```

### Gradle finalizer: web-components bin symlink

`scripts/setup-web-components-bin.sh` (new):

```bash
#!/usr/bin/env bash
# Creates web-components/node_modules/.bin -> ../../node_modules/.bin
# so wtr-utils.js can resolve its hardcoded ./node_modules/.bin/lerna path.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p web-components/node_modules
ln -snf ../../node_modules/.bin web-components/node_modules/.bin
```

`build.gradle.kts` adds:

```kotlin
val symlinkWebComponentsBin = tasks.register<Exec>("symlinkWebComponentsBin") {
    description = "Creates web-components/node_modules/.bin symlink to hoisted root bin."
    group = "build"
    workingDir = rootDir
    commandLine("bash", "scripts/setup-web-components-bin.sh")
    inputs.dir("node_modules/.bin")
    outputs.file("web-components/node_modules/.bin")
}
```

### Gradle finalizer wiring

```kotlin
tasks.named("npmInstall") {
    finalizedBy(applyWebComponentsPatches, symlinkWebComponentsBin)
}
```

`finalizedBy` ensures the post-install tasks run whether `npmInstall` is invoked directly (`./gradlew npmInstall`) or transitively (`./gradlew install`). Neither task is `@Cacheable` — outputs are filesystem-specific (a relative symlink; patched files in `node_modules/`).

### `setup-gradle` in the install job

In `validation.yml`'s install job, immediately after `Setup Node` (the install job retains its inline setup steps; it's the cache-creating job and doesn't use the `setup-workspace` composite, which is for cache-restoring jobs):

```yaml
- uses: gradle/actions/setup-gradle@v5
```

Defaults cache `~/.gradle/wrapper/dists` (the Gradle distribution download) and `~/.gradle/caches/modules-2` (resolved plugin coordinates). Cache key is derived automatically from `gradle/wrapper/gradle-wrapper.properties` and the build files; cache invalidates correctly on Gradle bumps or plugin changes. Scoped to the install job only — downstream jobs don't run Gradle.

### Install-job step removals

The `install` job loses two steps (now folded into Gradle):

```yaml
- name: Apply web-components patches
  ...
- name: Symlink web-components/node_modules/.bin to workspace root
  ...
```

The cache key already includes `web-components/patches/**` in `hashFiles`, so a patch change still invalidates correctly.

### Spec doc update

`docs/superpowers/specs/2026-06-10-wc-validation-design.md` (the parent spec) currently has two narrative paragraphs in the §Shared Job Prelude region describing the patches-application and bin-symlink steps. Replace with a single sentence pointing at the Gradle finalizers in `build.gradle.kts`. The detailed rationale lives here in §Background.

## Verification

### Locally — Gradle integration

1. **Clean slate.** `./gradlew clean` removes `node_modules/`. Confirm `web-components/node_modules/` does not exist.
2. **Fresh install.** `./gradlew install` — both `applyWebComponentsPatches` and `symlinkWebComponentsBin` execute under the `build` group.
3. **Patched state.** `cat node_modules/@web/test-runner-visual-regression/index.d.ts` reads `export * from './browser/commands';` (no `.mjs`).
4. **Symlink correctness.** `readlink web-components/node_modules/.bin` prints `../../node_modules/.bin`.
5. **Downstream usage.** `cd web-components && npm run lint:types` exits 0; `cd web-components && npm test -- --config web-test-runner-it.config.js --all` runs through to completion.
6. **Idempotent re-run.** `./gradlew install` a second time. Both new tasks report `UP-TO-DATE`.
7. **Direct `npmInstall` invocation.** `./gradlew npmInstall` alone still triggers the finalizers.

### Locally — composite-action and script call sites

8. **YAML parses.** `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))"` exits 0.
9. **Composite-action YAML parses.** Same check on `.github/actions/setup-workspace/action.yml` and `.github/actions/install-tb-license/action.yml`.
10. **Scripts are executable and runnable.**
    - `bash scripts/run-wtr-config.sh web-test-runner-snapshots.config.js` (from `web-components/`, COMPONENTS unset) runs the snapshot suite.
    - `COMPONENTS="grid" bash scripts/run-wtr-config.sh web-test-runner-snapshots.config.js` runs only the grid group.
    - `bash scripts/check-results.sh '{"a":{"result":"success"},"b":{"result":"failure"}}'` exits 1 and reports `Failed jobs: b`.
    - `bash scripts/check-results.sh '{"a":{"result":"success"},"b":{"result":"skipped"}}'` exits 0.

### In CI (push to `ci/refactor`)

11. **Full pipeline green** with the composites in place, the two install steps removed, and the scripts in use.
12. **Per-job log inspection.** The first downstream job (e.g., `flow-components-wtr`) log shows the `setup-workspace` composite resolved and ran, with the install cache restored exactly once.
13. **Failure-check script.** Deliberately fail one job (e.g., introduce a temporary lint failure in `web-components-verify`). `results` reports `Failed jobs: web-components-verify` and exits 1; `Collect results` is red. Revert.
14. **`setup-gradle` cold/warm.** First run reports a `Setup Gradle` cache miss and downloads `gradle-8.10-bin.zip`. Second push (with `gradle-wrapper.properties` unchanged): cache hit, no download. Saves ~2-3s on warm runs.
15. **Install-cache hit on re-push.** Confirm the patches+symlink finalizer tasks are short-circuited via Gradle's UP-TO-DATE check when the cached `node_modules/` is restored.

## Future Work

- **Per-submodule `workflow_call` split.** Once the workspace gains a third submodule, factor each submodule's jobs into its own callable workflow. The composite-action refactor reduces the per-submodule file size enough that the split becomes a small per-submodule file rather than a big YAML duplication.
- **Pin actions to SHA via Dependabot.** With composite actions in place, the surface to update is small and well-defined. `.github/dependabot.yml` with `package-ecosystem: github-actions` would file PRs on action releases.
- **Generalize patches application across submodules.** If a future submodule also ships `patches/`, generalize the script to take a directory argument and register one task per submodule.
- **Make the bin symlink unnecessary.** File a PR upstream to `web-components/wtr-utils.js` replacing the hardcoded `./node_modules/.bin/lerna` with `require.resolve('lerna/dist/cli.js')` or equivalent. The symlink workaround can then be removed.
- **`restore-keys:` fallback for partial cache hits.** Add a less-specific prefix as a fallback to the install cache key — allows cross-PR partial sharing when exact inputs differ. Needs care to ensure the patched state survives a partial restore.

## References

- `.github/workflows/validation.yml` — the workflow this spec refactors.
- `/Users/antonplatonov/work/space/hilla/.github/actions/setup/action.yml` — the prior-art composite that inspired the `setup-workspace` shape.
- `gradle/flow-components.gradle.kts` — defines `syncFlowOverlays`, the existing pattern the new Gradle tasks mirror.
- `scripts/sync-flow-overlays.sh` — shape model for the new shell scripts.
- `.npmrc` (workspace root) — explains why `ignore-scripts=true` is set and why patches must be applied externally.
- `gradle/wrapper/gradle-wrapper.properties` — pins the Gradle distribution URL; `setup-gradle`'s cache key derives from this file.
- `gradle/actions/setup-gradle@v5` — Gradle's official action for caching the wrapper distribution and plugin metadata.
- `docs/superpowers/specs/2026-06-10-wc-validation-design.md` — the parent spec; this spec amends its narrative around the install job.
- `web-components/patches/` — the patches dir applied by the Gradle finalizer.
- `web-components/wtr-utils.js:59` — the hardcoded `./node_modules/.bin/lerna` lookup the symlink finalizer compensates for.
