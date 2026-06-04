# Common Gradle Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a workspace-root Gradle build that orchestrates the existing Yarn (web-components) and Maven (flow-components) builds behind a uniform `install`/`build`/`test`/`clean` task surface.

**Architecture:** Gradle multi-project. Two subprojects (`:web-components`, `:flow-components`) point at the submodule directories via `projectDir`; their build scripts live at the workspace root in `gradle/` so no files end up tracked inside the submodules. Yarn is invoked via the `com.github.node-gradle.node` plugin (`download = false`, system Yarn); Maven is invoked via plain `Exec` tasks. Gradle never compiles anything itself — every task delegates to the submodule's native tooling.

**Tech Stack:**
- Gradle 8.10 (via Gradle Wrapper)
- Kotlin DSL (`.gradle.kts`)
- `com.github.node-gradle.node` plugin v7.1.0
- Kotlin (DSL only — no JVM compilation)

**Reference spec:** `docs/superpowers/specs/2026-06-04-common-gradle-build-design.md`

---

## Pre-flight check

System Gradle was confirmed installed at `gradle --version` → `Gradle 9.2.0`. The wrapper we generate will be pinned to 8.10 regardless of the system version, so this only matters for the bootstrap step.

The submodules' yarn/mvn must be on `PATH` for any task that actually runs them; this is already a workspace prerequisite per `README.md`.

---

### Task 1: Bootstrap the Gradle Wrapper

**Files:**
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: Generate the wrapper at version 8.10**

From the workspace root:

```bash
gradle wrapper --gradle-version 8.10 --distribution-type bin
```

This uses the system `gradle` (9.2.0) only to scaffold the wrapper files. The wrapper itself will download and use Gradle 8.10.

- [ ] **Step 2: Verify wrapper bootstraps correctly**

Run: `./gradlew --version`
Expected output contains:
```
Gradle 8.10
```
(First run downloads the distribution; subsequent runs are fast.)

- [ ] **Step 3: Commit**

```bash
git add gradlew gradlew.bat gradle/wrapper/
git commit -m "chore: add Gradle Wrapper at 8.10"
```

---

### Task 2: Update `.gitignore`

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Add Gradle build/cache directories**

Append two lines to `.gitignore`:

```
.gradle/
build/
```

The existing `.gitignore` already contains `.idea/`; do not duplicate.

- [ ] **Step 2: Verify nothing under `.gradle/` would be staged**

Run: `git status`
Expected: no `.gradle/` or `build/` paths in the output (even after `./gradlew --version` was just run and created `.gradle/`).

- [ ] **Step 3: Commit**

```bash
git add .gitignore
git commit -m "chore: ignore Gradle .gradle/ and build/ dirs"
```

---

### Task 3: Create skeleton subproject build scripts

**Files:**
- Create: `gradle/web-components.gradle.kts`
- Create: `gradle/flow-components.gradle.kts`

These are placeholders for now. Settings.gradle.kts will reference them in the next task; they must exist as valid Kotlin files even if empty.

- [ ] **Step 1: Create empty placeholders**

Write `gradle/web-components.gradle.kts`:

```kotlin
// Tasks for the :web-components subproject. Populated in a later task.
```

Write `gradle/flow-components.gradle.kts`:

```kotlin
// Tasks for the :flow-components subproject. Populated in a later task.
```

- [ ] **Step 2: Commit**

```bash
git add gradle/web-components.gradle.kts gradle/flow-components.gradle.kts
git commit -m "chore: add empty subproject build script placeholders"
```

---

### Task 4: Wire subprojects in `settings.gradle.kts`

**Files:**
- Create: `settings.gradle.kts`

- [ ] **Step 1: Create settings file**

Write `settings.gradle.kts`:

```kotlin
rootProject.name = "components-workspace"

include(":web-components", ":flow-components")

project(":web-components").apply {
    projectDir = file("web-components")
    buildFileName = "../gradle/web-components.gradle.kts"
}

project(":flow-components").apply {
    projectDir = file("flow-components")
    buildFileName = "../gradle/flow-components.gradle.kts"
}
```

Note: `buildFileName` is a path **relative to `projectDir`**, so `../gradle/web-components.gradle.kts` resolves from `<root>/web-components/` back out to `<root>/gradle/web-components.gradle.kts` — the file we just created. No files end up tracked inside the submodules.

- [ ] **Step 2: Verify Gradle sees both subprojects**

Run: `./gradlew projects`

Expected output includes:
```
Root project 'components-workspace'
+--- Project ':flow-components'
\--- Project ':web-components'
```

- [ ] **Step 3: Commit**

```bash
git add settings.gradle.kts
git commit -m "feat: wire web-components and flow-components as Gradle subprojects"
```

---

### Task 5: Create root `build.gradle.kts` with plugin declarations

**Files:**
- Create: `build.gradle.kts`

- [ ] **Step 1: Create root build file**

Write `build.gradle.kts`:

```kotlin
plugins {
    base
    id("com.github.node-gradle.node") version "7.1.0" apply false
}

project(":web-components") {
    apply(plugin = "base")
    apply(plugin = "com.github.node-gradle.node")
    extensions.configure<com.github.gradle.node.NodeExtension> {
        download.set(false)
        nodeProjectDir.set(projectDir)
        workDir.set(rootProject.layout.buildDirectory.dir("nodejs"))
        npmWorkDir.set(rootProject.layout.buildDirectory.dir("npm"))
        yarnWorkDir.set(rootProject.layout.buildDirectory.dir("yarn"))
    }
}
```

Rationale:
- `base` is applied at root (gives us `clean`, `build` lifecycle tasks to wire aggregates onto) and also to `:web-components` (so we can configure its `clean` as a `Delete` task and have a `build` lifecycle to wire `install` onto).
- The Node plugin is declared at root with `apply false` so its version is centralized; it's actually applied to `:web-components` via the `configure` block.
- `download = false` means the system Node + Yarn are used. No automatic provisioning.
- `nodeProjectDir = projectDir` (the `web-components` submodule directory) tells the plugin where `package.json` and `yarn.lock` live.

- [ ] **Step 2: Verify Gradle still loads**

Run: `./gradlew help`
Expected: completes without errors. (`help` is the default task — if the build script has syntax errors, this will fail.)

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "feat: add root Gradle build with Node plugin wired to web-components"
```

---

### Task 6: Implement `:web-components` tasks

**Files:**
- Modify: `gradle/web-components.gradle.kts`

- [ ] **Step 1: Replace the placeholder with task definitions**

Write `gradle/web-components.gradle.kts`:

```kotlin
import com.github.gradle.node.yarn.task.YarnTask

tasks.register<YarnTask>("install") {
    description = "Runs `yarn install` in the web-components submodule."
    group = "build"
    args.set(listOf("install"))
    inputs.file("package.json")
    inputs.file("yarn.lock")
    outputs.dir("node_modules")
}

tasks.named("build") {
    description = "Build web-components — no-action; components are source-published. Triggers install."
    dependsOn("install")
}

tasks.register<YarnTask>("test") {
    description = "Runs `yarn test` (default: changed packages only)."
    group = "verification"
    args.set(listOf("test"))
    dependsOn("build")
}

tasks.named<Delete>("clean") {
    description = "Removes node_modules in the web-components submodule."
    delete(file("node_modules"))
}
```

Notes on the design:
- `YarnTask` is provided by the Node plugin. Its working directory is `nodeProjectDir` (set in the root build to `projectDir` = the web-components submodule), so we only set `args`.
- `inputs`/`outputs` on `install` let Gradle skip it when `package.json` and `yarn.lock` are unchanged and `node_modules/` exists.
- `build` uses `tasks.named` (configuring the lifecycle task added by the `base` plugin) instead of `tasks.register`, so it doesn't duplicate the existing `build` task.
- `clean` is configured the same way — `base`'s `clean` is a `Delete` task; we just add a path to its `delete` set.

- [ ] **Step 2: Verify the task list includes all four**

Run: `./gradlew :web-components:tasks --all`

Expected: output contains all four:
```
install - Runs `yarn install` in the web-components submodule.
build - Build web-components ...
test - Runs `yarn test` ...
clean - Removes node_modules ...
```

- [ ] **Step 3: Verify dependency chain via dry-run**

Run: `./gradlew :web-components:test --dry-run`

Expected: dry-run output lists tasks in order — `:web-components:install`, `:web-components:build`, `:web-components:test` — none of them executed (because `--dry-run`).

- [ ] **Step 4: Commit**

```bash
git add gradle/web-components.gradle.kts
git commit -m "feat: add install/build/test/clean tasks for :web-components"
```

---

### Task 7: Implement `:flow-components` tasks

**Files:**
- Modify: `gradle/flow-components.gradle.kts`

- [ ] **Step 1: Replace the placeholder with task definitions**

Write `gradle/flow-components.gradle.kts`:

```kotlin
import org.gradle.internal.os.OperatingSystem

val mvnCommand = if (OperatingSystem.current().isWindows) "mvn.cmd" else "mvn"

tasks.register("install") {
    description = "No-op for flow-components — Maven resolves dependencies on demand. Kept for task-surface symmetry with :web-components."
    group = "build"
}

tasks.register<Exec>("build") {
    description = "Runs `mvn -DskipTests install` in the flow-components submodule."
    group = "build"
    workingDir = projectDir
    commandLine(mvnCommand, "-DskipTests", "install")
    dependsOn("install")
}

tasks.register<Exec>("test") {
    description = "Runs `mvn test` in the flow-components submodule."
    group = "verification"
    workingDir = projectDir
    commandLine(mvnCommand, "test")
    dependsOn("build")
}

tasks.register<Exec>("clean") {
    description = "Runs `mvn clean` in the flow-components submodule."
    group = "build"
    workingDir = projectDir
    commandLine(mvnCommand, "clean")
}
```

Notes:
- We don't apply the `base` plugin here, so no lifecycle tasks pre-exist and all four can be `register`ed directly.
- `mvn install` (not `package`) matches existing flow-components/CLAUDE.md guidance — it populates the local Maven repository so consumers can resolve the artifacts.
- Each `Exec` task fails the Gradle build on non-zero exit; no extra error wiring needed.
- Windows compatibility via `OperatingSystem.current().isWindows`.

- [ ] **Step 2: Verify the task list**

Run: `./gradlew :flow-components:tasks --all`

Expected: output contains `install`, `build`, `test`, `clean` each with the description above.

- [ ] **Step 3: Verify dependency chain via dry-run**

Run: `./gradlew :flow-components:test --dry-run`

Expected: dry-run order — `:flow-components:install`, `:flow-components:build`, `:flow-components:test`.

- [ ] **Step 4: Commit**

```bash
git add gradle/flow-components.gradle.kts
git commit -m "feat: add install/build/test/clean tasks for :flow-components"
```

---

### Task 8: Add aggregate tasks at root

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Append aggregate task wiring**

Append to `build.gradle.kts` (after the existing `project(":web-components")` block):

```kotlin
tasks.register("install") {
    description = "Installs dependencies for all subprojects."
    group = "build"
    dependsOn(":web-components:install", ":flow-components:install")
}

tasks.named("build") {
    description = "Builds all subprojects."
    dependsOn(":web-components:build", ":flow-components:build")
}

tasks.register("test") {
    description = "Runs tests for all subprojects."
    group = "verification"
    dependsOn(":web-components:test", ":flow-components:test")
}

tasks.named("clean") {
    description = "Cleans all subprojects."
    dependsOn(":web-components:clean", ":flow-components:clean")
}
```

Notes:
- `build` and `clean` are configured (via `named`) because the root project has the `base` plugin applied, which already created those lifecycle tasks. `install` and `test` are net-new and use `register`.
- Per-subproject parallel execution is enabled by the next task (gradle.properties).

- [ ] **Step 2: Verify all four aggregate tasks exist with the wiring**

Run: `./gradlew tasks --group build --group verification`

Expected: output lists root-level `install`, `build`, `test`, `clean`.

- [ ] **Step 3: Verify `./gradlew build` would invoke both subprojects**

Run: `./gradlew build --dry-run`

Expected: dry-run order includes both `:web-components:build` and `:flow-components:build` (and their `install` predecessors).

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "feat: add aggregate install/build/test/clean tasks at workspace root"
```

---

### Task 9: Add `gradle.properties` for parallel execution

**Files:**
- Create: `gradle.properties`

- [ ] **Step 1: Create the properties file**

Write `gradle.properties`:

```
org.gradle.parallel=true
org.gradle.java.installations.auto-detect=true
```

Notes:
- `parallel=true` lets Gradle execute tasks across the two subprojects concurrently. They have no inter-dependency, so this is safe.
- `java.installations.auto-detect=true` is a hint for environments where multiple JDKs are present; flow-components' Maven build picks up `JAVA_HOME` itself so Gradle's only role is to find a JDK for itself if needed.

- [ ] **Step 2: Verify Gradle loads with the new properties**

Run: `./gradlew help`
Expected: completes without errors, no warnings about unknown properties.

- [ ] **Step 3: Commit**

```bash
git add gradle.properties
git commit -m "chore: enable Gradle parallel execution"
```

---

### Task 10: End-to-end smoke test

No new files; this verifies the previous tasks actually work against the real submodules. Expected to take **15–30 minutes** on a clean machine (Yarn install + Maven install for flow-components dominate).

**Prerequisite check:**

Run these and confirm each prints a version, not an error:

```bash
yarn --version
mvn --version
node --version
```

- [ ] **Step 1: Run aggregate `install`**

Run: `./gradlew install`

Expected:
- `:web-components:install` runs `yarn install`, populates `web-components/node_modules/`. May take several minutes the first time.
- `:flow-components:install` is a no-op, completes instantly.
- Final line: `BUILD SUCCESSFUL`.

If yarn install fails for an environmental reason (e.g., a network issue, missing Node version), stop, fix the environment, and re-run.

- [ ] **Step 2: Re-run `install` and verify it's UP-TO-DATE**

Run: `./gradlew install` (a second time)

Expected: `:web-components:install` reports `UP-TO-DATE` (because the `inputs`/`outputs` declarations let Gradle skip when unchanged).

- [ ] **Step 3: Run aggregate `build`**

Run: `./gradlew build`

Expected:
- `:web-components:build` is a no-action task that depends on `install` (already up-to-date).
- `:flow-components:build` runs `mvn -DskipTests install`. This is slow (10+ minutes) and produces a lot of Maven output streamed to the console.
- Final line: `BUILD SUCCESSFUL`.

If `mvn` fails (e.g., `JAVA_HOME` not 17+, network for dependency downloads), fix and re-run.

- [ ] **Step 4: Run `clean`**

Run: `./gradlew clean`

Expected:
- `:web-components:clean` removes `web-components/node_modules/`.
- `:flow-components:clean` runs `mvn clean` (removes Maven `target/` directories).
- Final line: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Verify cleanup**

Run: `ls web-components/node_modules 2>&1 | head -1`
Expected: `ls: web-components/node_modules: No such file or directory`

Run: `find flow-components -name target -type d -maxdepth 3 2>/dev/null | head -3`
Expected: empty output (or no `target/` directories).

- [ ] **Step 6: No commit**

This task is a verification gate. If anything failed, go back and fix the relevant task before continuing. If everything passed, no code changes were made — no commit.

---

### Task 11: Document the new entry points

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update `README.md`**

Find the existing `## Building` section in `README.md` (currently at lines 56–72) and **replace** the entire section (header through the last bullet of "flow-components" block, before the next `## Future Plans` heading) with the following content. The outer fence below is 4 backticks; the inner code blocks remain 3.

````markdown
## Building

The workspace exposes Gradle tasks that orchestrate both submodules:

```bash
./gradlew install   # yarn install (web-components) + Maven warm-up (no-op)
./gradlew build     # web-components ready + mvn -DskipTests install
./gradlew test      # yarn test (web-components, changed packages) + mvn test
./gradlew clean     # remove node_modules + mvn clean
```

Run a single subproject's task by prefixing the subproject path:

```bash
./gradlew :web-components:build
./gradlew :flow-components:test
```

The submodules' native commands continue to work directly inside each submodule:

```bash
# web-components
cd web-components && yarn install

# flow-components
cd flow-components && mvn install
```

See `docs/superpowers/specs/2026-06-04-common-gradle-build-design.md` for design details.
````

Also update the `## Prerequisites` section (currently lines 5–9): change `Node.js 18+ and pnpm (for web-components)` to `Node.js 18+ and Yarn (for web-components)`.

- [ ] **Step 2: Update `CLAUDE.md`**

In `CLAUDE.md`, find the section starting with `## Cross-Repo Integration` (around line 47). **Insert before it** the following new section (no nested code fences, so a plain 3-backtick wrapper is fine — but to keep this plan parseable the wrapper is 4 backticks):

````markdown
## Workspace-Level Build

A Gradle build at the workspace root orchestrates both submodules behind a uniform task surface:

| Command | What it does |
|---|---|
| `./gradlew install` | `yarn install` in web-components + Maven warm-up (no-op) in flow-components |
| `./gradlew build` | web-components install + `mvn -DskipTests install` in flow-components |
| `./gradlew test` | `yarn test` (changed packages) + `mvn test` |
| `./gradlew clean` | remove `node_modules/` + `mvn clean` |

Use `./gradlew :web-components:<task>` or `./gradlew :flow-components:<task>` to target a single subproject. The submodules remain independently buildable from inside their own directories — the Gradle build is additive, not a replacement.

Build files live at the workspace root (`build.gradle.kts`, `settings.gradle.kts`, `gradle/*.kts`); nothing is added inside the submodules.

````

Also update the existing `## Repository Overview` (lines 5–13): change the bullet `web-components/ — vaadin/web-components (TypeScript/Lit, pnpm/yarn)` to `web-components/ — vaadin/web-components (TypeScript/Lit, Yarn)`.

- [ ] **Step 3: Verify docs render and links resolve**

Run: `grep -n "gradlew" README.md CLAUDE.md`
Expected: shows the new entries in both files.

Run: `ls docs/superpowers/specs/2026-06-04-common-gradle-build-design.md`
Expected: file exists (no errors).

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: document the workspace Gradle build entry points"
```

---

## Self-review checklist (run after all tasks)

- [ ] Every task in the spec's "Implementation Steps" section (1–9) is covered by a task above? Steps 1–6 → Tasks 1–9; Step 7 (E2E verification) → Task 10; Step 8 (docs) → Task 11; Step 9 (final commit) → covered by per-task commits.
- [ ] No `pnpm` references in any new file (run `grep -ri pnpm build.gradle.kts gradle/ settings.gradle.kts gradle.properties` after Task 9 — expected: empty).
- [ ] Aggregate `./gradlew install`, `./gradlew build`, `./gradlew test`, `./gradlew clean` all succeeded in Task 10.
- [ ] Nothing was added inside `web-components/` or `flow-components/` (run `cd web-components && git status` and `cd flow-components && git status` — both expected clean).

---

## What could go wrong

- **`gradle wrapper` requires a system Gradle.** The pre-flight confirmed Gradle 9.2.0 is installed. If absent on another machine, install via Homebrew (`brew install gradle`) or by downloading a 9.x distribution; the version doesn't matter as long as `gradle wrapper` exists.
- **Node plugin requires a network the first time.** Even with `download = false`, the plugin itself is resolved from the Gradle Plugin Portal. If on a restricted network, configure a mirror in `settings.gradle.kts` `pluginManagement`.
- **`yarn` not on PATH inside the YarnTask's exec environment.** The Node plugin uses the system PATH. If a developer has yarn installed via a non-default Node version manager that doesn't set PATH globally, they'll need to invoke Gradle from a shell that has yarn available.
- **`mvn` not on PATH.** Same — listed as workspace prerequisite already.
- **`buildFileName` is deprecated in some Gradle 8.x docs.** It is still functional in 8.10. If a future Gradle removes it, the replacement is `project.buildFile = file(...)` set in settings.gradle.kts.
- **Parallel execution + Maven local repo.** Two Maven processes writing to `~/.m2/repository` simultaneously can in theory race. In our setup only flow-components touches Maven, so there's no concurrent Maven; safe.