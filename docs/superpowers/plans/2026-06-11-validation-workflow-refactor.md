# Validation Workflow Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `.github/workflows/validation.yml` to use two composite GitHub Actions and one shared shell script (eliminating ~100 lines of duplication), fold the two install-job postinstall steps into Gradle finalizers so `./gradlew install` is the canonical entry point, and cache the Gradle wrapper distribution and plugin metadata with a plain `actions/cache@v5` step. The TB license setup and the trailing failure check stay CI-side: TB license lives inline in its composite action (no separate script — license install is meaningless locally); the failure check is a 3-line inline jq scan over `toJson(needs)` in the results job.

**Architecture:** Three phases that can be executed in order without partial-state breakage: (1) Gradle integration — new shell scripts and Gradle Exec tasks for the postinstall fixes, then the CI workflow drops its two now-redundant steps. (2) Workflow refactor — new shared script, then composite actions, then per-job migrations, then inline-jq the results aggregation. (3) Wrapper caching + parent-spec narrative update + a small terminology cleanup (rename `scripts/overlay-component-names.sh` and fix two workflow strings that say "overlay" when they mean "components"). Each task ends with a commit; CI runs on every push and gates via `Collect results`.

**Tech Stack:**
- GitHub Actions composite actions (`.github/actions/<name>/action.yml`)
- Bash shell scripts (`scripts/*.sh`) following the existing `sync-flow-overlays.sh` shape
- Kotlin DSL Gradle build (`build.gradle.kts`) with `tasks.register<Exec>` and `finalizedBy`
- `actions/cache@v5` for the Gradle wrapper cache (`~/.gradle/wrapper/dists`, `~/.gradle/caches/modules-2`)
- `jq` (already present on GHA runners) for the failure-check script
- `patch -p1 -N` (system patch) for idempotent patch application

**Reference spec:** `docs/superpowers/specs/2026-06-11-validation-workflow-refactor-design.md`

**Branch:** `ci/refactor` (current; tip is `855ac5d` at plan-write time).

---

## Pre-flight check

Run from the workspace root:

```bash
git status                                       # clean tree on ci/refactor
git log --oneline -1                             # 855ac5d docs: use actions/cache@v5 ...
which jq && echo "jq ok"                         # required by check-results.sh
which patch && echo "patch ok"                   # required by apply-web-components-patches.sh
ls .github/actions/ 2>/dev/null && echo "actions dir exists" || echo "will be created"
gh auth status                                   # for the final PR push
```

If `~/.vaadin/proKey` is absent and you want to fully verify locally, materialize it with your TestBench license. CI uses the `TB_LICENSE` secret.

---

# Phase 1 — Gradle integration

## Task 1: Add `scripts/apply-web-components-patches.sh`

**Files:**
- Create: `scripts/apply-web-components-patches.sh`

- [ ] **Step 1: Write the script**

Create `scripts/apply-web-components-patches.sh` with this content:

```bash
#!/usr/bin/env bash
# Applies web-components/patches/*.patch to the workspace-root node_modules.
# Idempotent: `-N` skips already-applied hunks. Resolves the workspace root
# from the script's own location, so it can be run from any cwd.
set -euo pipefail
cd "$(dirname "$0")/.."
for p in web-components/patches/*.patch; do
  echo "Applying $p"
  patch -p1 -d . -N < "$p"
done
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x scripts/apply-web-components-patches.sh
```

- [ ] **Step 3: Smoke-test the script**

```bash
bash scripts/apply-web-components-patches.sh
```

Expected: prints `Applying web-components/patches/...` for each patch file (3 files); each `patch` invocation either applies forward cleanly or reports "Ignoring previously applied (or reversed) patch" and exits 0 (because `-N`). Whole script exits 0.

If exit is non-zero with a real (non-already-applied) error, investigate before continuing.

- [ ] **Step 4: Commit**

```bash
git add scripts/apply-web-components-patches.sh
git commit -m "$(cat <<'EOF'
chore(scripts): add apply-web-components-patches.sh

Applies web-components/patches/*.patch to the workspace-root
node_modules. Idempotent via patch -N. Mirrors the
sync-flow-overlays.sh script shape: locally executable, resolves
workspace root from its own location.

Will be invoked by a Gradle finalizer in the next commits.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Add `scripts/setup-web-components-bin.sh`

**Files:**
- Create: `scripts/setup-web-components-bin.sh`

- [ ] **Step 1: Write the script**

Create `scripts/setup-web-components-bin.sh` with:

```bash
#!/usr/bin/env bash
# Creates web-components/node_modules/.bin -> ../../node_modules/.bin
# so wtr-utils.js can resolve its hardcoded ./node_modules/.bin/lerna path
# from cwd=web-components/ under workspace hoisting.
# Idempotent: `ln -snf` overwrites/creates as needed.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p web-components/node_modules
ln -snf ../../node_modules/.bin web-components/node_modules/.bin
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x scripts/setup-web-components-bin.sh
```

- [ ] **Step 3: Smoke-test the script**

```bash
bash scripts/setup-web-components-bin.sh
readlink web-components/node_modules/.bin
```

Expected: second command prints `../../node_modules/.bin`. Script exits 0.

- [ ] **Step 4: Commit**

```bash
git add scripts/setup-web-components-bin.sh
git commit -m "$(cat <<'EOF'
chore(scripts): add setup-web-components-bin.sh

Creates web-components/node_modules/.bin -> ../../node_modules/.bin
so web-components/wtr-utils.js can resolve its hardcoded
./node_modules/.bin/lerna lookup from cwd=web-components/ under
workspace hoisting.

Will be invoked by a Gradle finalizer in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Register Gradle Exec tasks and finalizer wiring

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Inspect the current `build.gradle.kts`**

```bash
cat build.gradle.kts
```

Expected: the file currently has 53 lines, ending with the `tasks.named("clean")` block. The `npmInstall` task is defined around lines 20-30; no `finalizedBy` on it yet.

- [ ] **Step 2: Append the two new task registrations and the `finalizedBy` wiring**

Using Edit tooling on `build.gradle.kts`, replace this block:

`old_string`:
```kotlin
val npmInstall = tasks.named<NpmTask>("npmInstall") {
    description = "Installs all workspace npm dependencies."
    group = "build"
    dependsOn(":flow-components:syncFlowOverlays")
    // `ignore-scripts=true` is set in `.npmrc` at the workspace root; see the
    // comment there for why postinstall hooks must be skipped.
    inputs.file("package.json")
    inputs.file("package-lock.json")
    inputs.file("flow-components/package.json")
    outputs.dir("node_modules")
}
```

`new_string`:
```kotlin
val npmInstall = tasks.named<NpmTask>("npmInstall") {
    description = "Installs all workspace npm dependencies."
    group = "build"
    dependsOn(":flow-components:syncFlowOverlays")
    // `ignore-scripts=true` is set in `.npmrc` at the workspace root; see the
    // comment there for why postinstall hooks must be skipped.
    inputs.file("package.json")
    inputs.file("package-lock.json")
    inputs.file("flow-components/package.json")
    outputs.dir("node_modules")
}

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

npmInstall.configure {
    finalizedBy(applyWebComponentsPatches, symlinkWebComponentsBin)
}
```

- [ ] **Step 3: Verify the Gradle config compiles**

```bash
./gradlew help --no-daemon 2>&1 | tail -10
```

Expected: prints the help text (or just exits 0). If you see a Kotlin compilation error, the edit didn't land cleanly — re-inspect.

- [ ] **Step 4: Verify the tasks are visible**

```bash
./gradlew tasks --group build --no-daemon 2>&1 | grep -E "applyWebComponentsPatches|symlinkWebComponentsBin"
```

Expected: both task names appear in the output, with their description text.

- [ ] **Step 5: Run a fresh install and confirm the finalizers fire**

```bash
./gradlew install --no-daemon 2>&1 | tail -30
```

Expected: the tail shows `applyWebComponentsPatches` and `symlinkWebComponentsBin` running (not UP-TO-DATE on the first run since no prior fingerprint exists). `BUILD SUCCESSFUL`.

- [ ] **Step 6: Verify the post-install state**

```bash
cat node_modules/@web/test-runner-visual-regression/index.d.ts
readlink web-components/node_modules/.bin
```

Expected:
- First command prints `export * from './browser/commands';` (patched, no `.mjs`).
- Second command prints `../../node_modules/.bin`.

- [ ] **Step 7: Verify idempotency on re-run**

```bash
./gradlew install --no-daemon 2>&1 | tail -10
```

Expected: both `applyWebComponentsPatches` and `symlinkWebComponentsBin` report `UP-TO-DATE`.

- [ ] **Step 8: Verify direct `npmInstall` invocation triggers the finalizers**

```bash
./gradlew --rerun-tasks npmInstall --no-daemon 2>&1 | tail -15
```

Expected: `npmInstall` runs, then both finalizer tasks run.

- [ ] **Step 9: Commit**

```bash
git add build.gradle.kts
git commit -m "$(cat <<'EOF'
build: fold web-components postinstall steps into Gradle finalizers

applyWebComponentsPatches and symlinkWebComponentsBin run as
finalizers on npmInstall. ./gradlew install (or ./gradlew
npmInstall alone) now leaves web-components fully set up — no
post-install shell commands needed by either local devs or CI.

Both tasks are plain Exec wrappers around shell scripts in
scripts/, mirroring the existing syncFlowOverlays pattern.
inputs/outputs declarations enable Gradle's UP-TO-DATE check; the
tasks short-circuit on re-runs against an unchanged workspace.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Remove the two now-redundant steps from `validation.yml` install job

**Files:**
- Modify: `.github/workflows/validation.yml` (install job — remove two steps)

- [ ] **Step 1: Remove the patches step**

Using Edit tooling on `.github/workflows/validation.yml`:

`old_string`:
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

      - name: Save install cache
```

`new_string`:
```yaml
      - name: Save install cache
```

This collapses the two steps into nothing — the install job now flows directly from `Workspace install` (which now runs the Gradle finalizers transparently) into `Save install cache`.

- [ ] **Step 2: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

Expected: `yaml ok`.

- [ ] **Step 3: Inspect the diff**

```bash
git diff .github/workflows/validation.yml
```

Expected: a single hunk removing two step blocks (patches + symlink). No other changes.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "$(cat <<'EOF'
ci: drop redundant postinstall steps; Gradle finalizers handle them

The Apply web-components patches and Symlink
web-components/node_modules/.bin steps in the install job are now
redundant — applyWebComponentsPatches and symlinkWebComponentsBin
Gradle finalizers on npmInstall do the same work as part of
./gradlew install. Removing the YAML steps keeps the workflow
focused on orchestration.

Cache-key inputs already include web-components/patches/**, so a
patch change still invalidates the install cache correctly.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 2 — Workflow refactor

## Task 5: Add `scripts/run-wtr-config.sh`

**Files:**
- Create: `scripts/run-wtr-config.sh`

- [ ] **Step 1: Write the script**

Create `scripts/run-wtr-config.sh`:

```bash
#!/usr/bin/env bash
# Runs web-test-runner (via npm test) with the given config file, honoring
# the COMPONENTS env var. When COMPONENTS is empty: --all. When set: loop
# --group per name. Pass an empty string as the config argument to invoke
# the default Chrome unit suite (no --config).
# Caller is responsible for `cd`-ing into web-components/ before running.
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

- [ ] **Step 2: Make it executable**

```bash
chmod +x scripts/run-wtr-config.sh
```

- [ ] **Step 3: Smoke-test the empty-config (Chrome) path**

Verify the script invokes `npm test` correctly (don't wait for the actual test run; ctrl-c is fine):

```bash
cd web-components && bash -x ../scripts/run-wtr-config.sh '' 2>&1 | head -3
cd ..
```

Expected: trace output shows the script resolving `config_arg=()` and calling `npm test -- --all`. Don't worry about test outcome.

- [ ] **Step 4: Smoke-test the with-config path**

```bash
cd web-components && bash -x ../scripts/run-wtr-config.sh web-test-runner-snapshots.config.js 2>&1 | head -3
cd ..
```

Expected: trace shows `npm test -- --config web-test-runner-snapshots.config.js --all`.

- [ ] **Step 5: Commit**

```bash
git add scripts/run-wtr-config.sh
git commit -m "$(cat <<'EOF'
chore(scripts): add run-wtr-config.sh

Extracts the COMPONENTS-loop pattern that the wc-verify, wc-unit,
and wc-visual steps each duplicate inline. One config argument
selects the web-test-runner config (empty string = default Chrome
suite); the COMPONENTS env var toggles --all vs per-group looping.
Caller cd's into web-components/ first.

Will replace the inline bash blocks in the workflow in the next
commits.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Add the `setup-workspace` composite action

**Files:**
- Create: `.github/actions/setup-workspace/action.yml`

- [ ] **Step 1: Create the directory**

```bash
mkdir -p .github/actions/setup-workspace
```

- [ ] **Step 2: Write the composite action**

Create `.github/actions/setup-workspace/action.yml`:

```yaml
name: Setup workspace
description: Common workspace prelude — checkout, Node/Java setup, install cache restore, optional overlay sync.

inputs:
  cache-key:
    description: Install cache key; pass needs.install.outputs.cache-key from the caller.
    required: true
  fetch-depth:
    description: git fetch-depth for the checkout step.
    required: false
    default: '1'
  setup-java:
    description: Set to 'true' to set up JDK 21 with Maven cache (for jobs that run Maven).
    required: false
    default: 'false'
  sync-overlays:
    description: Set to 'true' to run scripts/sync-flow-overlays.sh after cache restore.
    required: false
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

- [ ] **Step 3: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/actions/setup-workspace/action.yml'))" && echo "yaml ok"
```

Expected: `yaml ok`.

- [ ] **Step 4: Commit**

```bash
git add .github/actions/setup-workspace/action.yml
git commit -m "$(cat <<'EOF'
ci: add setup-workspace composite action

Bundles the per-job checkout + setup-node + (optional) setup-java
+ install cache restore + (optional) overlay sync. The 6-path
cache path: list lives here exclusively — any future change to
cached paths touches one file instead of eight.

Inputs:
- cache-key (required): from needs.install.outputs.cache-key.
- fetch-depth (default 1): submodule clone depth.
- setup-java (default false): JDK 21 + Maven cache for flow-* jobs.
- sync-overlays (default false): runs sync-flow-overlays.sh.

Will be consumed by all six downstream jobs in subsequent commits.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Add the `install-tb-license` composite action

**Files:**
- Create: `.github/actions/install-tb-license/action.yml`

- [ ] **Step 1: Create the directory**

```bash
mkdir -p .github/actions/install-tb-license
```

- [ ] **Step 2: Write the composite action**

Create `.github/actions/install-tb-license/action.yml`:

```yaml
name: Install TestBench license
description: Writes ~/.vaadin/proKey from the TB_LICENSE secret. Skips silently when the input is empty (e.g. fork PRs without the secret).

inputs:
  tb-license:
    description: TB_LICENSE secret value formatted as user/key.
    required: true

runs:
  using: composite
  steps:
    - if: inputs.tb-license != ''
      shell: bash
      env:
        TB_LICENSE: ${{ inputs.tb-license }}
      run: |
        mkdir -p ~/.vaadin
        user="${TB_LICENSE%%/*}"
        key="${TB_LICENSE#*/}"
        printf '{"username":"%s","proKey":"%s"}\n' "$user" "$key" > ~/.vaadin/proKey
```

Bash is inline in the composite (no separate `scripts/install-tb-license.sh`): TB license setup is meaningless locally — a developer already has their own `~/.vaadin/proKey` — so the indirection of a separate shell script earns nothing. The secret passes via `env:` rather than direct `${{ }}` interpolation in `run:`, which keeps the value out of the rendered step log.

- [ ] **Step 3: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/actions/install-tb-license/action.yml'))" && echo "yaml ok"
```

Expected: `yaml ok`.

- [ ] **Step 4: Commit**

```bash
git add .github/actions/install-tb-license/action.yml
git commit -m "$(cat <<'EOF'
ci: add install-tb-license composite action

Encapsulates the ~/.vaadin/proKey write that flow-components-wtr
and flow-components-its both need. Bash is inline because TB
license install is a pure CI concern; no separate shell script
to maintain. Secret passed via env rather than direct
interpolation to keep it out of the rendered step log.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Add workflow-level `defaults: run: shell: bash`

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Insert the defaults block**

Using Edit tooling on `.github/workflows/validation.yml`:

`old_string`:
```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
```

`new_string`:
```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

defaults:
  run:
    shell: bash

jobs:
```

- [ ] **Step 2: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

Expected: `yaml ok`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "$(cat <<'EOF'
ci: set workflow-level default shell to bash

Makes the shell choice explicit. GHA runners default to bash on
Linux today, but pinning here removes the implicit assumption and
keeps any future Windows-runner consideration from scattering
shell: bash across step bodies.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Migrate `flow-components-unit` to use the composite

**Files:**
- Modify: `.github/workflows/validation.yml` (replace the `flow-components-unit` setup prelude)

- [ ] **Step 1: Replace the prelude**

Using Edit tooling:

`old_string`:
```yaml
  flow-components-unit:
    name: Flow Components Unit Tests
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 30
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
            flow-components/**/node_modules
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
          fail-on-cache-miss: true

      - name: Sync overlay symlinks
        run: bash scripts/sync-flow-overlays.sh

      - name: Run unit tests
```

`new_string`:
```yaml
  flow-components-unit:
    name: Flow Components Unit Tests
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: ./.github/actions/setup-workspace
        with:
          cache-key: ${{ needs.install.outputs.cache-key }}
          setup-java: 'true'
          sync-overlays: 'true'

      - name: Run unit tests
```

- [ ] **Step 2: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "ci: migrate flow-components-unit to setup-workspace composite

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Migrate `flow-components-wtr` to composites

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Replace the prelude and TB license install**

Using Edit tooling:

`old_string`:
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
            flow-components/**/node_modules
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

      - name: Run WTR tests
```

`new_string`:
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
```

Note: the `env: TB_LICENSE:` block is removed because the composite action takes the secret directly via input.

- [ ] **Step 2: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "ci: migrate flow-components-wtr to composite actions

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Migrate `flow-components-its` to composites

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Replace the prelude and TB license install**

Using Edit tooling:

`old_string`:
```yaml
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
            flow-components/**/node_modules
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
```

`new_string`:
```yaml
    steps:
      - uses: ./.github/actions/setup-workspace
        with:
          cache-key: ${{ needs.install.outputs.cache-key }}
          setup-java: 'true'
          sync-overlays: 'true'

      - uses: ./.github/actions/install-tb-license
        with:
          tb-license: ${{ secrets.TB_LICENSE }}

      - name: Compute artifact shard id
```

- [ ] **Step 2: Remove the now-unused `env: TB_LICENSE:` job-level block**

Find the block in the `flow-components-its` job:

```yaml
    env:
      TB_LICENSE: ${{ secrets.TB_LICENSE }}
```

Verify the line numbers around it via:

```bash
grep -n "env:" .github/workflows/validation.yml | head -10
```

Use Edit to remove the two lines. The exact surrounding text (use this `old_string`):

```yaml
    timeout-minutes: 120
    env:
      TB_LICENSE: ${{ secrets.TB_LICENSE }}
    strategy:
      fail-fast: false
      matrix: ${{ fromJson(needs.install.outputs.it-matrix) }}
```

`new_string`:
```yaml
    timeout-minutes: 120
    strategy:
      fail-fast: false
      matrix: ${{ fromJson(needs.install.outputs.it-matrix) }}
```

- [ ] **Step 3: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "ci: migrate flow-components-its to composite actions

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: Migrate `web-components-verify` to composite + run-wtr-config

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Replace the prelude**

Using Edit tooling:

`old_string`:
```yaml
  web-components-verify:
    name: Web Components Verify
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v6
        with:
          submodules: recursive
          # depth-1 is sufficient: `npm test -- --all` short-circuits the
          # lerna-based `getChangedPackages()` in web-components/wtr-utils.js.
          fetch-depth: 1

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
            flow-components/**/node_modules
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
          fail-on-cache-miss: true

      - name: Lint
```

`new_string`:
```yaml
  web-components-verify:
    name: Web Components Verify
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: ./.github/actions/setup-workspace
        with:
          cache-key: ${{ needs.install.outputs.cache-key }}

      - name: Lint
```

- [ ] **Step 2: Replace the Snapshot tests step body**

`old_string`:
```yaml
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
```

`new_string`:
```yaml
      - name: Snapshot tests
        working-directory: web-components
        env:
          COMPONENTS: ${{ inputs.components }}
        run: bash ../scripts/run-wtr-config.sh web-test-runner-snapshots.config.js
```

- [ ] **Step 3: Replace the Integration tests step body**

`old_string`:
```yaml
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

`new_string`:
```yaml
      - name: Integration tests
        working-directory: web-components
        env:
          COMPONENTS: ${{ inputs.components }}
        run: bash ../scripts/run-wtr-config.sh web-test-runner-it.config.js
```

- [ ] **Step 4: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "ci: migrate web-components-verify to composite + run-wtr-config

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Migrate `web-components-unit` (matrix change + Run step)

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Change the matrix to carry `config` instead of `cmd`**

Using Edit tooling:

`old_string`:
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
      - uses: actions/checkout@v6
        with:
          submodules: recursive
          # depth-1 is sufficient: `npm test -- --all` short-circuits the
          # lerna-based `getChangedPackages()` in web-components/wtr-utils.js.
          fetch-depth: 1

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
            flow-components/**/node_modules
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
          fail-on-cache-miss: true

      - name: Cache Playwright browsers
```

`new_string`:
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
            config: ''
          - browser: firefox
            config: 'web-test-runner-firefox.config.js'
            playwright: firefox
          - browser: webkit
            config: 'web-test-runner-webkit.config.js'
            playwright: webkit
    steps:
      - uses: ./.github/actions/setup-workspace
        with:
          cache-key: ${{ needs.install.outputs.cache-key }}

      - name: Cache Playwright browsers
```

- [ ] **Step 2: Replace the Run unit tests step body**

`old_string`:
```yaml
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

`new_string`:
```yaml
      - name: Run unit tests
        working-directory: web-components
        env:
          COMPONENTS: ${{ inputs.components }}
        run: bash ../scripts/run-wtr-config.sh "${{ matrix.config }}"
```

- [ ] **Step 3: Validate YAML and matrix shape**

```bash
python3 -c "import yaml; wf=yaml.safe_load(open('.github/workflows/validation.yml')); print('yaml ok'); [print(' ', e) for e in wf['jobs']['web-components-unit']['strategy']['matrix']['include']]"
```

Expected:
```
yaml ok
  {'browser': 'chrome', 'config': ''}
  {'browser': 'firefox', 'config': 'web-test-runner-firefox.config.js', 'playwright': 'firefox'}
  {'browser': 'webkit', 'config': 'web-test-runner-webkit.config.js', 'playwright': 'webkit'}
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "ci: migrate web-components-unit to composite + run-wtr-config

Matrix now carries 'config' (config filename) instead of 'cmd'
(full command). The shared run-wtr-config.sh handles the empty-
string case for Chrome (no --config) uniformly with the firefox/
webkit case.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: Migrate `web-components-visual` to composite + run-wtr-config

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Replace the prelude (after the container setup steps)**

Using Edit tooling:

`old_string`:
```yaml
    steps:
      - uses: actions/checkout@v6
        with:
          submodules: recursive
          # depth-1 is sufficient: `npm test -- --all` short-circuits the
          # lerna-based `getChangedPackages()` in web-components/wtr-utils.js.
          # The explicit `git fetch origin main` below populates origin/main.
          fetch-depth: 1

      - name: Fix git safe directory
        run: git config --global --add safe.directory $GITHUB_WORKSPACE

      - name: Fetch origin/main
        run: git fetch origin main

      - name: Setup Node
        uses: actions/setup-node@v6
        with:
          node-version: '24'

      - name: Install zstd
        run: apt-get update && apt-get install -y zstd

      - name: Restore install cache
        uses: actions/cache/restore@v5
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
```

`new_string`:
```yaml
    steps:
      - name: Install zstd
        run: apt-get update && apt-get install -y zstd

      - uses: ./.github/actions/setup-workspace
        with:
          cache-key: ${{ needs.install.outputs.cache-key }}

      - name: Fix git safe directory
        run: git config --global --add safe.directory $GITHUB_WORKSPACE

      - name: Fetch origin/main
        run: git fetch origin main

      - name: Visual tests — base
```

Note: `Install zstd` moves to the very first step (before checkout) because the cache restore inside the composite needs `zstd`. The `Fix git safe directory` step has to come after the composite's checkout (otherwise the post-checkout git operations fail in the container).

- [ ] **Step 2: Replace the three Visual tests step bodies**

Edit 1 — base:

`old_string`:
```yaml
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
```

`new_string`:
```yaml
      - name: Visual tests — base
        uses: nick-fields/retry@v3
        with:
          timeout_minutes: 20
          retry_wait_seconds: 60
          max_attempts: 3
          command: cd web-components && bash ../scripts/run-wtr-config.sh web-test-runner-base.config.js
        env:
          COMPONENTS: ${{ inputs.components }}
```

Edit 2 — Lumo (`old_string` is identical shape with `lumo` substituted):

`old_string`:
```yaml
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
```

`new_string`:
```yaml
      - name: Visual tests — Lumo
        uses: nick-fields/retry@v3
        with:
          timeout_minutes: 20
          retry_wait_seconds: 60
          max_attempts: 3
          command: cd web-components && bash ../scripts/run-wtr-config.sh web-test-runner-lumo.config.js
        env:
          COMPONENTS: ${{ inputs.components }}
```

Edit 3 — Aura:

`old_string`:
```yaml
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
```

`new_string`:
```yaml
      - name: Visual tests — Aura
        uses: nick-fields/retry@v3
        with:
          timeout_minutes: 20
          retry_wait_seconds: 60
          max_attempts: 3
          command: cd web-components && bash ../scripts/run-wtr-config.sh web-test-runner-aura.config.js
        env:
          COMPONENTS: ${{ inputs.components }}
```

- [ ] **Step 3: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "ci: migrate web-components-visual to composite + run-wtr-config

Install zstd moves to the very first step (before checkout) so the
cache restore inside the composite can extract zstd-compressed
archives. The three Visual tests step bodies each collapse from 9
lines to 1 line that calls run-wtr-config.sh.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 15: Inline-jq the `results` failure check

**Files:**
- Modify: `.github/workflows/validation.yml` (replace the trailing failure check in the `results` job)

- [ ] **Step 1: Replace the trailing check**

Using Edit tooling:

`old_string`:
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

`new_string`:
```yaml
      - name: Fail if any needed job failed
        if: always()
        run: |
          failed=$(echo '${{ toJson(needs) }}' | jq -r '[to_entries[] | select(.value.result == "failure") | .key] | join(",")')
          [ -z "$failed" ] || { echo "Failed jobs: $failed"; exit 1; }
```

- [ ] **Step 2: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "$(cat <<'EOF'
ci: aggregate needs results via inline jq over toJson(needs)

Replaces the 6-line hand-maintained failure check with a 3-line
inline jq scan. Any future job added to results.needs is checked
automatically — no second edit required. The dorny-step
outputs.conclusion checks were redundant once needs.<job>.result
(which aggregates the full job outcome including publish steps)
is consulted. skipped and cancelled are not treated as failures,
matching prior semantics (e.g. fork PRs that skip
web-components-visual keep Collect results green).

Bash stays inline (no scripts/check-results.sh) because the
check is a pure CI concern — it consumes toJson(needs), an
expression that only exists inside a running workflow.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 3 — Gradle wrapper caching + parent-spec narrative

## Task 16: Add `Cache Gradle` step to the install job

**Files:**
- Modify: `.github/workflows/validation.yml`

- [ ] **Step 1: Insert the cache step**

Using Edit tooling:

`old_string`:
```yaml
      - name: Setup Node
        uses: actions/setup-node@v6
        with:
          node-version: '24'

      - name: Compute cache key
```

`new_string`:
```yaml
      - name: Setup Node
        uses: actions/setup-node@v6
        with:
          node-version: '24'

      - name: Cache Gradle
        uses: actions/cache@v5
        with:
          path: |
            ~/.gradle/wrapper/dists
            ~/.gradle/caches/modules-2
          key: ${{ runner.os }}-gradle-${{ hashFiles('gradle/wrapper/gradle-wrapper.properties', 'build.gradle.kts', 'settings.gradle.kts', 'gradle/*.gradle.kts') }}
          restore-keys: |
            ${{ runner.os }}-gradle-

      - name: Compute cache key
```

- [ ] **Step 2: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "$(cat <<'EOF'
ci: cache Gradle wrapper distribution and plugin metadata

Adds a plain actions/cache@v5 step in the install job that
caches ~/.gradle/wrapper/dists and ~/.gradle/caches/modules-2.
Cache key derives from gradle-wrapper.properties and the Gradle
build files; restore-keys allows partial reuse on plugin drift.

Eliminates the ~2-3s per-run download of gradle-8.10-bin.zip
without bringing in gradle/actions/setup-gradle (whose v6+
caching component requires accepting proprietary Gradle Inc.
Terms of Use, and whose v5 caching is more elaborate than this
single-shot install job needs).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 17: Trim parent spec narrative

**Files:**
- Modify: `docs/superpowers/specs/2026-06-10-wc-validation-design.md`

- [ ] **Step 1: Locate the patches and bin-symlink narrative**

```bash
grep -n "The install job applies the patches\|The install job applies \`web-components/patches/\`\|Immediately after the patches step, the install job creates the symlink" docs/superpowers/specs/2026-06-10-wc-validation-design.md
```

Expected: three line-number matches. These are paragraphs in the §Shared Job Prelude region.

- [ ] **Step 2: Replace the three paragraphs with a one-sentence pointer**

Using Edit tooling. The exact `old_string` is the three consecutive paragraphs:

`old_string`:
```
The install job applies `web-components/patches/` after `./gradlew install`. The workspace root's `.npmrc` sets `ignore-scripts=true` (because web-components' `postinstall` runs `patch-package` against its own `node_modules`, which doesn't exist when devDeps are hoisted to the workspace root). The patches are still required — at minimum, `@web+test-runner-visual-regression+0.10.0.patch` rewrites a `.mjs` extension in `index.d.ts` that TypeScript's `bundler` module resolution refuses to follow, breaking `lint:types`.

The install job applies the patches with the system `patch` binary rather than `patch-package`, because `patch-package` 8.x hardcodes its target to `<cwd>/node_modules` — running it from the workspace root muddles the attribution (patch-package is a `web-components` devDependency), and running it from `web-components/` fails outright because the hoisted `node_modules` lives one level up. The workflow loops over `web-components/patches/*.patch` with `working-directory: web-components` and `patch -p1 -d .. < "$p"`. The `-d ..` directs patch at the workspace root, where `node_modules/@web/...` actually lives. The patched state lands in the install cache and every downstream job consumes it.

Immediately after the patches step, the install job creates the symlink `web-components/node_modules/.bin -> ../../node_modules/.bin`. `web-components/wtr-utils.js` (which is loaded at config-evaluation time by `web-test-runner-it.config.js` and the visual configs) hardcodes the path `./node_modules/.bin/lerna` and shells out to it via `execSync`. From `cwd=web-components/` that path resolves to `web-components/node_modules/.bin/lerna`, which under workspace hoisting is missing. The symlink restores the lookup transparently — `wtr-utils.js`'s `getChangedPackages()` call resolves `./node_modules/.bin/lerna` through the link to the hoisted root binary. The symlink is part of the cached `web-components/node_modules` path, so it persists into every downstream job.
```

`new_string`:
```
The `./gradlew install` step handles both web-components patch application and the `web-components/node_modules/.bin → ../../node_modules/.bin` symlink as Gradle finalizers on `npmInstall`. See `build.gradle.kts` and `docs/superpowers/specs/2026-06-11-validation-workflow-refactor-design.md` §Background for the rationale (workspace hoisting + `ignore-scripts=true` requires re-applying web-components' patches; `wtr-utils.js` hardcodes a relative lerna path that breaks under hoisting without the symlink).
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-06-10-wc-validation-design.md
git commit -m "$(cat <<'EOF'
docs: trim parent spec; patches/symlink rationale moves to refactor spec

The install-job patches and symlink steps are no longer in the
workflow — they're Gradle finalizers now. Replaces the three
narrative paragraphs with a one-sentence pointer to
build.gradle.kts and the refactor spec where the rationale lives.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 4 — Push and verify on CI

## Task 18: Terminology cleanup — `overlay` → `components` where misused

**Files:**
- Rename: `scripts/overlay-component-names.sh` → `scripts/component-names.sh`
- Modify: `scripts/component-names.sh` (docstring + error message)
- Modify: `.github/workflows/validation.yml` (input description, echo, script reference)

- [ ] **Step 1: Rename the script via git**

```bash
git mv scripts/overlay-component-names.sh scripts/component-names.sh
```

- [ ] **Step 2: Update the script's docstring and error message**

Using Edit tooling on `scripts/component-names.sh`:

`old_string`:
```
# Emits a space-separated list of overlay short component names to stdout.
#
# Discovers overlays by scanning the overlay directory for every
# vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/package.json
# and extracting <name>.
#
# Env overrides:
#   COMPONENTS  — space-separated short names to keep (e.g. "grid date-picker")
#
# First positional argument overrides the overlay directory
# (default flow-components-overlay).
```

`new_string`:
```
# Emits a space-separated list of component short names to stdout.
#
# Discovers components by scanning flow-components-overlay/ for every
# vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/package.json
# and extracting <name>.
#
# Env overrides:
#   COMPONENTS  — space-separated short names to keep (e.g. "grid date-picker")
#
# First positional argument overrides the component overlay directory
# (default flow-components-overlay).
```

Then update the error message — `old_string`:
```bash
  echo "::error::Overlay directory not found at $SOURCE_DIR" >&2
```

`new_string`:
```bash
  echo "::error::Component overlay directory not found at $SOURCE_DIR" >&2
```

Then update the two inline comments — `old_string`:
```bash
# Discover all overlay short names, sorted alphabetically. nullglob lets the
# loop skip cleanly when no overlays exist.
```

`new_string`:
```bash
# Discover all component short names, sorted alphabetically. nullglob lets the
# loop skip cleanly when no components exist.
```

- [ ] **Step 3: Update the workflow's input description**

Using Edit tooling on `.github/workflows/validation.yml`:

`old_string`:
```yaml
        description: 'Space-separated names (e.g. "grid combo-box"), empty = all overlay modules'
```

`new_string`:
```yaml
        description: 'Space-separated names (e.g. "grid combo-box"), empty = all component modules'
```

- [ ] **Step 4: Update the script reference and echo in the install job**

Using Edit tooling. `old_string`:
```yaml
          names=$(COMPONENTS="$COMPONENTS" bash scripts/overlay-component-names.sh)
          echo "Overlay names: $names"
```

`new_string`:
```yaml
          names=$(COMPONENTS="$COMPONENTS" bash scripts/component-names.sh)
          echo "Component names: $names"
```

- [ ] **Step 5: Verify no other references to the old script name remain**

```bash
grep -rn "overlay-component-names" .github scripts docs 2>&1
```

Expected: no output. If anything matches, update it.

- [ ] **Step 6: Verify YAML still parses**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

- [ ] **Step 7: Sanity-check the renamed script still works**

```bash
bash scripts/component-names.sh | head -1
```

Expected: a space-separated list of component short names (e.g. `accordion app-layout aura-theme avatar …`).

- [ ] **Step 8: Commit**

```bash
git add scripts/component-names.sh .github/workflows/validation.yml
git commit -m "$(cat <<'EOF'
refactor: rename overlay-component-names.sh → component-names.sh

The word "overlay" had leaked into user-visible workflow strings
(workflow_dispatch input description, "Overlay names:" echo) and
into the script name itself, even though the noun being narrowed
by the components: input is a component, not an overlay. The
flow-components-overlay/ directory stays (it's a genuine
overlay), but the script that *reads* from it to produce component
names is now named for its output.

Also updates the script's docstring and error message to match.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 19: Push branch and verify CI green

**Files:** (no source changes; verification only)

- [ ] **Step 1: Review the full commit stack**

```bash
git log --oneline main..HEAD
```

Expected: the prior history plus 19 new commits from this plan's Tasks 1-19. Scan the messages to confirm the order and nothing landed out of sequence.

- [ ] **Step 2: Push**

```bash
git push 2>&1
```

- [ ] **Step 3: Watch the run**

```bash
gh pr checks --watch 2>&1
```

(If no PR exists for `ci/refactor` yet, open one first with `gh pr create --base main --head ci/refactor --title "ci: validation.yml refactor + Gradle integration" --body "See docs/superpowers/specs/2026-06-11-validation-workflow-refactor-design.md"`.)

- [ ] **Step 4: Confirm all 16 checks pass**

The expected check list (from prior runs):

- Install
- Flow Components Unit Tests
- Flow Components WTR Tests
- Flow Components IT 1/6 … 6/6
- Web Components Verify
- Web Components Unit (chrome / firefox / webkit)
- Web Components Visual
- Collect results
- license/cla

If any check fails, drill into the run log via `gh run view <id> --log --job <job-id>`. Common things to check:

- **Composite action not found:** the path in `uses: ./.github/actions/<name>` must match the directory name exactly (`setup-workspace`, `install-tb-license`).
- **Cache restore fails:** verify the install cache step still saves under the same key the composite expects.
- **`scripts/run-wtr-config.sh: command not found`:** ensure the script is executable (`git ls-files --stage scripts/run-wtr-config.sh` should show mode `100755`). If it's `100644`, run `chmod +x` and re-commit.
- **Inline jq failure check false positive:** confirm `jq` is on the runner image (it is on `ubuntu-latest`).
- **Gradle cache cold/warm:** on the first push the cache misses and Gradle downloads `gradle-8.10-bin.zip`. On the second push, log line `Cache restored from key: Linux-gradle-...` should appear.

- [ ] **Step 5: Report status**

Tell the user the PR URL and confirm green. If any check is red, link the failing job's run log and pause for direction.

---

## Self-review

Cross-checked against `docs/superpowers/specs/2026-06-11-validation-workflow-refactor-design.md`:

- §Goal 1 (reduce duplication) — Tasks 6–15 (composites + per-job migrations + results aggregation).
- §Goal 2 (shared bash to scripts) — Task 5 (`run-wtr-config.sh`) + the per-job migrations that consume it. TB license setup and the results failure check intentionally stay CI-side (inline in composite / inline in the workflow) rather than as separate scripts; see Tasks 7 and 15 for the rationale.
- §Goal 3 (prepare for more submodules) — implicit: the composite shape is parameterized for arbitrary submodule jobs.
- §Goal 4 (Gradle finalizers) — Tasks 1–4.
- §Goal 5 (Gradle cache) — Task 16.
- §`setup-workspace` composite — Task 6.
- §`install-tb-license` composite (inline bash) — Task 7.
- §`scripts/run-wtr-config.sh` — Task 5.
- §Inline jq failure check — Task 15.
- §Workflow-level `defaults` — Task 8.
- §Refactored downstream-job shape (before/after for `flow-components-wtr`) — implemented in Task 10.
- §Gradle finalizer: patches — Tasks 1, 3.
- §Gradle finalizer: bin symlink — Tasks 2, 3.
- §`finalizedBy` wiring — Task 3.
- §`Cache Gradle` step — Task 16.
- §Install-job step removals — Task 4.
- §Spec doc update — Task 17.
- §Terminology cleanup — Task 18.
- §Verification — Task 19 (CI) + intra-task local smoke tests on Tasks 1, 2, 3, 5, 18.

No placeholders. Every YAML edit shows exact `old_string`/`new_string`. Every script body is shown in full. Every commit message is provided. All script names match between definition (Tasks 1, 2, 5, 6, 7) and consumption (Tasks 3, 9, 14, 15, 16, 17).
