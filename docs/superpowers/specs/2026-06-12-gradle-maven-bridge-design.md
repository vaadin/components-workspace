# Gradle/Maven Bridge Design Spec

## Overview

A first step toward unifying the workspace's two build systems under Gradle, so that future work can deliver incremental builds that span JS/TS and Java — e.g. a change to a `web-components` package automatically invalidating dependent flow-components IT modules. This step introduces Gradle's awareness of individual Maven modules in `flow-components/` and their interdependencies, with a pilot covering one component end-to-end (`vaadin-button-flow-parent`) plus the shared modules.

Gradle drives the build for pilot modules natively (no `mvn` invocation at build time). Module structure and dependencies are read from existing `pom.xml` files — POMs remain the source of truth; nothing is duplicated into Gradle DSL. The existing `mvn`-based build continues to operate over non-pilot modules within the same `./gradlew :flow-components:build` invocation, so the workspace stays buildable end-to-end at all times.

## Goals

1. **Gradle-native build for pilot modules.** No `mvn` shells, no `mvn`-API embedding. Gradle's `java-library`/`war` plugins compile and package; `Test` tasks run tests.
2. **POMs are the source of truth.** Module list, interdependencies, properties, plugin configurations — all read from `pom.xml` at configuration time via the Maven Model Builder.
3. **Per-module Gradle subprojects** mirroring the Maven reactor structure (e.g. `:flow-components:vaadin-button-flow-parent:vaadin-button-flow`).
4. **Complementary, not parallel.** Gradle's pilot work writes to Maven's `target/` layout and publishes to Maven Local, so the subsequent `mvn install` over the full reactor observes finished work and skips compile/jar steps for pilot modules.
5. **Foundation for npm packages later.** Layer 1 is fully generic; Vaadin-specific conventions live in Layer 2; future npm conventions can sit alongside Layer 2 with the same shape.

## Non-Goals

- Replacing `mvn` across all of `flow-components` (only the pilot scope).
- Replacing the Vaadin Maven plugin (`flow-maven-plugin`) globally. The pilot IT uses the official Vaadin Gradle plugin; the rest of `flow-components` continues to use the Maven plugin.
- Cross-language incremental triggers (web-components JS source → flow-components IT). The pilot lays foundations; the actual wiring is a follow-up step.
- Modifying files inside the submodules. All new files live at the workspace root.
- Modeling intermediate Maven `*-parent` aggregator modules as full Gradle subprojects. They exist as implicit Gradle project-tree placeholders only.
- Caching `target/` directories in CI. Possible later optimization.

## Pilot Scope

Five leaf Maven modules:

```
flow-components/
├── vaadin-flow-components-shared-parent/
│   ├── vaadin-flow-components-base/                  ← in pilot
│   └── vaadin-flow-components-test-util/             ← in pilot
└── vaadin-button-flow-parent/
    ├── vaadin-button-flow/                           ← in pilot
    ├── vaadin-button-testbench/                      ← in pilot
    └── vaadin-button-flow-integration-tests/         ← in pilot
```

All other flow-components modules continue to be built only by Maven during the existing `mvn install` over the full reactor.

## Architecture

A new included Gradle build at `gradle/plugins/` hosts a two-layer plugin pair:

- **Layer 1 (`workspace.maven-module`)** — generic Maven adapter. Reads a project's `pom.xml`, populates a shared GAV→Gradle-path registry, configures repositories, redirects outputs to Maven's `target/` layout, applies `maven-publish` for `publishToMavenLocal`, and defers cross-module dependency wiring until all subprojects have registered. No Vaadin knowledge.
- **Layer 2 (`vaadin.workspace.*`)** — Vaadin-specific conventions. Two plugin IDs:
  - `vaadin.workspace.java-library` — applies Layer 1 + `java-library` + Spotless + Checkstyle + toolchain + `useJUnitPlatform()` for unit tests.
  - `vaadin.workspace.integration-tests` — applies `java-library` (transitively) + `war` + Vaadin Gradle plugin + Gretty + an `integrationTest` task (JUnit 4) with Gretty's Jetty lifecycle.

The workspace's `settings.gradle.kts` is plain Kotlin: `includeBuild("gradle/plugins")` plus a small loop walking `gradle/flow-components/**/*.gradle.kts`, emitting one `include(...)` per file. Each per-subproject `.gradle.kts` applies one Layer 2 plugin. No settings-time plugin scanning; the per-subproject files are the authoritative scope signal.

### File layout

```
components-workspace/
├── settings.gradle.kts                          # plain Kotlin: includeBuild + glob loop
├── gradle/
│   ├── plugins/                                 # included build
│   │   ├── settings.gradle.kts
│   │   ├── build.gradle.kts                     # deps: maven-model-builder, maven-resolver, Vaadin Gradle plugin, Gretty, Spotless
│   │   └── src/main/kotlin/
│   │       ├── workspace/maven/                 # Layer 1 — generic
│   │       │   ├── MavenModuleProjectPlugin.kt  # id: workspace.maven-module
│   │       │   ├── PomReader.kt
│   │       │   ├── GavRegistry.kt
│   │       │   └── DependencyMapper.kt
│   │       └── vaadin/workspace/                # Layer 2 — Vaadin conventions
│   │           ├── JavaLibraryConventionPlugin.kt        # id: vaadin.workspace.java-library
│   │           └── IntegrationTestsConventionPlugin.kt   # id: vaadin.workspace.integration-tests
│   ├── flow-components/                         # NEW: one *.gradle.kts per in-scope leaf
│   │   ├── vaadin-flow-components-shared-parent/
│   │   │   ├── vaadin-flow-components-base.gradle.kts
│   │   │   └── vaadin-flow-components-test-util.gradle.kts
│   │   └── vaadin-button-flow-parent/
│   │       ├── vaadin-button-flow.gradle.kts
│   │       ├── vaadin-button-testbench.gradle.kts
│   │       └── vaadin-button-flow-integration-tests.gradle.kts
│   ├── flow-components.gradle.kts               # existing — extended (see below)
│   └── web-components.gradle.kts                # unchanged
└── flow-components/                              # submodule unchanged
```

### `settings.gradle.kts` (workspace root)

```kotlin
import java.io.File

pluginManagement {
    includeBuild("gradle/plugins")
}

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

val gradleModulesDir = file("gradle/flow-components")
gradleModulesDir.walkTopDown()
    .filter { it.isFile && it.name.endsWith(".gradle.kts") }
    .forEach { buildFile ->
        val rel = buildFile.relativeTo(gradleModulesDir).path.removeSuffix(".gradle.kts")
        val gradlePath = ":flow-components:" + rel.replace(File.separatorChar, ':')
        include(gradlePath)
        project(gradlePath).apply {
            projectDir = file("flow-components/$rel")
            buildFileName = buildFile.absolutePath
        }
    }
```

### Per-subproject build files

Each file applies one Layer 2 plugin; no further configuration is normally needed.

```kotlin
// gradle/flow-components/vaadin-button-flow-parent/vaadin-button-flow.gradle.kts
plugins { id("vaadin.workspace.java-library") }
```

```kotlin
// gradle/flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests.gradle.kts
plugins { id("vaadin.workspace.integration-tests") }
```

### `gradle/flow-components.gradle.kts` (existing — extended)

```kotlin
import org.gradle.internal.os.OperatingSystem

plugins {
    id("workspace.maven-module")    // parse flow-components/pom.xml for properties, repos, dep-mgmt
}

val mvnCommand = if (OperatingSystem.current().isWindows) "mvn.cmd" else "mvn"

tasks.register("install") {
    description = "No-op placeholder, kept for task-surface symmetry."
    group = "build"
}

tasks.register<Exec>("build") {
    description = "Pilot modules via Gradle, then mvn -DskipTests install on the whole flow-components reactor."
    group = "build"
    workingDir = projectDir
    commandLine(mvnCommand, "-DskipTests", "install")
    dependsOn("install")
    dependsOn(rootProject.subprojects
        .filter { it.path.startsWith(":flow-components:") }
        .filter {
            it.plugins.hasPlugin("vaadin.workspace.java-library") ||
            it.plugins.hasPlugin("vaadin.workspace.integration-tests")
        }
        .map { "${it.path}:publishToMavenLocal" })
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

val syncFlowOverlays = tasks.register<Exec>("syncFlowOverlays") {
    description = "Materializes workspace-tracked flow-components overlay package.json files as symlinks inside the submodule."
    group = "build"
    workingDir = rootDir
    commandLine("bash", "scripts/sync-flow-overlays.sh")
}

tasks.named("install") {
    dependsOn(syncFlowOverlays, ":npmInstall")
}
```

The added `dependsOn(":npmInstall")` makes `:flow-components:build` self-sufficient: a single command sets up overlays, runs workspace npm install, builds pilot modules via Gradle, publishes them to Maven Local, and runs `mvn install` over the full reactor.

## Layer 1 — `workspace.maven-module`

A `Plugin<Project>` applied to any project that mirrors a Maven module. Single plugin, no settings counterpart. Generic — knows nothing about Vaadin.

### PomReader

Wraps `org.apache.maven:maven-model-builder` with a network-fetching `ModelResolver` (via `org.apache.maven.resolver:maven-resolver-impl` + `maven-resolver-transport-http`) configured with two hard-coded repositories:

- `https://repo.maven.apache.org/maven2` (Maven Central)
- `https://maven.vaadin.com/vaadin-prereleases`

This is the only place Vaadin-specific knowledge leaks into Layer 1; documented and accepted. Resolved POMs cache in `~/.m2/repository` via the resolver's standard mechanism.

In-build parent inheritance is resolved via the model builder's standard `<relativePath>` handling. External parents (e.g. `vaadin-parent`) are resolved over the network on first parse and cached in `~/.m2` thereafter.

Extracted from the resolved model:

- GAV (`groupId`, `artifactId`, `version`)
- `packaging`
- `properties` (including `maven.compiler.release`, `spotless.licence-header`, `checkstyle.version`, `jetty.http.port`, `jetty.stop.port`, etc.)
- `dependencies[]` with `<scope>`, `<optional>`, `<type>`
- `dependencyManagement` entries (for BOM `import` translation)
- `repositories[]`
- `build/plugins[]` configurations — surfaced as raw `Xpp3Dom` on the model; consumers (Layer 2) interpret on demand

Standard Maven source layout is assumed. Non-default profiles are read but ignored — only the default profile (the one `mvn install` activates with no `-P` flag) is honored.

A per-`pom.xml` cache lives on `Gradle.extensions` so siblings don't re-parse parent POMs.

### GavRegistry

A Gradle `BuildService<BuildServiceParameters.None>` keyed on `rootProject.gradle`:

```kotlin
interface GavRegistry : BuildService<BuildServiceParameters.None> {
    fun register(groupId: String, artifactId: String, projectPath: String)
    fun lookup(groupId: String, artifactId: String): String?
    val isFinalized: AtomicBoolean
}
```

Registration happens synchronously inside `MavenModuleProjectPlugin.apply()`. Lookup is deferred to the cross-project dependency wiring step (below). `isFinalized` flips to `true` inside `gradle.projectsEvaluated`; late lookups after that point surface as build errors instead of silently treating GAVs as external coordinates.

`BuildService` was picked over `rootProject.extra` because it's the documented Gradle pattern for cross-project shared state, survives configuration caching, and is thread-safe for parallel configuration.

### DependencyMapper

Pure function: `(PomDependency, GavRegistry) -> GradleDependencySpec`.

Scope mapping (POM → Gradle):

| POM scope | Gradle configuration |
|---|---|
| `compile` | `implementation` |
| `runtime` | `runtimeOnly` |
| `test` | `testImplementation` |
| `provided` | `compileOnly` + `testImplementation` |
| `system` | error |

`<optional>true</optional>` is logged as a warning and otherwise skipped (no clean Gradle equivalent; pilot modules don't use it).

GAV resolution:
- `registry.lookup(g, a)` returns a path → emit `ProjectRef(scope, path)`, materialized as `implementation(project(":path"))` etc.
- Returns null → emit `External(scope, "g:a${version?":$version"}")`. Version omitted when POM doesn't declare one (dependency management resolves it via the Vaadin BOM `platform(...)`).

BOM imports (`<scope>import</scope>` + `<type>pom</type>` in `dependencyManagement`) translate to `implementation(platform("g:a:v"))`. The flow-components root POM imports `vaadin-bom` this way.

### MavenModuleProjectPlugin

```kotlin
class MavenModuleProjectPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val model = PomReader.read(project.projectDir.resolve("pom.xml"))
        val registry = project.gradle.sharedServices
            .registerIfAbsent("gavRegistry", GavRegistry::class) {}
        registry.get().register(model.groupId, model.artifactId, project.path)

        // 1. Repositories (project-side, for Gradle dep resolution).
        project.repositories.mavenLocal()
        project.repositories.mavenCentral()
        model.repositories.forEach { repo ->
            project.repositories.maven { url = uri(repo.url) }
        }

        // 2. Expose model so the build file (or Layer 2) can read it.
        project.extensions.add("mavenModel", model)

        // 3. Maven-style output layout + maven-publish, deferred until `java` plugin is applied.
        project.plugins.withId("java") {
            project.extensions.configure<SourceSetContainer> {
                named("main") {
                    java.destinationDirectory.set(project.file("target/classes"))
                    output.resourcesDir = project.file("target/classes")
                }
                named("test") {
                    java.destinationDirectory.set(project.file("target/test-classes"))
                    output.resourcesDir = project.file("target/test-classes")
                }
            }
            project.tasks.named<Jar>("jar") {
                destinationDirectory.set(project.file("target"))
                archiveBaseName.set(model.artifactId)
                archiveVersion.set(model.version)
            }
            project.tasks.named<Delete>("clean") {
                delete(project.file("target"))
            }
            project.plugins.apply("maven-publish")
            project.extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("maven") {
                    from(project.components["java"])
                    groupId = model.groupId
                    artifactId = model.artifactId
                    version = model.version
                }
            }
        }

        // For war modules: extend the war task's output, update the publication to use the web component.
        project.plugins.withId("war") {
            project.tasks.named<War>("war") {
                destinationDirectory.set(project.file("target"))
                archiveBaseName.set(model.artifactId)
                archiveVersion.set(model.version)
            }
            project.extensions.configure<PublishingExtension> {
                publications.named<MavenPublication>("maven") {
                    setComponents(project.components["web"])
                }
            }
        }

        // 4. Defer dependency wiring until ALL subprojects have registered.
        if (project.gradle.extensions.findByName("mavenModuleDepsHookInstalled") == null) {
            project.gradle.extensions.add("mavenModuleDepsHookInstalled", true)
            project.gradle.projectsEvaluated {
                registry.get().isFinalized.set(true)
                rootProject.allprojects
                    .filter { it.extensions.findByName("mavenModel") != null }
                    .forEach { p ->
                        val pModel = p.extensions.getByName("mavenModel") as MavenModel
                        val mapper = DependencyMapper(registry.get())
                        pModel.dependencies.forEach { dep ->
                            val spec = mapper.map(dep)
                            p.dependencies.add(spec.configuration, when (spec) {
                                is ProjectRef -> p.project(spec.projectPath)
                                is External   -> spec.coordinate
                            })
                        }
                    }
            }
        }
    }
}
```

A few specifics:

- The `plugins.withId("java")` / `plugins.withId("war")` deferral makes output redirection compose cleanly regardless of which Java-ecosystem plugin a caller applies on top.
- `pom`-packaged modules (e.g. `:flow-components` itself, applied directly in `gradle/flow-components.gradle.kts`) get repositories + the `mavenModel` extension but no Java/war configuration.
- The cross-project dependency wiring runs once, in `gradle.projectsEvaluated`, iterating all projects that have `mavenModel`. Other Gradle projects (e.g. `:web-components`) are ignored.

### Public surface

Anything that applies `workspace.maven-module` — per-subproject files, Layer 2 conventions, `gradle/flow-components.gradle.kts` — has access to:

- `project.extensions.getByName("mavenModel") as MavenModel` — the resolved model
- Repositories and dependency wiring happen automatically.

## Layer 2 — Vaadin convention plugins

Two plugins. Both live in `vaadin/workspace/`.

### `vaadin.workspace.java-library`

For `-flow`, `-testbench`, and shared modules. Applies:

- `workspace.maven-module` (Layer 1) — idempotent
- `java-library`
- `com.diffplug.spotless`
- Gradle's built-in `checkstyle`

Configures:

- **Toolchain**: `JavaPluginExtension.toolchain.languageVersion = JavaLanguageVersion.of(model.compilerRelease)`. Root POM sets `maven.compiler.release = 21`.
- **Source sets**: Maven's standard layout is Gradle's default already. `src/main/resources/META-INF/resources/frontend/**` (JS connectors) is picked up via the default `resources` source dir.
- **`test` task**: `useJUnitPlatform()` (JUnit 6, matching Surefire's config in the root POM).
- **Spotless**: license header from `mavenModel.property("spotless.licence-header")`; Java + Kotlin formatters mirroring the Maven plugin's defaults.
- **Checkstyle**: `configFile = rootDir.resolve("flow-components/checkstyle/checkstyle.xml")`; tool version pinned via `mavenModel.property("checkstyle.version")`.

### `vaadin.workspace.integration-tests`

For `*-flow-integration-tests` only. Applies:

- `vaadin.workspace.java-library` (which pulls in Layer 1 + base config)
- `war`
- `com.vaadin` — the Vaadin Gradle plugin (version pinned in `gradle/plugins/build.gradle.kts`)
- `org.gretty` — Gretty for the Jetty dev server

Configures:

- **`integrationTest` task**: separate from `test`. JUnit 4 via `useJUnit()`. `dependsOn("vaadinBuildFrontend")`. Lifecycle bracketed by Gretty's `appBeforeIntegrationTest` / `appAfterIntegrationTest`, the standard pattern for Jetty around test tasks.
- **Vaadin Gradle plugin extension** populated from `mavenModel.pluginConfiguration("com.vaadin:flow-maven-plugin")`:

| POM `<configuration>` key | Vaadin Gradle extension property |
|---|---|
| `frontendDirectory` | `frontendDirectory` |
| `productionMode` | `productionMode` |
| `pnpmEnable` (from `vaadin.pnpm.enable`) | `pnpmEnable` |
| `nodeAutoUpdate` | `nodeAutoUpdate` |
| `npmFolder` | `npmFolder` |

Translation is best-effort; unknown POM keys are logged at debug level. A subproject's `.gradle.kts` may override the extension settings after the `plugins {}` block.

- **Gretty extension**: `httpPort = mavenModel.property("jetty.http.port")?.toIntOrNull() ?: 8080`; `stopPort = mavenModel.property("jetty.stop.port")?.toIntOrNull() ?: 9999`.
- **War packaging**: handled by the `war` plugin. The root POM's `<webResources>` config is ignored for now (button IT doesn't override it).

### `gradle/plugins/build.gradle.kts`

```kotlin
plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven { url = uri("https://maven.vaadin.com/vaadin-prereleases/") }
}

dependencies {
    implementation("org.apache.maven:maven-model-builder:3.9.9")
    implementation("org.apache.maven.resolver:maven-resolver-impl:1.9.22")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.22")
    implementation("com.vaadin:flow-gradle-plugin:25.3-SNAPSHOT")
    implementation("org.gretty:gretty:4.1.6")
    implementation("com.diffplug.spotless:spotless-plugin-gradle:6.25.0")
}

gradlePlugin {
    plugins {
        register("workspaceMavenModule") {
            id = "workspace.maven-module"
            implementationClass = "workspace.maven.MavenModuleProjectPlugin"
        }
        register("vaadinWorkspaceJavaLibrary") {
            id = "vaadin.workspace.java-library"
            implementationClass = "vaadin.workspace.JavaLibraryConventionPlugin"
        }
        register("vaadinWorkspaceIntegrationTests") {
            id = "vaadin.workspace.integration-tests"
            implementationClass = "vaadin.workspace.IntegrationTestsConventionPlugin"
        }
    }
}
```

The `vaadin-prereleases` URL in this file is local to the included build's own dependency resolution — needed to fetch `com.vaadin:flow-gradle-plugin`. It does not leak into the workspace's main build configuration.

The Vaadin Gradle plugin version is pinned manually to match `flow.version` from the flow-components root POM (`25.3-SNAPSHOT`). Future automation could read it from `mavenModel.property("flow.version")` in settings; out of scope for this step.

## Build/Test Task Surface

### Per-subproject tasks

`vaadin.workspace.java-library` subprojects expose the standard `java-library` lifecycle plus Spotless and Checkstyle tasks:

| Task | Behavior |
|---|---|
| `build` | compile + jar + test |
| `compileJava` / `compileTestJava` | standard Java compile |
| `test` | JUnit 6 unit tests |
| `jar` | produces `target/<artifactId>-<version>.jar` |
| `publishToMavenLocal` | installs jar into `~/.m2/repository/...` |
| `clean` | wipes `build/` and `target/` |
| `spotlessCheck` / `spotlessApply` | format check / apply |
| `checkstyleMain` / `checkstyleTest` | style checks |

`vaadin.workspace.integration-tests` subprojects add:

| Task | Behavior |
|---|---|
| `war` | produces `target/<artifactId>-<version>.war` |
| `vaadinBuildFrontend` / `vaadinPrepareFrontend` | frontend bundle |
| `appRun` / `appStart` / `appStop` | manual Jetty control via Gretty |
| `integrationTest` | JUnit 4 ITs, auto-starts/stops Jetty |
| `publishToMavenLocal` | installs war into `~/.m2/repository/...` |

### Aggregate behavior

`./gradlew :flow-components:build` now means: pilot Gradle subprojects publish to Maven Local, then `mvn -DskipTests install` runs over the full reactor. Maven sees pilot modules' `target/` already populated and skips their compile/jar/install work (timestamp-based).

`./gradlew :flow-components:test` continues to run `mvn test` across the full reactor. Pilot modules' tests run twice in this command (Gradle has already run them via `:flow-components:<...>:test`); duplicate accepted in step 1, pruned in a follow-up.

`./gradlew :flow-components:clean` runs `mvn clean` across the full reactor, which wipes all `target/` directories (Maven's and Gradle's outputs share the same dirs). Pilot subprojects' `gradle clean` also wipes `target/` for completeness.

### Local dev workflow

```bash
# One-time bootstrap (or after dep changes):
./gradlew :flow-components:build       # pilot via Gradle + mvn install on the rest

# Iterate on button via Gradle:
./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow:test
./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow-integration-tests:integrationTest
```

Interleaving Gradle and Maven iterations may cause one tool to redo work the other already did (timestamps disagree). Recommendation: pick one front per iteration cycle. Not a correctness issue.

### Frontend bundle path collisions (pilot IT)

The Vaadin Gradle plugin and the `flow-maven-plugin` write frontend bundles to overlapping paths. Best case: aligned timestamps deduplicate. Likely case: one tool detects something stale and regenerates anyway — no harm, just lost dedupe. Worst case: outputs collide and break one or both tools.

Safety net (documented; not implemented in step 1): a finalizer task `cleanBundleAfterIntegrationTest` on the IT subproject would remove `target/frontend/`, `target/classes/META-INF/VAADIN/`, and `frontend/generated/` after the Gradle IT run, forcing Maven to regenerate cleanly. Enable if collisions surface.

## CI Integration

### Install job runs the build

The existing `install` job in `.github/workflows/validation.yml` has its command updated:

```yaml
- name: Workspace install
  if: steps.cache.outputs.cache-hit != 'true'
  run: ./gradlew :flow-components:build --no-daemon
```

Because `:flow-components:install` now `dependsOn(":npmInstall")`, this single command runs:

1. `:npmInstall` (workspace-wide npm install + sync overlays)
2. Pilot subprojects' `publishToMavenLocal` (per Section 4 wiring)
3. `mvn -DskipTests install` on the full reactor

After the step completes, the existing install cache (`~/.m2/repository/com/vaadin`, `node_modules`, etc.) captures everything — Maven Local is now genuinely populated, not just nominally cached.

Downstream jobs benefit:
- `flow-components-unit` (`mvn test`): dep resolution is instant; only compile + test execution remains.
- `flow-components-its` (`mvn verify -am -pl <shard>`): `-am` is mostly a no-op; just builds the shard + runs IT.

### New `flow-components-pilot-gradle` job

Validates the Gradle pilot path independently of the Maven path.

```yaml
flow-components-pilot-gradle:
  name: Flow Components Pilot (Gradle)
  needs: install
  runs-on: ubuntu-latest
  timeout-minutes: 30
  steps:
    - uses: actions/checkout@v6
      with:
        submodules: recursive
        fetch-depth: 1

    - uses: ./.github/actions/setup-workspace
      with:
        cache-key: ${{ needs.install.outputs.cache-key }}
        setup-java: 'true'
        sync-overlays: 'true'

    - uses: ./.github/actions/install-tb-license
      with:
        tb-license: ${{ secrets.TB_LICENSE }}

    - name: Run pilot Gradle tasks
      run: |
        ./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow:test \
                  :flow-components:vaadin-button-flow-parent:vaadin-button-flow-integration-tests:integrationTest \
                  --stacktrace --info

    - name: Upload pilot test reports
      if: always()
      uses: actions/upload-artifact@v6
      with:
        name: pilot-gradle-reports
        path: |
          flow-components/**/target/surefire-reports/TEST-*.xml
          flow-components/**/target/failsafe-reports/TEST-*.xml
        retention-days: 1
        if-no-files-found: ignore
```

Added to the `results` job's `needs` list so red builds report it.

Job-name convention (per existing memory): `<submodule>-<purpose>`, full names, no abbreviations.

### Duplicate execution accepted

In step 1, the same button code is exercised by `flow-components-unit` + `flow-components-its` (Maven) and `flow-components-pilot-gradle` (Gradle). Wallclock cost: a few minutes for the pilot. Pruned in a follow-up once Gradle parity is confirmed.

## Plugin Testing

### Unit tests (`gradle/plugins/src/test/`)

- `PomReader`: parses fixture POMs correctly; resolves in-build parent inheritance; surfaces properties, dep management, plugin configurations; degrades gracefully when an external parent isn't in `~/.m2`.
- `GavRegistry`: register/lookup, finalization flag.
- `DependencyMapper`: all scope mappings; GAV→ProjectRef vs External; BOM import → `platform(...)`.

### Functional tests (`gradle/plugins/src/functionalTest/`)

Synthetic Maven reactor fixture: one parent POM, two leaf modules (one depending on the other), one IT-like module. Tests assert:

- `./gradle <module>:build` succeeds.
- In-build deps resolve as `project(":...")`.
- Out-of-scope deps resolve as external coordinates.
- `target/classes`, `target/<artifact>.jar` appear at the right paths.
- `publishToMavenLocal` produces expected `~/.m2` entries.

Vaadin Gradle plugin + Gretty are not exercised in functional tests; covered by the end-to-end smoke test.

### End-to-end smoke test

Manual, part of implementation acceptance:

1. Fresh checkout. Run `./gradlew :flow-components:build`. Confirm pilot modules built via Gradle, non-pilot via Maven.
2. Run `./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow-integration-tests:integrationTest`. Confirm it passes.

## Implementation Steps

Each step lands a working state.

1. **Scaffolding** — Create `gradle/plugins/` included build with `settings.gradle.kts`, `build.gradle.kts`, empty plugin registrations. Wire `pluginManagement { includeBuild("gradle/plugins") }` into root `settings.gradle.kts`. `./gradlew tasks` still works.
2. **Layer 1 logic** — `PomReader`, `GavRegistry`, `DependencyMapper`. Unit-tested in isolation.
3. **`workspace.maven-module` project plugin** — wires the logic into a Gradle project. `apply` reads the POM, registers the GAV, exposes `mavenModel`, configures repositories. No output redirection yet.
4. **Apply Layer 1 to `:flow-components`** — Update `gradle/flow-components.gradle.kts` to apply `workspace.maven-module`. Existing mvn-shell tasks untouched.
5. **`vaadin.workspace.java-library`** without output redirection — Apply to `vaadin-flow-components-base.gradle.kts`. Verify Gradle compiles, runs `test`, Spotless and Checkstyle run, jar lands in `build/libs/`.
6. **Output redirection in Layer 1** — Outputs go to `target/`. `clean` extended. Re-run step 5's module.
7. **`maven-publish` + `publishToMavenLocal`** — Re-run step 5's module; verify `~/.m2` populated.
8. **Cross-module dep wiring** — Apply `vaadin.workspace.java-library` to `vaadin-flow-components-test-util` (depends on `-base`). Verify `gradle.projectsEvaluated` hook wires `project(":...")` correctly.
9. **Button JARs** — `vaadin-button-flow.gradle.kts`, `vaadin-button-testbench.gradle.kts`. Verify mixed in-pilot (`project(":...")`) + out-of-pilot (external coord from Maven Local / vaadin-prereleases) dep resolution.
10. **`vaadin.workspace.integration-tests`** — Apply to `vaadin-button-flow-integration-tests.gradle.kts`. Vaadin Gradle plugin extension populated from POM; Gretty configured; `integrationTest` task wired. Run end-to-end; verify button IT passes.
11. **Wire `:flow-components:build`** — Add `dependsOn(":npmInstall")` to `:flow-components:install`; add `dependsOn(pilotPublishToMavenLocal)` to `:flow-components:build`. Verify single-command behavior.
12. **CI changes** — Update install job's command. Add `flow-components-pilot-gradle` job. Add to `results` job's `needs`.
13. **Documentation** — Update workspace `CLAUDE.md` and `flow-components/CLAUDE.md` with new entry points + bootstrap requirement.
14. **Commit.** One PR; reviewable size; rollback-safe because everything is additive until step 11.

## Risks

| # | Risk | Likelihood | Mitigation |
|---|---|---|---|
| 1 | Vaadin Gradle plugin can't fully replace `flow-maven-plugin` for the button IT (missing config keys, behavioral gaps) | Medium | Spike step 10 before committing the rest. Fallback: drop IT from pilot for this step; JAR-side dedupe still ships. |
| 2 | Maven and Gradle javac produce slightly different bytecode/manifests, causing Maven to redo work | Low | Standard javac is identical across both. Manifest divergences are configurable. Align Gradle's `jar` manifest if needed. |
| 3 | Frontend bundle path collision in IT module | Medium | Safety net documented: enable `cleanBundleAfterIntegrationTest` finalizer if it surfaces. |
| 4 | `~/.m2` cache miss during ModelResolver parent resolution on fresh checkouts | Medium | Network-fetching ModelResolver downloads on first parse and caches. First-build network requirement documented. CI restores cache. |
| 5 | `gradle.projectsEvaluated` deferred dep wiring conflicts with another plugin expecting deps at config time | Low | Pilot scope uses `java-library`, `war`, Vaadin Gradle plugin, Gretty, Spotless, Checkstyle — none read dep configurations during the affected window. Verified in step 5. |
| 6 | Maven and Gradle interleaving wipes each other's incremental state mid-iteration | Low | Documented: pick one front per iteration cycle. No code mitigation. |
| 7 | Vaadin Gradle plugin's pinned version drifts from `flow.version` | Low | Pinned manually; tracked alongside the submodule's flow version. Future automation reads it from `mavenModel` at settings time. |
| 8 | Unit test execution duplicated across Gradle and Maven jobs in CI | Low (scope) | Localized to pilot scope. Pruned in a follow-up step. |
| 9 | Install job wallclock grows significantly on cache miss (npm only → npm + full mvn install) | Medium | One-time cost per cache miss; amortized across downstream jobs which no longer need `-am` work. Cache hits stay fast. |

## Open Questions

- Whether intermediate `*-parent` POM-packaging modules should be modeled as Gradle subprojects. Current decision: no (their POMs are nearly empty). Revisit if a `*-parent` POM gains substantive content.
- Whether `vaadin.workspace.java-library` should publish javadoc + sources jars (Maven build does for releases). Out of scope for step 1.

## Definition of Done

The first step is complete when:

1. `./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow:test` passes against a fresh `~/.m2` populated by a prior `./gradlew :flow-components:build`.
2. `./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow-integration-tests:integrationTest` passes end-to-end.
3. `./gradlew :flow-components:build` performs pilot via Gradle + non-pilot via mvn in one command without errors.
4. `./gradlew :flow-components:test` continues to pass (acknowledging duplicate pilot test execution).
5. CI green on the branch, including the new `flow-components-pilot-gradle` job.

## Future Work

- **JS source → IT incremental triggers.** Wire file-level changes in `web-components/packages/` to invalidate dependent flow-components IT subprojects.
- **Expand pilot scope.** Each new component is mechanical: add per-leaf `.gradle.kts` files.
- **Prune duplicate test execution.** Exclude pilot modules from mvn surefire/failsafe invocations once Gradle parity is proven.
- **Cache `target/` directories in CI.** Possible if downstream jobs become bottlenecked on compile work.
- **Automate Vaadin Gradle plugin version from `flow.version`.** Read it from the resolved root POM at settings time, eliminating manual pinning.
- **Eliminate the mvn-driven path entirely.** Viable once pilot covers the full reactor.
- **Extract Layer 1 (`workspace.maven-module`) as a reusable plugin.** Configurable repository list; usable by other workspaces with a Maven reactor.