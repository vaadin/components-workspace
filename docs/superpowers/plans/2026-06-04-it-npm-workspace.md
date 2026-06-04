# Flow IT npm Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an npm workspace at the components-workspace root so 4 pilot flow-components IT modules can consume `@vaadin/*` packages directly from the local `web-components/` submodule via `file:` URLs and symlinked overlay `package.json` files.

**Architecture:** Real per-IT-module `package.json` files live in a workspace-tracked `flow-components-overlay/` tree. An idempotent shell script materializes them as symlinks inside the submodule paths — where the submodule's own committed `.gitignore` (`**/package*json`) already covers them. The workspace root has an npm `package.json` with workspace globs over `web-components/packages/*` and `flow-components-overlay/*/*`. `./gradlew install` runs the sync script and then `npm install` at the workspace root.

**Tech Stack:**
- npm 9+ (system Node provides it)
- Bash (sync script, no Node dependency for the script itself)
- Gradle Kotlin DSL (existing from the previous spec)
- `com.github.node-gradle.node` plugin (already applied to `:web-components`; this plan extends it to the root)

**Reference spec:** `docs/superpowers/specs/2026-06-04-it-npm-workspace-design.md`

**Dependency lists** (derived from grepping `@NpmPackage` in the `*-flow` module Java sources of each pilot — exact, ready-to-paste):

| IT module | Direct deps |
|---|---|
| `vaadin-button-flow-integration-tests` | `@vaadin/button` |
| `vaadin-grid-flow-integration-tests` | `@vaadin/grid`, `@vaadin/tooltip` |
| `vaadin-combo-box-flow-integration-tests` | `@vaadin/combo-box`, `@vaadin/multi-select-combo-box` |
| `vaadin-date-picker-flow-integration-tests` | `@vaadin/date-picker` |

All at version `25.2.0-beta1` (Lerna-synced across web-components).

---

## Pre-flight check

Run these from the workspace root before starting:

```bash
npm --version       # ≥ 9
node --version      # Node 18–24 recommended for full mvn-install compatibility, but Task 9's verification doesn't need mvn
yarn --version      # already verified in previous plan
ls web-components/packages/{button,grid,tooltip,combo-box,multi-select-combo-box,date-picker} | head
```

The last command confirms all 6 web-component package directories exist (they were confirmed at version `25.2.0-beta1` during plan-writing).

---

### Task 1: Ignore `node_modules/` at the workspace root

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Verify current `.gitignore` state**

```bash
cat .gitignore
```
Expected output (3 lines):
```
.idea/
.gradle/
build/
```

- [ ] **Step 2: Append `node_modules`**

Use Edit to add `node_modules` as a fourth line. After the change, `.gitignore` should be exactly:

```
.idea/
.gradle/
build/
node_modules
```

End with a single trailing newline.

- [ ] **Step 3: Verify**

```bash
cat .gitignore && xxd .gitignore | tail -1
```
Expected: four lines, final byte `0a`, no double newline at EOF.

- [ ] **Step 4: Commit**

```bash
git add .gitignore
git commit -m "chore: ignore workspace-root node_modules"
```

---

### Task 2: Scaffold the overlay tree

**Files:**
- Create: `flow-components-overlay/README.md`
- Create: `flow-components-overlay/overlays.txt`

- [ ] **Step 1: Create `flow-components-overlay/README.md`**

Contents (exact, trailing newline at EOF):

```markdown
# flow-components Overlay Tree

This directory contains workspace-tracked `package.json` files that get
symlinked into the `flow-components/` submodule by `scripts/sync-flow-overlays.sh`.

Each subdirectory mirrors the corresponding submodule path so the mapping
between overlay file and target location is obvious from the layout alone.

The symlinks created inside `flow-components/` are caught by the submodule's
own `.gitignore` (`**/package*json`), so `git status` inside the submodule
stays clean.

See `docs/superpowers/specs/2026-06-04-it-npm-workspace-design.md` for the
full design rationale.
```

- [ ] **Step 2: Create `flow-components-overlay/overlays.txt`**

Contents (exact, trailing newline; one path per line):

```
vaadin-button-flow-parent/vaadin-button-flow-integration-tests
vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests
vaadin-combo-box-flow-parent/vaadin-combo-box-flow-integration-tests
vaadin-date-picker-flow-parent/vaadin-date-picker-flow-integration-tests
```

- [ ] **Step 3: Verify**

```bash
ls -la flow-components-overlay/
wc -l flow-components-overlay/overlays.txt
```
Expected: README.md and overlays.txt present; overlays.txt has 4 lines.

- [ ] **Step 4: Commit**

```bash
git add flow-components-overlay/README.md flow-components-overlay/overlays.txt
git commit -m "feat: scaffold flow-components overlay tree with pilot module list"
```

---

### Task 3: Hand-author the four overlay `package.json` files

**Files:**
- Create: `flow-components-overlay/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json`
- Create: `flow-components-overlay/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/package.json`
- Create: `flow-components-overlay/vaadin-combo-box-flow-parent/vaadin-combo-box-flow-integration-tests/package.json`
- Create: `flow-components-overlay/vaadin-date-picker-flow-parent/vaadin-date-picker-flow-integration-tests/package.json`

Each file ends with a trailing newline. The `file:` paths are three `..` segments back to the workspace root, then into `web-components/packages/<name>`.

- [ ] **Step 1: button**

Path: `flow-components-overlay/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json`

```json
{
  "name": "@vaadin-flow-integration-tests/button",
  "private": true,
  "version": "0.0.0",
  "dependencies": {
    "@vaadin/button": "file:../../../web-components/packages/button"
  }
}
```

- [ ] **Step 2: grid**

Path: `flow-components-overlay/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/package.json`

```json
{
  "name": "@vaadin-flow-integration-tests/grid",
  "private": true,
  "version": "0.0.0",
  "dependencies": {
    "@vaadin/grid": "file:../../../web-components/packages/grid",
    "@vaadin/tooltip": "file:../../../web-components/packages/tooltip"
  }
}
```

- [ ] **Step 3: combo-box**

Path: `flow-components-overlay/vaadin-combo-box-flow-parent/vaadin-combo-box-flow-integration-tests/package.json`

```json
{
  "name": "@vaadin-flow-integration-tests/combo-box",
  "private": true,
  "version": "0.0.0",
  "dependencies": {
    "@vaadin/combo-box": "file:../../../web-components/packages/combo-box",
    "@vaadin/multi-select-combo-box": "file:../../../web-components/packages/multi-select-combo-box"
  }
}
```

- [ ] **Step 4: date-picker**

Path: `flow-components-overlay/vaadin-date-picker-flow-parent/vaadin-date-picker-flow-integration-tests/package.json`

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

- [ ] **Step 5: Verify all four files**

```bash
find flow-components-overlay -name package.json | sort
```
Expected output (4 paths):
```
flow-components-overlay/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json
flow-components-overlay/vaadin-combo-box-flow-parent/vaadin-combo-box-flow-integration-tests/package.json
flow-components-overlay/vaadin-date-picker-flow-parent/vaadin-date-picker-flow-integration-tests/package.json
flow-components-overlay/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/package.json
```

JSON validity check:

```bash
for f in $(find flow-components-overlay -name package.json); do
  node -e "JSON.parse(require('fs').readFileSync('$f', 'utf8'))" && echo "OK: $f"
done
```
Expected: 4 lines starting with `OK:`.

- [ ] **Step 6: Commit**

```bash
git add flow-components-overlay/vaadin-button-flow-parent flow-components-overlay/vaadin-grid-flow-parent flow-components-overlay/vaadin-combo-box-flow-parent flow-components-overlay/vaadin-date-picker-flow-parent
git commit -m "feat: add overlay package.json files for 4 pilot IT modules"
```

---

### Task 4: Write and test the sync script

**Files:**
- Create: `scripts/sync-flow-overlays.sh`

- [ ] **Step 1: Create the script**

Path: `scripts/sync-flow-overlays.sh`. Make it executable (`chmod +x`) after creation.

```bash
#!/usr/bin/env bash
# Materializes workspace-tracked flow-components overlay files as symlinks
# inside the flow-components/ submodule. Idempotent.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OVERLAY_DIR="$ROOT/flow-components-overlay"
SUBMODULE_DIR="$ROOT/flow-components"
OVERLAYS_FILE="$OVERLAY_DIR/overlays.txt"

if [[ ! -f "$OVERLAYS_FILE" ]]; then
    echo "error: $OVERLAYS_FILE not found" >&2
    exit 1
fi

status=0
while IFS= read -r rel_path || [[ -n "$rel_path" ]]; do
    # Skip blank lines and comments.
    [[ -z "$rel_path" || "$rel_path" =~ ^# ]] && continue

    source_file="$OVERLAY_DIR/$rel_path/package.json"
    target_file="$SUBMODULE_DIR/$rel_path/package.json"
    target_dir="$(dirname "$target_file")"

    if [[ ! -f "$source_file" ]]; then
        echo "error: missing overlay source: $source_file" >&2
        status=1
        continue
    fi

    if [[ ! -d "$target_dir" ]]; then
        echo "error: missing submodule target dir: $target_dir" >&2
        status=1
        continue
    fi

    # Compute symlink target as a relative path from target_dir back to source_file.
    # Each rel_path has the form <parent>/<it-module>, i.e., 2 segments. From
    # flow-components/<parent>/<it-module>/ we need 3 ..'s to reach workspace root,
    # then into flow-components-overlay/<parent>/<it-module>/package.json.
    link_target="../../../flow-components-overlay/$rel_path/package.json"

    if [[ -L "$target_file" ]]; then
        existing="$(readlink "$target_file")"
        if [[ "$existing" == "$link_target" ]]; then
            echo "up-to-date: $target_file"
            continue
        else
            echo "error: $target_file is a symlink pointing at $existing (expected $link_target); not overwriting" >&2
            status=1
            continue
        fi
    fi

    if [[ -e "$target_file" ]]; then
        echo "error: $target_file exists and is not a symlink; not overwriting" >&2
        status=1
        continue
    fi

    ln -s "$link_target" "$target_file"
    echo "created: $target_file -> $link_target"
done < "$OVERLAYS_FILE"

exit $status
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x scripts/sync-flow-overlays.sh
```

- [ ] **Step 3: Run it the first time**

```bash
./scripts/sync-flow-overlays.sh
```
Expected output (4 `created:` lines, one per overlay), exit code 0:
```
created: .../flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json -> ../../../flow-components-overlay/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json
created: .../flow-components/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/package.json -> ../../../flow-components-overlay/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/package.json
created: .../flow-components/vaadin-combo-box-flow-parent/vaadin-combo-box-flow-integration-tests/package.json -> ../../../flow-components-overlay/vaadin-combo-box-flow-parent/vaadin-combo-box-flow-integration-tests/package.json
created: .../flow-components/vaadin-date-picker-flow-parent/vaadin-date-picker-flow-integration-tests/package.json -> ../../../flow-components-overlay/vaadin-date-picker-flow-parent/vaadin-date-picker-flow-integration-tests/package.json
```

- [ ] **Step 4: Run it a second time to verify idempotency**

```bash
./scripts/sync-flow-overlays.sh
```
Expected output (4 `up-to-date:` lines), exit code 0.

- [ ] **Step 5: Verify the symlinks resolve and the submodule git status is clean**

```bash
ls -la flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json
cat flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json
cd flow-components && git status --short && cd ..
```
Expected:
- `ls` shows `package.json -> ../../../flow-components-overlay/.../package.json`
- `cat` outputs the JSON we wrote in Task 3
- `git status --short` in the submodule prints nothing (the `.gitignore` rule `**/package*json` masks the symlinks)

- [ ] **Step 6: Commit**

```bash
git add scripts/sync-flow-overlays.sh
git commit -m "feat: add idempotent sync script for flow-components overlays"
```

---

### Task 5: Create the workspace root `package.json`

**Files:**
- Create: `package.json` (workspace root)

- [ ] **Step 1: Create the file**

Path: `package.json` at the workspace root. Trailing newline.

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

- [ ] **Step 2: Validate JSON**

```bash
node -e "JSON.parse(require('fs').readFileSync('package.json', 'utf8'))" && echo "OK"
```
Expected: `OK`.

- [ ] **Step 3: Dry-run workspace listing**

```bash
npm query .workspace 2>&1 | head -30
```
Expected: a JSON array listing many packages (the `@vaadin/*` packages from `web-components/packages/*` plus the 4 `@vaadin-flow-integration-tests/*` overlays). If npm refuses (`error: package-lock.json not found` or similar pre-install error), that's fine — Task 6 covers the install.

- [ ] **Step 4: Commit**

```bash
git add package.json
git commit -m "feat: add npm workspace root with web-components and IT overlays"
```

---

### Task 6: Verify `npm install` works bare

**No file changes in this task** — it's a verification gate. If something is wrong with the workspace setup, fix the source-of-truth files (overlay package.jsons, root package.json) and re-run.

- [ ] **Step 1: Run `npm install` at the workspace root**

```bash
npm install
```
Expected: completes successfully with a final summary like `added N packages`. Creates `package-lock.json` and `node_modules/` at the workspace root. Network access required for transitive registry deps (`lit`, `@open-wc/dedupe-mixin`, etc.).

If npm fails because Node is too new (Node 25+ has previously broken some Vaadin builds), capture the last 30 lines and report the error.

- [ ] **Step 2: Verify the `@vaadin/*` symlinks in `node_modules/`**

```bash
for p in button grid tooltip combo-box multi-select-combo-box date-picker; do
  ls -la "node_modules/@vaadin/$p" | head -1
done
```
Expected: each line shows a symlink (mode starts with `l`) pointing into `../../web-components/packages/<name>` (or similar relative path).

- [ ] **Step 3: Verify the 4 IT module workspace members appear**

```bash
ls node_modules/@vaadin-flow-integration-tests/
```
Expected: `button combo-box date-picker grid` listed.

- [ ] **Step 4: Verify date-picker's transitive `@vaadin/button` resolves to the workspace**

```bash
readlink node_modules/@vaadin/button
ls -la node_modules/@vaadin/date-picker/node_modules/@vaadin/button 2>/dev/null || echo "(hoisted to root — expected)"
```
Expected: the first command shows a relative symlink into `web-components/packages/button`; the second command falls through to `(hoisted to root — expected)` because npm hoists shared deps.

- [ ] **Step 5: Commit the lockfile**

```bash
git add package-lock.json
git commit -m "chore: add npm workspace lockfile"
```

---

### Task 7: Add `syncFlowOverlays` Gradle task

**Files:**
- Modify: `gradle/flow-components.gradle.kts`

- [ ] **Step 1: Append the task definition**

Append to the end of `gradle/flow-components.gradle.kts` (after the existing four task registrations). Preserve trailing newline.

```kotlin

val syncFlowOverlays = tasks.register<Exec>("syncFlowOverlays") {
    description = "Materializes workspace-tracked flow-components overlay package.json files as symlinks inside the submodule."
    group = "build"
    workingDir = rootDir
    commandLine("bash", "scripts/sync-flow-overlays.sh")
}

tasks.named("install") {
    dependsOn(syncFlowOverlays)
}
```

- [ ] **Step 2: Verify the task is registered**

```bash
./gradlew :flow-components:tasks --all | grep -E 'syncFlowOverlays|install'
```
Expected: lists `syncFlowOverlays` with the description above. `install` is also listed.

- [ ] **Step 3: Verify dependency wiring via dry-run**

```bash
./gradlew :flow-components:install --dry-run
```
Expected: dry-run order includes `:flow-components:syncFlowOverlays SKIPPED` before `:flow-components:install SKIPPED`.

- [ ] **Step 4: Run the task for real**

```bash
./gradlew :flow-components:syncFlowOverlays
```
Expected: prints 4 `up-to-date:` lines (symlinks already exist from Task 4), `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add gradle/flow-components.gradle.kts
git commit -m "feat: wire syncFlowOverlays into :flow-components:install"
```

---

### Task 8: Add `npmInstall` Gradle task at the root

**Files:**
- Modify: `build.gradle.kts`

This task extends the root build to apply the Node plugin to the root project (in addition to `:web-components`) and registers an `npmInstall` task. The aggregate root `install` task now also depends on `npmInstall`.

- [ ] **Step 1: Read the current `build.gradle.kts`**

```bash
cat build.gradle.kts
```
Confirm the current state matches the previous spec's output (plugins block, `project(":web-components") { ... }` configure block, four aggregate task definitions).

- [ ] **Step 2: Modify `build.gradle.kts`**

Make these three changes:

(a) Add an import at the very top of the file (line 1):

```kotlin
import com.github.gradle.node.npm.task.NpmTask
```

(b) After the existing `project(":web-components") { ... }` block (which currently ends around line 14), insert a new block applying the Node plugin to the root project and configuring it. Insert with one blank line of separation from the preceding block:

```kotlin

apply(plugin = "com.github.node-gradle.node")
extensions.configure<com.github.gradle.node.NodeExtension> {
    download.set(false)
    nodeProjectDir.set(rootDir)
    workDir.set(layout.buildDirectory.dir("nodejs"))
    npmWorkDir.set(layout.buildDirectory.dir("npm"))
    yarnWorkDir.set(layout.buildDirectory.dir("yarn"))
}

val npmInstall = tasks.named<NpmTask>("npmInstall") {
    description = "Installs all workspace npm dependencies."
    group = "build"
    args.set(listOf("install"))
    dependsOn(":flow-components:syncFlowOverlays")
    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
}
```

(c) Modify the existing aggregate `install` task to also depend on `npmInstall`. Find the existing block:

```kotlin
tasks.register("install") {
    description = "Installs dependencies for all subprojects."
    group = "build"
    dependsOn(":web-components:install", ":flow-components:install")
}
```

Change the `dependsOn` line to:

```kotlin
    dependsOn(":web-components:install", ":flow-components:install", npmInstall)
```

- [ ] **Step 3: Verify the build loads**

```bash
./gradlew help
```
Expected: `BUILD SUCCESSFUL`. If a Kotlin DSL error appears (e.g., unresolved `NpmTask` reference), check the import in change (a).

- [ ] **Step 4: Verify `npmInstall` is registered and wired**

```bash
./gradlew tasks --group build | grep -E 'npmInstall|^install '
./gradlew install --dry-run 2>&1 | head -30
```
Expected:
- `tasks` lists `npmInstall` and root `install`.
- `install --dry-run` includes `:syncFlowOverlays SKIPPED`, `:npmInstall SKIPPED`, `:web-components:install SKIPPED`, `:flow-components:install SKIPPED`.

- [ ] **Step 5: Run `npmInstall` for real**

```bash
./gradlew npmInstall
```
Expected: completes successfully. Since `package-lock.json` and `node_modules/` haven't changed since Task 6, `npmInstall` should report `UP-TO-DATE` (or a near-no-op install).

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -m "feat: add npmInstall Gradle task wired to root install"
```

---

### Task 9: End-to-end verification from a clean state

**No file changes** — verification gate.

- [ ] **Step 1: Clean the workspace**

```bash
rm -rf node_modules package-lock.json
rm -f flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json
rm -f flow-components/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/package.json
rm -f flow-components/vaadin-combo-box-flow-parent/vaadin-combo-box-flow-integration-tests/package.json
rm -f flow-components/vaadin-date-picker-flow-parent/vaadin-date-picker-flow-integration-tests/package.json
```

This simulates a fresh post-clone state (lockfile and node_modules wiped; symlinks wiped). Do NOT commit anything — this is a verification setup.

- [ ] **Step 2: Run the full install via Gradle**

```bash
./gradlew install
```
Expected:
- `:flow-components:syncFlowOverlays` creates the 4 symlinks fresh (4 `created:` lines).
- `npmInstall` runs `npm install` and populates `node_modules/`.
- `:web-components:install` runs `yarn install` in the web-components submodule (slow, but already cached from prior tasks if you've been doing the steps in order).
- Final: `BUILD SUCCESSFUL`.

If a step fails, STOP and capture the last 30 lines.

- [ ] **Step 3: Verify the symlinks are present**

```bash
for d in vaadin-button vaadin-grid vaadin-combo-box vaadin-date-picker; do
  ls -la "flow-components/${d}-flow-parent/${d}-flow-integration-tests/package.json"
done
```
Expected: 4 lines, each showing a symlink (`l`-prefixed mode) into the overlay tree.

- [ ] **Step 4: Verify the workspace `node_modules/` has the expected symlinks**

```bash
for p in button grid tooltip combo-box multi-select-combo-box date-picker; do
  printf "%-30s -> %s\n" "@vaadin/$p" "$(readlink node_modules/@vaadin/$p)"
done
```
Expected: each line shows the symlink target ending in `web-components/packages/<name>`.

- [ ] **Step 5: Verify both submodules are clean**

```bash
cd web-components && git status --short && cd ..
cd flow-components && git status --short && cd ..
```
Expected: both empty (no uncommitted changes; symlinks masked by submodule .gitignore).

- [ ] **Step 6: No commit**

Verification gate only.

---

### Task 10: Flow plugin coexistence check

**No file changes** — verification gate. Confirms that `mvn install` on an IT module does not destroy the overlay symlink.

If `mvn install` fails on this machine due to the Node 25 incompatibility in `vaadin-charts-flow-svg-generator` (same issue surfaced in the previous plan's smoke test), pick an IT module that does NOT depend on the charts module — `vaadin-button-flow-integration-tests` is the safest single-module target since it has no Charts dependency.

- [ ] **Step 1: Capture pre-state of the button symlink**

```bash
ls -la flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json > /tmp/overlay-pre.txt
cat /tmp/overlay-pre.txt
```

- [ ] **Step 2: Run `mvn install` on the button IT module only**

```bash
cd flow-components
mvn install -DskipTests -pl vaadin-button-flow-parent/vaadin-button-flow-integration-tests -am
cd ..
```
Expected: `BUILD SUCCESS`. The `-am` (also-make) flag pulls in upstream deps automatically. If this build fails for environmental reasons (Node 25 issue, port-8080 conflict), STOP and report — Task 10 cannot complete without a working mvn build.

- [ ] **Step 3: Verify the symlink survived**

```bash
ls -la flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json > /tmp/overlay-post.txt
diff /tmp/overlay-pre.txt /tmp/overlay-post.txt
```
Expected: `diff` produces no output (file is identical — still a symlink to the overlay). If Flow's plugin converted the symlink to a regular file, the diff will show it.

- [ ] **Step 4: Verify the symlink target is still the overlay**

```bash
readlink flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json
```
Expected: `../../../flow-components-overlay/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json`.

- [ ] **Step 5: Verify the overlay file content is unchanged**

```bash
cat flow-components-overlay/vaadin-button-flow-parent/vaadin-button-flow-integration-tests/package.json
```
Expected: identical to what we wrote in Task 3 — a 3-key JSON with `@vaadin/button` as the only dep. If Flow's plugin wrote through the symlink and added/changed keys, capture the diff and report.

- [ ] **Step 6: Verify submodule git status is still clean**

```bash
cd flow-components && git status --short && cd ..
```
Expected: empty output.

- [ ] **Step 7: No commit**

---

### Task 11: Local-edit propagation check

**No file changes that get committed** — verification gate. Confirms that an edit to a web-components package source is visible from a flow-components IT module's resolved deps (the core promise of the workspace).

- [ ] **Step 1: Add a marker comment to a web-components source file**

Append a no-op comment line to `web-components/packages/button/src/vaadin-button.js`. Use Edit/Write, not `echo`. The file already exists; we just append one line. Pick a line near the top of the file that is safe to add a comment after.

Example: find the first import statement and add a line after it like:

```js
// workspace-overlay verification marker — do not commit
```

- [ ] **Step 2: Verify the change appears through the workspace symlink**

```bash
grep "workspace-overlay verification marker" node_modules/@vaadin/button/src/vaadin-button.js
```
Expected: prints the marker line. Because `node_modules/@vaadin/button` is a symlink into `web-components/packages/button`, the change is visible immediately without any reinstall.

- [ ] **Step 3: Verify the change appears via the IT module's transitive resolution (date-picker depends on button)**

```bash
grep "workspace-overlay verification marker" node_modules/@vaadin/date-picker/node_modules/@vaadin/button/src/vaadin-button.js 2>/dev/null \
  || grep "workspace-overlay verification marker" node_modules/@vaadin/button/src/vaadin-button.js
```

If `node_modules/@vaadin/date-picker/node_modules/@vaadin/button` doesn't exist (because npm hoisted button to the root), the fallback grep is the canonical check — both paths resolve to the same underlying file via the workspace.

Expected: at least one of the two greps finds the marker.

- [ ] **Step 4: Revert the marker**

Use Edit to remove the marker line from `web-components/packages/button/src/vaadin-button.js`. Verify the file is back to its original state:

```bash
cd web-components && git status --short && cd ..
```
Expected: empty (the file is back to its committed state).

If `git status --short` still shows the file as modified, run `cd web-components && git checkout -- packages/button/src/vaadin-button.js && cd ..` to discard the local edit.

- [ ] **Step 5: Confirm the marker is gone from the resolved view**

```bash
grep "workspace-overlay verification marker" node_modules/@vaadin/button/src/vaadin-button.js && echo "STILL PRESENT (problem)" || echo "removed"
```
Expected: `removed`.

- [ ] **Step 6: No commit**

---

### Task 12: Document the npm workspace in README and CLAUDE.md

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update `README.md`**

In `README.md`, find the existing `## Building` section (added in the previous plan's Task 11) and insert a new subsection **before** the existing `./gradlew install` instructions.

Add the following content directly under the `## Building` header (the 4-backtick outer fence is just for unambiguity in this plan; write the inner content with 3-backtick fences as shown):

````markdown
### npm workspace for flow-components integration tests

The workspace also exposes an npm workspace at the root that lets a pilot set of `flow-components` integration-test modules consume `@vaadin/*` packages directly from the local `web-components/` submodule. The Gradle `install` task creates the necessary symlinks and runs `npm install` automatically; no extra commands are needed.

Pilot IT modules currently covered:

- `vaadin-button-flow-integration-tests`
- `vaadin-grid-flow-integration-tests`
- `vaadin-combo-box-flow-integration-tests`
- `vaadin-date-picker-flow-integration-tests`

Local edits to `web-components/packages/*` source files take effect in those IT modules without a re-install. See `docs/superpowers/specs/2026-06-04-it-npm-workspace-design.md` for the design.
````

- [ ] **Step 2: Update `CLAUDE.md`**

In `CLAUDE.md`, find the existing `## Workspace-Level Build` section (added in the previous plan's Task 11). After its existing content, append a new subsection:

````markdown
### Integration-test npm workspace

The workspace root also acts as an npm workspace. A pilot set of `flow-components` integration-test modules (`vaadin-button-flow-integration-tests`, `vaadin-grid-flow-integration-tests`, `vaadin-combo-box-flow-integration-tests`, `vaadin-date-picker-flow-integration-tests`) consume `@vaadin/*` packages directly from `web-components/packages/*` via workspace symlinks. The per-IT-module `package.json` files live in `flow-components-overlay/` and are symlinked into the submodule by `scripts/sync-flow-overlays.sh` (wired into `./gradlew :flow-components:syncFlowOverlays`, run automatically by `./gradlew install`).

To add a new IT module to the pilot:

1. Append its path to `flow-components-overlay/overlays.txt`.
2. Create the matching directory and `package.json` under `flow-components-overlay/`, listing the direct `@vaadin/*` deps from the module's Java `@NpmPackage` annotations as `file:` URLs into `web-components/packages/<name>`.
3. Run `./gradlew install`.

See `docs/superpowers/specs/2026-06-04-it-npm-workspace-design.md`.
````

- [ ] **Step 3: Verify**

```bash
grep -n "npm workspace" README.md CLAUDE.md
grep -n "flow-components-overlay" README.md CLAUDE.md
```
Expected: matches in both files.

Trailing newline check:

```bash
xxd README.md | tail -1
xxd CLAUDE.md | tail -1
```
Expected: each ends with a single `0a`.

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: document the IT npm workspace and overlay mechanism"
```

---

## Self-review checklist (run after all tasks)

- [ ] Every section of the spec is covered:
  - Architecture/overlay tree → Tasks 2, 3, 4
  - Setup script → Task 4
  - File layout → Tasks 1, 2, 3, 4, 5, 7, 8
  - package.json shapes → Tasks 3, 5
  - Install flow → Tasks 6, 9
  - Gradle integration → Tasks 7, 8
  - Flow plugin coexistence → Task 10
  - Pilot module selection → Task 3
  - Verification → Tasks 9, 10, 11
  - Documentation → Task 12
- [ ] No `pnpm` references introduced anywhere (search after Task 12: `grep -ri pnpm flow-components-overlay scripts package.json build.gradle.kts gradle/flow-components.gradle.kts` — expected empty).
- [ ] `./gradlew install` succeeded from a clean state in Task 9.
- [ ] Both submodules are clean (`git status --short` empty inside each) after Tasks 9, 10, and 11.

## What could go wrong

- **`npm install` fails on Node 25.** The Node 25 incompatibility surfaced in the previous plan's Task 10 (`vaadin-charts-flow-svg-generator` → mocha → jsdom localStorage) does not directly affect this plan's `npm install` (no mocha/jsdom in the install step), but `mvn install` in Task 10 still hits it for modules that transitively depend on Charts. The pilot IT modules (button, grid, combo-box, date-picker) do not depend on Charts, so `mvn install -pl <module> -am` should succeed when invoked at the IT-module granularity.
- **Symlinks on Windows.** Bash + `ln -s` require either Developer Mode or `mklink` privileges on Windows. Out of scope; macOS/Linux is the primary target.
- **`buildFileName` deprecation in Gradle 9+.** Not introduced by this plan; carried over from the previous spec. No action.
- **`npm install` writes to `package-lock.json` and creates a diff every time.** Mitigation: commit the lockfile once (Task 6) and let future runs be no-ops as long as deps don't change. The Gradle task's `inputs`/`outputs` declarations also skip the run when nothing changed.
- **Flow plugin behavior on a module not in the pilot.** Out of scope — only the 4 pilot modules get overlays. Other IT modules continue to use Flow's auto-generated package.json as before.
