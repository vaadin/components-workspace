# Common Gradle Build Design Spec

## Overview

A workspace-root Gradle build that orchestrates the existing `web-components` (Yarn) and `flow-components` (Maven) builds behind a single, uniform task surface. Gradle acts as a thin orchestrator: it does not compile, test, or package anything itself — every task delegates to the submodule's native tooling.

## Goals

1. **Single command to build both submodules** — `./gradlew build` builds web-components and flow-components without the user `cd`-ing between them.
2. **Uniform task surface** — `install`, `build`, `test`, `clean` work the same way regardless of which submodule is underneath.
3. **IntelliJ integration** — Opening the workspace as a Gradle project surfaces both submodules as modules with runnable tasks in the Gradle tool window.

## Non-Goals

- Replacing Yarn or Maven in the submodules. Both remain authoritative; the submodules stay independently buildable from inside their own directories without Gradle.
- Modifying files inside the submodules. The Gradle build lives entirely at the workspace root.
- Cross-repo integration testing / local npm linking between the two submodules. Flow-components continues to consume web-components via the npm registry. Deferred to future work.
- Native Gradle compilation, dependency wiring, or version catalogs.
- CI pipeline integration. Local-dev orchestration only.

## Tooling Choices

| Choice | Decision |
|---|---|
| Gradle version | 8.x (current stable), via Gradle Wrapper |
| DSL | Kotlin DSL (`.gradle.kts`) |
| Structure | Multi-project, two subprojects: `:web-components`, `:flow-components` |
| Yarn invocation | `com.github.node-gradle.node` plugin v7.x with `download = false` |
| Maven invocation | Plain `Exec` task shelling to `mvn` on `PATH` |

## File Layout

```
components-workspace/
├── settings.gradle.kts             # rootProject.name + subproject wiring
├── build.gradle.kts                # root: aggregate tasks + node plugin application
├── gradle.properties               # parallel execution, toolchain hints
├── gradle/
│   ├── web-components.gradle.kts   # yarn install/build/test/clean tasks
│   └── flow-components.gradle.kts  # mvn install/build/test/clean tasks
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── gradlew                         # POSIX wrapper script
├── gradlew.bat                     # Windows wrapper script
├── web-components/                 # submodule (unchanged)
└── flow-components/                # submodule (unchanged)
```

Files committed: everything above except `.gradle/` and `build/` (added to `.gitignore`). The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) is committed.

## Subproject Wiring

`settings.gradle.kts`:

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

`projectDir` points at the submodule directory so that Yarn/Maven run with the correct working directory. `buildFileName` is a path relative to `projectDir`, pointing back out to the `gradle/` directory at the workspace root so no Gradle files end up tracked inside the submodules.

## Task Surface

### Aggregate tasks (root project)

User-facing entry points. Each depends on the same-named task in both subprojects.

| Task | Behavior |
|---|---|
| `./gradlew install` | Runs `:web-components:install` (yarn install) and `:flow-components:install`. |
| `./gradlew build` | Builds both. Each subproject's `build` depends on its own `install`. |
| `./gradlew test` | Runs tests in both. Each subproject's `test` depends on its own `build`. |
| `./gradlew clean` | Cleans both: removes `node_modules/` and `dist/` in web-components and Maven `target/` directories in flow-components. |

### `:web-components` tasks

Uses the Node Gradle plugin's `YarnTask` type. `download = false` so the system-installed Node and Yarn are used (already prerequisites per the workspace README).

Note: web-components has no `build` script in its `package.json` — its components are published as source (ESM, no bundling step). The `:build` task therefore has no action of its own and simply depends on `:install`. This preserves task-surface symmetry with `:flow-components` while reflecting the reality that "building" web-components locally is just installing it.

| Task | Action |
|---|---|
| `install` | `yarn install` |
| `build` | No-action task. Depends on `install`. |
| `test` | `yarn test` (runs tests for changed packages only — the existing default for web-components). Depends on `build`. |
| `clean` | `Delete` `node_modules/` |

### `:flow-components` tasks

Plain `Exec` tasks shelling to `mvn`. `workingDir` is the flow-components submodule. On Windows, the command is `mvn.cmd`; resolved via `OperatingSystem.current().isWindows`.

| Task | Command |
|---|---|
| `install` | No-op placeholder, kept for symmetry with `:web-components`. Maven resolves dependencies transparently on demand. |
| `build` | `mvn -DskipTests install` — uses `install` (not `package`) to populate the local Maven repo, matching existing CLAUDE.md guidance |
| `test` | `mvn test` |
| `clean` | `mvn clean` |

## Implementation Notes

### Node plugin configuration

In `build.gradle.kts` (root):

```kotlin
plugins {
    id("com.github.node-gradle.node") version "7.1.0" apply false
}

project(":web-components") {
    apply(plugin = "base")
    apply(plugin = "com.github.node-gradle.node")
    extensions.configure<com.github.gradle.node.NodeExtension> {
        download.set(false)
        nodeProjectDir.set(projectDir)
    }
}
```

The plugin reads `package.json` and `yarn.lock` from `nodeProjectDir`. `download = false` means the plugin will not provision Node/Yarn; the system installation is used.

### Maven Exec helper

In `gradle/flow-components.gradle.kts`:

```kotlin
fun mvnExec(taskName: String, vararg args: String) = tasks.register<Exec>(taskName) {
    workingDir = projectDir
    val mvn = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "mvn.cmd" else "mvn"
    commandLine(mvn, *args)
}
```

### Parallel execution

`gradle.properties`:

```
org.gradle.parallel=true
org.gradle.java.installations.auto-detect=true
```

The two subprojects have no Gradle-level inter-dependency, so `build`, `test`, and `clean` can run them concurrently. Each subproject's internal task chain (`install` → `build` → `test`) remains sequential.

### Up-to-date checks

- The yarn `install` task declares `yarn.lock` + `package.json` as inputs and `node_modules/` as output, so Gradle skips it when unchanged.
- The Maven tasks declare no inputs/outputs and run every time. Maven's own incremental build behavior handles the inner work efficiently.

### JDK toolchain

Gradle does not compile any Java itself, so no `java { toolchain { ... } }` block is needed at the Gradle level. The Maven build inside `:flow-components` reads `JAVA_HOME` directly. The workspace prerequisite remains JDK 17+.

### Error propagation

Both `Exec` and `YarnTask` fail the Gradle build on non-zero exit. No additional wiring needed.

### No artifact wiring between subprojects

`:flow-components` does not depend on `:web-components`. Builds run independently; flow-components still resolves web-components from the npm registry as today. Local linking is deferred (see Future Work).

## IDE Integration (IntelliJ)

- Open the workspace root in IntelliJ; the importer detects `settings.gradle.kts` and treats it as a Gradle project. Both subprojects appear as Gradle modules with their tasks in the Gradle tool window.
- The existing `.idea/` directory is rewritten by IntelliJ on first Gradle import. Users with custom IDE setup may want to close the project and re-open it via "Open" → root → "Trust Project" so the Gradle importer runs cleanly.
- IntelliJ's Gradle importer does not run Yarn or Maven during import — it only reads the Gradle model. The first `./gradlew install` (or `build`) must run from a terminal or the Gradle tool window to populate `node_modules/` and the Maven local repository.
- The submodules' own IDE hints (existing in their trees) remain valid for users who prefer to open a single submodule directly. The workspace-level Gradle setup is additive.

## Workflows After This Spec

### Fresh clone

```bash
git clone --recurse-submodules <workspace-url>
cd components-workspace
./gradlew install   # yarn install + (mvn warm-up no-op)
./gradlew build     # (no-op for web-components) + mvn -DskipTests install
```

### Run all tests

```bash
./gradlew test
```

### Build one submodule only

```bash
./gradlew :web-components:build
./gradlew :flow-components:build
```

### Clean both

```bash
./gradlew clean
```

The existing direct workflows (`cd web-components && yarn install`, `cd flow-components && mvn install`) continue to work and are unaffected.

## .gitignore Additions

Append to `.gitignore`:

```
.gradle/
build/
```

## Future Work

- **Local npm linking** between `:web-components` and `:flow-components` — would let flow-components consume an unreleased web-components build. Likely via a `:web-components:pack` task producing a tarball consumed via `yarn link` or a `resolutions` entry in flow-components' `package.json`.
- **CI integration** — using these Gradle tasks as the entry point for CI pipelines.
- **Subtree or monorepo migration** — orthogonal to this spec; carried forward from the workspace design spec.

## Implementation Steps

1. Add Gradle Wrapper (`gradle wrapper --gradle-version 8.x`) at the workspace root.
2. Create `settings.gradle.kts` with subproject wiring.
3. Create root `build.gradle.kts` with Node plugin application and aggregate tasks.
4. Create `gradle/web-components.gradle.kts` with yarn tasks.
5. Create `gradle/flow-components.gradle.kts` with Maven Exec tasks.
6. Add `.gradle/` and `build/` to `.gitignore`.
7. Verify `./gradlew install`, `./gradlew build`, `./gradlew test`, `./gradlew clean` all succeed end-to-end.
8. Update the workspace `README.md` and `CLAUDE.md` to document the new Gradle entry points alongside the existing per-submodule commands.
9. Commit.