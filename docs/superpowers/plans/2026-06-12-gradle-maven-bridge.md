# Gradle/Maven Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land a two-layer Gradle plugin pair that models a pilot subset of `flow-components` Maven modules as native Gradle subprojects (driven by POM data), with outputs sharing Maven's `target/` layout so the existing `mvn` build complements (not duplicates) Gradle's work.

**Architecture:** New included Gradle build at `gradle/plugins/`. Layer 1 (`workspace.maven-module`) is a generic Maven adapter that parses POMs via `maven-model-builder`, registers GAVs in a `BuildService`, configures repositories, and defers cross-module dep wiring to `gradle.projectsEvaluated`. Layer 2 (`vaadin.workspace.java-library`, `vaadin.workspace.integration-tests`) adds Vaadin-specific conventions on top. Per-subproject `*.gradle.kts` files under `gradle/flow-components/` are the authoritative scope signal; the workspace `settings.gradle.kts` walks them with a plain Kotlin loop.

**Tech Stack:** Kotlin DSL, Gradle 8.x, `org.apache.maven:maven-model-builder` 3.9.9, `org.apache.maven.resolver:maven-resolver-impl` 1.9.22, `com.vaadin:flow-gradle-plugin` 25.3-SNAPSHOT, `org.gretty:gretty` 4.1.6, `com.diffplug.spotless:spotless-plugin-gradle` 6.25.0, Gradle TestKit, JUnit 5.

**Reference spec:** `docs/superpowers/specs/2026-06-12-gradle-maven-bridge-design.md`.

---

## File Structure

**New files (created by this plan):**

- `gradle/plugins/settings.gradle.kts` — included build's settings
- `gradle/plugins/build.gradle.kts` — Kotlin DSL, deps, plugin registrations
- `gradle/plugins/src/main/kotlin/workspace/maven/MavenModel.kt` — data classes for the parsed model
- `gradle/plugins/src/main/kotlin/workspace/maven/PomReader.kt` — wraps maven-model-builder
- `gradle/plugins/src/main/kotlin/workspace/maven/GavRegistry.kt` — BuildService
- `gradle/plugins/src/main/kotlin/workspace/maven/DependencyMapper.kt` — pure POM-dep → Gradle-dep function
- `gradle/plugins/src/main/kotlin/workspace/maven/MavenModuleProjectPlugin.kt` — Layer 1 plugin
- `gradle/plugins/src/main/kotlin/vaadin/workspace/JavaLibraryConventionPlugin.kt` — Layer 2
- `gradle/plugins/src/main/kotlin/vaadin/workspace/IntegrationTestsConventionPlugin.kt` — Layer 2
- `gradle/plugins/src/test/kotlin/workspace/maven/PomReaderTest.kt`
- `gradle/plugins/src/test/kotlin/workspace/maven/GavRegistryTest.kt`
- `gradle/plugins/src/test/kotlin/workspace/maven/DependencyMapperTest.kt`
- `gradle/plugins/src/test/resources/poms/simple.xml` — fixture
- `gradle/plugins/src/test/resources/poms/with-parent.xml` — fixture
- `gradle/plugins/src/functionalTest/kotlin/workspace/maven/MavenModuleProjectPluginTest.kt`
- `gradle/flow-components/vaadin-flow-components-shared-parent/vaadin-flow-components-base.gradle.kts`
- `gradle/flow-components/vaadin-flow-components-shared-parent/vaadin-flow-components-test-util.gradle.kts`
- `gradle/flow-components/vaadin-button-flow-parent/vaadin-button-flow.gradle.kts`
- `gradle/flow-components/vaadin-button-flow-parent/vaadin-button-testbench.gradle.kts`
- `gradle/flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests.gradle.kts`

**Modified files:**

- `settings.gradle.kts` — add `pluginManagement { includeBuild("gradle/plugins") }` and the glob loop
- `gradle/flow-components.gradle.kts` — apply `workspace.maven-module`, wire `:npmInstall` + pilot `publishToMavenLocal` deps
- `.github/workflows/validation.yml` — update install job command, add `flow-components-pilot-gradle` job
- `CLAUDE.md` (workspace) — document new entry points + bootstrap requirement
- `.gitignore` — ensure `build/` and `gradle/plugins/build/` are ignored (already covered if `build/` is)

---

## Task 1: Scaffolding — Gradle plugin included build skeleton

**Files:**
- Create: `gradle/plugins/settings.gradle.kts`
- Create: `gradle/plugins/build.gradle.kts`
- Modify: `settings.gradle.kts:1-13` (root workspace)

- [ ] **Step 1: Create the included build's `settings.gradle.kts`**

Write to `gradle/plugins/settings.gradle.kts`:

```kotlin
rootProject.name = "components-workspace-plugins"
```

- [ ] **Step 2: Create the included build's `build.gradle.kts`**

Write to `gradle/plugins/build.gradle.kts`:

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

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        // Plugins will be registered as they are introduced in later tasks.
    }
}
```

- [ ] **Step 3: Wire the included build into the workspace `settings.gradle.kts`**

Modify `settings.gradle.kts` to add a `pluginManagement` block at the very top, **before** any other code:

```kotlin
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
```

- [ ] **Step 4: Verify Gradle still loads the workspace**

Run: `./gradlew tasks --no-daemon`
Expected: tasks listed, no errors. The included build at `gradle/plugins` compiles its (currently empty) Kotlin sources successfully.

- [ ] **Step 5: Commit**

```bash
git add gradle/plugins/settings.gradle.kts gradle/plugins/build.gradle.kts settings.gradle.kts
git commit -m "build: scaffold gradle/plugins included build"
```

---

## Task 2: `MavenModel` data class + `PomReader` (in-build parents only)

**Files:**
- Create: `gradle/plugins/src/main/kotlin/workspace/maven/MavenModel.kt`
- Create: `gradle/plugins/src/main/kotlin/workspace/maven/PomReader.kt`
- Create: `gradle/plugins/src/test/kotlin/workspace/maven/PomReaderTest.kt`
- Create: `gradle/plugins/src/test/resources/poms/simple.xml`

- [ ] **Step 1: Create the fixture POM**

Write to `gradle/plugins/src/test/resources/poms/simple.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>simple</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <spotless.licence-header>header.txt</spotless.licence-header>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>2.0.13</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <repositories>
        <repository>
            <id>example-repo</id>
            <url>https://repo.example.com</url>
        </repository>
    </repositories>
</project>
```

- [ ] **Step 2: Write the failing test**

Write to `gradle/plugins/src/test/kotlin/workspace/maven/PomReaderTest.kt`:

```kotlin
package workspace.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class PomReaderTest {
    private fun fixture(name: String) =
        Paths.get("src/test/resources/poms", name).toFile()

    @Test
    fun `parses GAV and packaging from a simple POM`() {
        val model = PomReader().read(fixture("simple.xml"))
        assertEquals("com.example", model.groupId)
        assertEquals("simple", model.artifactId)
        assertEquals("1.0.0", model.version)
        assertEquals("jar", model.packaging)
    }

    @Test
    fun `extracts properties from a simple POM`() {
        val model = PomReader().read(fixture("simple.xml"))
        assertEquals("21", model.properties["maven.compiler.release"])
        assertEquals("header.txt", model.properties["spotless.licence-header"])
    }

    @Test
    fun `extracts dependencies with scopes from a simple POM`() {
        val model = PomReader().read(fixture("simple.xml"))
        val slf4j = model.dependencies.single { it.artifactId == "slf4j-api" }
        assertEquals("org.slf4j", slf4j.groupId)
        assertEquals("2.0.13", slf4j.version)
        assertEquals("compile", slf4j.scope)

        val junit = model.dependencies.single { it.artifactId == "junit-jupiter" }
        assertEquals("test", junit.scope)
    }

    @Test
    fun `extracts repositories declared in the POM`() {
        val model = PomReader().read(fixture("simple.xml"))
        assertEquals(listOf("https://repo.example.com"), model.repositories.map { it.url })
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :gradle-plugins:test --no-daemon`
Expected: FAIL with "Unresolved reference: PomReader".

- [ ] **Step 4: Define the `MavenModel` data class**

Write to `gradle/plugins/src/main/kotlin/workspace/maven/MavenModel.kt`:

```kotlin
package workspace.maven

import org.codehaus.plexus.util.xml.Xpp3Dom

data class MavenModel(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val packaging: String,
    val properties: Map<String, String>,
    val dependencies: List<MavenDependency>,
    val dependencyManagement: List<MavenDependency>,
    val repositories: List<MavenRepository>,
    /** Raw `<configuration>` XML by `groupId:artifactId` of the plugin. */
    val pluginConfigurations: Map<String, Xpp3Dom>
) {
    val compilerRelease: Int
        get() = properties["maven.compiler.release"]?.toInt()
            ?: error("$groupId:$artifactId: maven.compiler.release not set")

    fun property(key: String): String? = properties[key]

    fun pluginConfiguration(coords: String): Xpp3Dom? = pluginConfigurations[coords]
}

data class MavenDependency(
    val groupId: String,
    val artifactId: String,
    val version: String?,
    val scope: String,
    val optional: Boolean,
    val type: String,   // "jar" or "pom" (for BOM imports)
)

data class MavenRepository(
    val id: String,
    val url: String,
)
```

- [ ] **Step 5: Implement minimal `PomReader` (in-build parents only)**

Write to `gradle/plugins/src/main/kotlin/workspace/maven/PomReader.kt`:

```kotlin
package workspace.maven

import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import org.apache.maven.model.Repository as MavenRepoModel
import org.apache.maven.model.building.ModelSource2
import org.codehaus.plexus.util.xml.Xpp3Dom
import java.io.File
import java.net.URI

class PomReader(
    private val modelResolver: ModelResolver = OfflineModelResolver()
) {
    fun read(pomFile: File): MavenModel {
        val builder = DefaultModelBuilderFactory().newInstance()
        val request = DefaultModelBuildingRequest().apply {
            pomFile.let { setPomFile(it) }
            validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
            isProcessPlugins = true
            modelResolver = this@PomReader.modelResolver
            // Default profile only — no -P activations honored.
        }
        val result = builder.build(request)
        val model = result.effectiveModel

        val pluginConfigs = mutableMapOf<String, Xpp3Dom>()
        model.build?.plugins?.forEach { plugin ->
            val cfg = plugin.configuration as? Xpp3Dom ?: return@forEach
            pluginConfigs["${plugin.groupId}:${plugin.artifactId}"] = cfg
        }

        return MavenModel(
            groupId = model.groupId,
            artifactId = model.artifactId,
            version = model.version,
            packaging = model.packaging ?: "jar",
            properties = model.properties.entries.associate { it.key.toString() to it.value.toString() },
            dependencies = model.dependencies.map { dep ->
                MavenDependency(
                    groupId = dep.groupId,
                    artifactId = dep.artifactId,
                    version = dep.version,
                    scope = dep.scope ?: "compile",
                    optional = dep.optional?.toBoolean() ?: false,
                    type = dep.type ?: "jar",
                )
            },
            dependencyManagement = model.dependencyManagement?.dependencies?.map { dep ->
                MavenDependency(
                    groupId = dep.groupId,
                    artifactId = dep.artifactId,
                    version = dep.version,
                    scope = dep.scope ?: "compile",
                    optional = false,
                    type = dep.type ?: "jar",
                )
            } ?: emptyList(),
            repositories = model.repositories.map { MavenRepository(it.id, it.url) },
            pluginConfigurations = pluginConfigs,
        )
    }
}

/** Placeholder — full resolver added in Task 3. */
class OfflineModelResolver : ModelResolver {
    override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource2 =
        throw UnresolvableModelException("External parents not supported by OfflineModelResolver", groupId, artifactId, version)
    override fun resolveModel(parent: org.apache.maven.model.Parent): ModelSource2 =
        resolveModel(parent.groupId, parent.artifactId, parent.version)
    override fun resolveModel(dependency: org.apache.maven.model.Dependency): ModelSource2 =
        resolveModel(dependency.groupId, dependency.artifactId, dependency.version)
    override fun addRepository(repository: MavenRepoModel) {}
    override fun addRepository(repository: MavenRepoModel, replace: Boolean) {}
    override fun newCopy(): ModelResolver = this
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :gradle-plugins:test --no-daemon`
Expected: PASS for all four tests.

- [ ] **Step 7: Commit**

```bash
git add gradle/plugins/src/main/kotlin/workspace/maven/ gradle/plugins/src/test/
git commit -m "feat(gradle-plugins): PomReader for simple POMs"
```

---

## Task 3: Network-fetching `ModelResolver` for external parents

**Files:**
- Modify: `gradle/plugins/src/main/kotlin/workspace/maven/PomReader.kt` (replace `OfflineModelResolver`)
- Modify: `gradle/plugins/src/test/kotlin/workspace/maven/PomReaderTest.kt` (add test)

- [ ] **Step 1: Write a failing test that requires external resolution**

Append to `gradle/plugins/src/test/kotlin/workspace/maven/PomReaderTest.kt`:

```kotlin
    @Test
    fun `resolves external parent POM transparently`() {
        // Use the actual flow-components root POM, which has vaadin-parent as external parent.
        val pomFile = Paths.get("../../flow-components/pom.xml").toFile()
        if (!pomFile.exists()) return  // skip if submodule not checked out

        val model = PomReader().read(pomFile)
        // vaadin-parent contributes the prereleases repo via inheritance.
        val urls = model.repositories.map { it.url }
        assertTrue(urls.any { it.contains("vaadin-prereleases") },
            "Expected vaadin-prereleases in resolved repositories: $urls")
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :gradle-plugins:test --no-daemon`
Expected: FAIL — `UnresolvableModelException` when `OfflineModelResolver` is asked for `com.vaadin:vaadin-parent`.

- [ ] **Step 3: Implement the network-fetching resolver**

Replace `OfflineModelResolver` in `gradle/plugins/src/main/kotlin/workspace/maven/PomReader.kt` with:

```kotlin
package workspace.maven

import org.apache.maven.model.Dependency
import org.apache.maven.model.Parent
import org.apache.maven.model.Repository as MavenRepoModel
import org.apache.maven.model.building.*
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import java.io.File

class NetworkModelResolver : ModelResolver {
    companion object {
        private val DEFAULT_REPOS = listOf(
            RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2").build(),
            RemoteRepository.Builder("vaadin-prereleases", "default", "https://maven.vaadin.com/vaadin-prereleases").build(),
        )

        private val system: RepositorySystem by lazy {
            val locator = MavenRepositorySystemUtils.newServiceLocator()
            locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
            locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
            locator.getService(RepositorySystem::class.java)
        }

        private val session by lazy {
            val s = MavenRepositorySystemUtils.newSession()
            val localM2 = File(System.getProperty("user.home"), ".m2/repository")
            s.localRepositoryManager = system.newLocalRepositoryManager(s, LocalRepository(localM2))
            s
        }
    }

    private val additionalRepos = mutableListOf<RemoteRepository>()

    override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource2 {
        val artifact = DefaultArtifact("$groupId:$artifactId:pom:$version")
        val req = ArtifactRequest(artifact, additionalRepos + DEFAULT_REPOS, null)
        return try {
            val result = system.resolveArtifact(session, req)
            FileModelSource(result.artifact.file)
        } catch (e: Exception) {
            throw UnresolvableModelException(e.message, groupId, artifactId, version, e)
        }
    }

    override fun resolveModel(parent: Parent): ModelSource2 =
        resolveModel(parent.groupId, parent.artifactId, parent.version)

    override fun resolveModel(dependency: Dependency): ModelSource2 =
        resolveModel(dependency.groupId, dependency.artifactId, dependency.version)

    override fun addRepository(repository: MavenRepoModel) = addRepository(repository, false)

    override fun addRepository(repository: MavenRepoModel, replace: Boolean) {
        if (additionalRepos.any { it.id == repository.id } && !replace) return
        additionalRepos.add(
            RemoteRepository.Builder(repository.id, "default", repository.url).build()
        )
    }

    override fun newCopy(): ModelResolver = NetworkModelResolver().also {
        it.additionalRepos.addAll(this.additionalRepos)
    }
}
```

Update `PomReader`'s default to use the new resolver:

```kotlin
class PomReader(
    private val modelResolver: ModelResolver = NetworkModelResolver()
) {
    // unchanged
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :gradle-plugins:test --no-daemon`
Expected: PASS (requires network on first run; subsequent runs use `~/.m2` cache).

- [ ] **Step 5: Commit**

```bash
git add gradle/plugins/src/main/kotlin/workspace/maven/PomReader.kt gradle/plugins/src/test/kotlin/workspace/maven/PomReaderTest.kt
git commit -m "feat(gradle-plugins): network-fetching ModelResolver for external parents"
```

---

## Task 4: `GavRegistry` BuildService

**Files:**
- Create: `gradle/plugins/src/main/kotlin/workspace/maven/GavRegistry.kt`
- Create: `gradle/plugins/src/test/kotlin/workspace/maven/GavRegistryTest.kt`

- [ ] **Step 1: Write the failing test**

Write to `gradle/plugins/src/test/kotlin/workspace/maven/GavRegistryTest.kt`:

```kotlin
package workspace.maven

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GavRegistryTest {
    @Test
    fun `register then lookup returns the registered path`() {
        val registry = InMemoryGavRegistry()
        registry.register("com.vaadin", "vaadin-button-flow", ":flow-components:vaadin-button-flow-parent:vaadin-button-flow")
        assertEquals(
            ":flow-components:vaadin-button-flow-parent:vaadin-button-flow",
            registry.lookup("com.vaadin", "vaadin-button-flow")
        )
    }

    @Test
    fun `lookup returns null for unknown GAV`() {
        val registry = InMemoryGavRegistry()
        assertNull(registry.lookup("com.example", "missing"))
    }

    @Test
    fun `lookup throws after finalization for unknown GAV is permitted to caller`() {
        val registry = InMemoryGavRegistry()
        registry.finalize()
        // The registry just returns null; finalization is informational for callers.
        assertNull(registry.lookup("com.example", "missing"))
        assertTrue(registry.isFinalized)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :gradle-plugins:test --tests "workspace.maven.GavRegistryTest" --no-daemon`
Expected: FAIL with "Unresolved reference: InMemoryGavRegistry".

- [ ] **Step 3: Implement the registry**

Write to `gradle/plugins/src/main/kotlin/workspace/maven/GavRegistry.kt`:

```kotlin
package workspace.maven

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentHashMap

interface GavRegistry {
    fun register(groupId: String, artifactId: String, projectPath: String)
    fun lookup(groupId: String, artifactId: String): String?
    fun finalize()
    val isFinalized: Boolean
}

/** Plain-object implementation used in unit tests and as the runtime store inside [GavRegistryBuildService]. */
class InMemoryGavRegistry : GavRegistry {
    private val entries = ConcurrentHashMap<String, String>()
    @Volatile private var finalized = false

    override fun register(groupId: String, artifactId: String, projectPath: String) {
        entries["$groupId:$artifactId"] = projectPath
    }

    override fun lookup(groupId: String, artifactId: String): String? =
        entries["$groupId:$artifactId"]

    override fun finalize() {
        finalized = true
    }

    override val isFinalized: Boolean
        get() = finalized
}

abstract class GavRegistryBuildService :
    BuildService<BuildServiceParameters.None>,
    GavRegistry by InMemoryGavRegistry()
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :gradle-plugins:test --tests "workspace.maven.GavRegistryTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add gradle/plugins/src/main/kotlin/workspace/maven/GavRegistry.kt gradle/plugins/src/test/kotlin/workspace/maven/GavRegistryTest.kt
git commit -m "feat(gradle-plugins): GavRegistry build service"
```

---

## Task 5: `DependencyMapper`

**Files:**
- Create: `gradle/plugins/src/main/kotlin/workspace/maven/DependencyMapper.kt`
- Create: `gradle/plugins/src/test/kotlin/workspace/maven/DependencyMapperTest.kt`

- [ ] **Step 1: Write the failing tests**

Write to `gradle/plugins/src/test/kotlin/workspace/maven/DependencyMapperTest.kt`:

```kotlin
package workspace.maven

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DependencyMapperTest {
    private val registry = InMemoryGavRegistry().apply {
        register("com.vaadin", "vaadin-button-flow", ":flow-components:vaadin-button-flow-parent:vaadin-button-flow")
    }
    private val mapper = DependencyMapper(registry)

    @Test
    fun `compile scope maps to implementation`() {
        val spec = mapper.map(MavenDependency("org.slf4j", "slf4j-api", "2.0.13", "compile", false, "jar"))
        assertEquals("implementation", spec.configuration)
        assertTrue(spec is GradleDependencySpec.External)
        assertEquals("org.slf4j:slf4j-api:2.0.13", (spec as GradleDependencySpec.External).coordinate)
    }

    @Test
    fun `test scope maps to testImplementation`() {
        val spec = mapper.map(MavenDependency("org.junit.jupiter", "junit-jupiter", "5.10.2", "test", false, "jar"))
        assertEquals("testImplementation", spec.configuration)
    }

    @Test
    fun `runtime scope maps to runtimeOnly`() {
        val spec = mapper.map(MavenDependency("ch.qos.logback", "logback-classic", "1.5", "runtime", false, "jar"))
        assertEquals("runtimeOnly", spec.configuration)
    }

    @Test
    fun `provided scope maps to compileOnly plus testImplementation`() {
        val specs = mapper.mapProvided(MavenDependency("jakarta.servlet", "jakarta.servlet-api", "6.0.0", "provided", false, "jar"))
        assertEquals(setOf("compileOnly", "testImplementation"), specs.map { it.configuration }.toSet())
    }

    @Test
    fun `registered GAV resolves to ProjectRef`() {
        val spec = mapper.map(MavenDependency("com.vaadin", "vaadin-button-flow", null, "compile", false, "jar"))
        assertTrue(spec is GradleDependencySpec.ProjectRef)
        assertEquals(":flow-components:vaadin-button-flow-parent:vaadin-button-flow",
            (spec as GradleDependencySpec.ProjectRef).projectPath)
    }

    @Test
    fun `unregistered GAV without version maps to external without version`() {
        val spec = mapper.map(MavenDependency("com.vaadin", "vaadin-icons-flow", null, "compile", false, "jar"))
        assertTrue(spec is GradleDependencySpec.External)
        assertEquals("com.vaadin:vaadin-icons-flow", (spec as GradleDependencySpec.External).coordinate)
    }

    @Test
    fun `BOM import becomes platform dependency`() {
        val spec = mapper.mapBomImport(MavenDependency("com.vaadin", "vaadin-bom", "25.3-SNAPSHOT", "import", false, "pom"))
        assertEquals("implementation", spec.configuration)
        assertTrue(spec is GradleDependencySpec.Platform)
        assertEquals("com.vaadin:vaadin-bom:25.3-SNAPSHOT", (spec as GradleDependencySpec.Platform).coordinate)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :gradle-plugins:test --tests "workspace.maven.DependencyMapperTest" --no-daemon`
Expected: FAIL with "Unresolved reference: DependencyMapper".

- [ ] **Step 3: Implement `DependencyMapper`**

Write to `gradle/plugins/src/main/kotlin/workspace/maven/DependencyMapper.kt`:

```kotlin
package workspace.maven

sealed interface GradleDependencySpec {
    val configuration: String

    data class ProjectRef(override val configuration: String, val projectPath: String) : GradleDependencySpec
    data class External(override val configuration: String, val coordinate: String) : GradleDependencySpec
    data class Platform(override val configuration: String, val coordinate: String) : GradleDependencySpec
}

class DependencyMapper(private val registry: GavRegistry) {

    fun map(dep: MavenDependency): GradleDependencySpec {
        val configuration = scopeToConfiguration(dep.scope)
        val registered = registry.lookup(dep.groupId, dep.artifactId)
        if (registered != null) {
            return GradleDependencySpec.ProjectRef(configuration, registered)
        }
        val coord = if (dep.version != null) "${dep.groupId}:${dep.artifactId}:${dep.version}"
                    else "${dep.groupId}:${dep.artifactId}"
        return GradleDependencySpec.External(configuration, coord)
    }

    /** Maven `provided` scope maps to both `compileOnly` and `testImplementation`. */
    fun mapProvided(dep: MavenDependency): List<GradleDependencySpec> {
        require(dep.scope == "provided") { "mapProvided requires provided scope, got ${dep.scope}" }
        val coord = if (dep.version != null) "${dep.groupId}:${dep.artifactId}:${dep.version}"
                    else "${dep.groupId}:${dep.artifactId}"
        return listOf(
            GradleDependencySpec.External("compileOnly", coord),
            GradleDependencySpec.External("testImplementation", coord),
        )
    }

    /** Map a `<scope>import</scope>` + `<type>pom</type>` BOM entry to a Gradle platform dependency. */
    fun mapBomImport(dep: MavenDependency): GradleDependencySpec {
        require(dep.scope == "import" && dep.type == "pom") {
            "mapBomImport requires <scope>import</scope> + <type>pom</type>, got ${dep.scope}/${dep.type}"
        }
        val version = dep.version ?: error("BOM import must have explicit version")
        return GradleDependencySpec.Platform("implementation", "${dep.groupId}:${dep.artifactId}:$version")
    }

    private fun scopeToConfiguration(scope: String): String = when (scope) {
        "compile" -> "implementation"
        "runtime" -> "runtimeOnly"
        "test" -> "testImplementation"
        "provided" -> error("Call mapProvided() for provided scope")
        "system" -> error("system scope is not supported")
        "import" -> error("Call mapBomImport() for import scope")
        else -> error("Unknown Maven scope: $scope")
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :gradle-plugins:test --tests "workspace.maven.DependencyMapperTest" --no-daemon`
Expected: PASS for all seven tests.

- [ ] **Step 5: Commit**

```bash
git add gradle/plugins/src/main/kotlin/workspace/maven/DependencyMapper.kt gradle/plugins/src/test/kotlin/workspace/maven/DependencyMapperTest.kt
git commit -m "feat(gradle-plugins): DependencyMapper for POM-to-Gradle dep translation"
```

---

## Task 6: `MavenModuleProjectPlugin` (minimal — repos + extension only)

**Files:**
- Create: `gradle/plugins/src/main/kotlin/workspace/maven/MavenModuleProjectPlugin.kt`
- Modify: `gradle/plugins/build.gradle.kts` (register the plugin)
- Create: `gradle/plugins/src/functionalTest/kotlin/workspace/maven/MavenModuleProjectPluginTest.kt`
- Modify: `gradle/plugins/build.gradle.kts` (add functionalTest source set)

- [ ] **Step 1: Add `functionalTest` source set + TestKit dep**

Modify `gradle/plugins/build.gradle.kts` to add the source set and TestKit. Replace the file's content with:

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

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

val functionalTest by sourceSets.creating
configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
    "functionalTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(functionalTestTask) }
gradlePlugin.testSourceSets(functionalTest)

gradlePlugin {
    plugins {
        register("workspaceMavenModule") {
            id = "workspace.maven-module"
            implementationClass = "workspace.maven.MavenModuleProjectPlugin"
        }
    }
}
```

- [ ] **Step 2: Write a failing functional test**

Write to `gradle/plugins/src/functionalTest/kotlin/workspace/maven/MavenModuleProjectPluginTest.kt`:

```kotlin
package workspace.maven

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MavenModuleProjectPluginTest {
    @TempDir lateinit var projectDir: File

    @Test
    fun `plugin applies and exposes mavenModel extension`() {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        File(projectDir, "pom.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>fixture</artifactId>
                <version>1.0.0</version>
                <packaging>jar</packaging>
                <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                </properties>
            </project>
        """.trimIndent())
        File(projectDir, "build.gradle.kts").writeText("""
            plugins { id("workspace.maven-module") }
            tasks.register("printGav") {
                doLast {
                    val model = extensions.getByName("mavenModel") as workspace.maven.MavenModel
                    println("GAV=${'$'}{model.groupId}:${'$'}{model.artifactId}:${'$'}{model.version}")
                }
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("printGav", "--no-daemon")
            .withPluginClasspath()
            .build()

        assertTrue(result.output.contains("GAV=com.example:fixture:1.0.0"), result.output)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :gradle-plugins:functionalTest --no-daemon`
Expected: FAIL — `MavenModuleProjectPlugin` doesn't exist yet.

- [ ] **Step 4: Implement the plugin (minimal)**

Write to `gradle/plugins/src/main/kotlin/workspace/maven/MavenModuleProjectPlugin.kt`:

```kotlin
package workspace.maven

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.net.URI

class MavenModuleProjectPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val pomFile = project.projectDir.resolve("pom.xml")
        check(pomFile.exists()) { "workspace.maven-module requires ${pomFile} to exist" }

        val model = PomReader().read(pomFile)

        val registry = project.gradle.sharedServices.registerIfAbsent(
            "gavRegistry",
            GavRegistryBuildService::class.java
        ) {}
        registry.get().register(model.groupId, model.artifactId, project.path)

        // 1. Repositories — Maven defaults + anything declared in the resolved POM.
        project.repositories.mavenLocal()
        project.repositories.mavenCentral()
        model.repositories.forEach { repo ->
            project.repositories.maven { it.url = URI.create(repo.url) }
        }

        // 2. Expose the parsed model for build files and Layer 2 conventions to read.
        project.extensions.add("mavenModel", model)

        // Output redirection, maven-publish, and deferred dep wiring are added in later tasks.
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :gradle-plugins:functionalTest --no-daemon`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add gradle/plugins/src/main/kotlin/workspace/maven/MavenModuleProjectPlugin.kt gradle/plugins/src/functionalTest/ gradle/plugins/build.gradle.kts
git commit -m "feat(gradle-plugins): workspace.maven-module plugin (minimal)"
```

---

## Task 7: Apply `workspace.maven-module` to `:flow-components`

**Files:**
- Modify: `gradle/flow-components.gradle.kts:1-3` (add plugins block)

- [ ] **Step 1: Add the plugin to the existing build file**

Modify `gradle/flow-components.gradle.kts` — prepend a `plugins {}` block. The full new top of the file:

```kotlin
import org.gradle.internal.os.OperatingSystem

plugins {
    id("workspace.maven-module")
}

val mvnCommand = if (OperatingSystem.current().isWindows) "mvn.cmd" else "mvn"

// ... existing task definitions unchanged ...
```

- [ ] **Step 2: Verify the model is parsed**

Run: `./gradlew :flow-components:help --no-daemon`
Expected: command succeeds; no errors during plugin apply (parsing `flow-components/pom.xml`, including network resolution of `vaadin-parent`).

- [ ] **Step 3: Smoke-check the model via a temporary task**

Append a one-off diagnostic task at the bottom of `gradle/flow-components.gradle.kts`:

```kotlin
tasks.register("printFlowVersion") {
    doLast {
        val model = extensions.getByName("mavenModel") as workspace.maven.MavenModel
        println("flow.version=${model.property("flow.version")}")
    }
}
```

- [ ] **Step 4: Run the diagnostic**

Run: `./gradlew :flow-components:printFlowVersion --no-daemon`
Expected: prints `flow.version=25.3-SNAPSHOT` (current value at the time of writing — match it to `flow-components/pom.xml`).

- [ ] **Step 5: Remove the diagnostic task**

Remove the `printFlowVersion` block. It was for verification only.

- [ ] **Step 6: Commit**

```bash
git add gradle/flow-components.gradle.kts
git commit -m "build(flow-components): apply workspace.maven-module to :flow-components"
```

---

## Task 8: `vaadin.workspace.java-library` convention plugin (skeleton, no output redirection)

**Files:**
- Modify: `gradle/plugins/build.gradle.kts` (add Spotless dep + plugin registration)
- Create: `gradle/plugins/src/main/kotlin/vaadin/workspace/JavaLibraryConventionPlugin.kt`

- [ ] **Step 1: Add Spotless to the included build's dependencies**

In `gradle/plugins/build.gradle.kts`, add to `dependencies { ... }`:

```kotlin
    implementation("com.diffplug.spotless:spotless-plugin-gradle:6.25.0")
```

And add to `gradlePlugin.plugins { ... }`:

```kotlin
        register("vaadinWorkspaceJavaLibrary") {
            id = "vaadin.workspace.java-library"
            implementationClass = "vaadin.workspace.JavaLibraryConventionPlugin"
        }
```

- [ ] **Step 2: Implement the convention plugin**

Write to `gradle/plugins/src/main/kotlin/vaadin/workspace/JavaLibraryConventionPlugin.kt`:

```kotlin
package vaadin.workspace

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import workspace.maven.MavenModel
import java.io.File

class JavaLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("workspace.maven-module")
        project.plugins.apply("java-library")
        project.plugins.apply("com.diffplug.spotless")
        project.plugins.apply("checkstyle")

        val model = project.extensions.getByName("mavenModel") as MavenModel

        project.extensions.configure(JavaPluginExtension::class.java) { ext ->
            ext.toolchain.languageVersion.set(JavaLanguageVersion.of(model.compilerRelease))
        }

        project.tasks.withType(Test::class.java).configureEach { it.useJUnitPlatform() }

        project.extensions.configure(SpotlessExtension::class.java) { spotless ->
            val licenseHeader = model.property("spotless.licence-header")?.let { File(it) }
            spotless.java { java ->
                java.target("src/main/java/**/*.java", "src/test/java/**/*.java")
                java.googleJavaFormat()
                if (licenseHeader?.exists() == true) {
                    java.licenseHeaderFile(licenseHeader)
                }
            }
        }

        project.extensions.configure(CheckstyleExtension::class.java) { ext ->
            val checkstyleConfig = project.rootDir
                .resolve("flow-components/checkstyle/checkstyle.xml")
            if (checkstyleConfig.exists()) {
                ext.configFile = checkstyleConfig
            }
            model.property("checkstyle.version")?.let { ext.toolVersion = it }
        }
    }
}
```

- [ ] **Step 3: Run the included build's `assemble` to verify it compiles**

Run: `./gradlew :gradle-plugins:assemble --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add gradle/plugins/build.gradle.kts gradle/plugins/src/main/kotlin/vaadin/workspace/JavaLibraryConventionPlugin.kt
git commit -m "feat(gradle-plugins): vaadin.workspace.java-library convention plugin"
```

---

## Task 9: First pilot subproject + workspace `settings.gradle.kts` glob loop

**Files:**
- Modify: `settings.gradle.kts` (add the glob loop)
- Create: `gradle/flow-components/vaadin-flow-components-shared-parent/vaadin-flow-components-base.gradle.kts`

- [ ] **Step 1: Create the first per-subproject build file**

Write to `gradle/flow-components/vaadin-flow-components-shared-parent/vaadin-flow-components-base.gradle.kts`:

```kotlin
plugins { id("vaadin.workspace.java-library") }
```

- [ ] **Step 2: Add the glob loop to the workspace settings**

Modify `settings.gradle.kts` — append after the existing `project(":flow-components")` block:

```kotlin
import java.io.File

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

Move the `import java.io.File` to the very top of the file if Kotlin script requires it (it does — imports come first).

- [ ] **Step 3: Verify the subproject is registered**

Run: `./gradlew :flow-components:vaadin-flow-components-shared-parent:vaadin-flow-components-base:tasks --no-daemon`
Expected: list of tasks for the subproject (`compileJava`, `test`, `jar`, `spotlessCheck`, `checkstyleMain`, etc.).

- [ ] **Step 4: Build the subproject natively**

Bootstrap Maven Local first (if not already):
```bash
./gradlew :flow-components:build --no-daemon
```

Then run:
```bash
./gradlew :flow-components:vaadin-flow-components-shared-parent:vaadin-flow-components-base:build --no-daemon
```
Expected: BUILD SUCCESSFUL. Compiled classes in `flow-components/vaadin-flow-components-shared-parent/vaadin-flow-components-base/build/classes/...` (Gradle default; output redirection comes in Task 10). Tests run via JUnit Platform.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts gradle/flow-components/vaadin-flow-components-shared-parent/vaadin-flow-components-base.gradle.kts
git commit -m "build: first pilot subproject — vaadin-flow-components-base via Gradle"
```

---

## Task 10: Output redirection to `target/` in Layer 1

**Files:**
- Modify: `gradle/plugins/src/main/kotlin/workspace/maven/MavenModuleProjectPlugin.kt`

- [ ] **Step 1: Add output redirection in `plugins.withId("java")` block**

Modify `MavenModuleProjectPlugin.apply()` to add, after `project.extensions.add("mavenModel", model)`:

```kotlin
        project.plugins.withId("java") {
            project.extensions.configure(org.gradle.api.tasks.SourceSetContainer::class.java) { sourceSets ->
                sourceSets.named("main") { ss ->
                    ss.java.destinationDirectory.set(project.file("target/classes"))
                    ss.output.setResourcesDir(project.file("target/classes"))
                }
                sourceSets.named("test") { ss ->
                    ss.java.destinationDirectory.set(project.file("target/test-classes"))
                    ss.output.setResourcesDir(project.file("target/test-classes"))
                }
            }
            project.tasks.named("jar", org.gradle.api.tasks.bundling.Jar::class.java) { jar ->
                jar.destinationDirectory.set(project.file("target"))
                jar.archiveBaseName.set(model.artifactId)
                jar.archiveVersion.set(model.version)
            }
            project.tasks.named("clean", org.gradle.api.tasks.Delete::class.java) { clean ->
                clean.delete(project.file("target"))
            }
        }
        project.plugins.withId("war") {
            project.tasks.named("war", org.gradle.api.tasks.bundling.War::class.java) { war ->
                war.destinationDirectory.set(project.file("target"))
                war.archiveBaseName.set(model.artifactId)
                war.archiveVersion.set(model.version)
            }
        }
```

- [ ] **Step 2: Re-run the pilot subproject build and verify outputs**

```bash
./gradlew :flow-components:vaadin-flow-components-shared-parent:vaadin-flow-components-base:clean :flow-components:vaadin-flow-components-shared-parent:vaadin-flow-components-base:build --no-daemon
```

Expected: BUILD SUCCESSFUL. Compiled classes in `flow-components/vaadin-flow-components-shared-parent/vaadin-flow-components-base/target/classes/...`. Jar at `flow-components/vaadin-flow-components-shared-parent/vaadin-flow-components-base/target/vaadin-flow-components-base-25.3-SNAPSHOT.jar` (or whatever version is in the POM).

- [ ] **Step 3: Update the existing functional test to assert outputs in `target/`**

Modify `gradle/plugins/src/functionalTest/kotlin/workspace/maven/MavenModuleProjectPluginTest.kt` — add a second test:

```kotlin
    @Test
    fun `outputs land in Maven target layout when java plugin is applied`() {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        File(projectDir, "pom.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>fixture</artifactId>
                <version>1.0.0</version>
                <packaging>jar</packaging>
                <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                </properties>
            </project>
        """.trimIndent())
        File(projectDir, "src/main/java/com/example").mkdirs()
        File(projectDir, "src/main/java/com/example/Hello.java")
            .writeText("package com.example; public class Hello {}")
        File(projectDir, "build.gradle.kts").writeText("""
            plugins {
                id("workspace.maven-module")
                `java-library`
            }
            java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
        """.trimIndent())

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("jar", "--no-daemon")
            .withPluginClasspath()
            .build()

        val targetDir = File(projectDir, "target")
        assertTrue(File(targetDir, "classes/com/example/Hello.class").exists(),
            "Compiled class should be under target/classes/")
        assertTrue(File(targetDir, "fixture-1.0.0.jar").exists(),
            "Jar should be at target/fixture-1.0.0.jar")
    }
```

- [ ] **Step 4: Run the functional test**

Run: `./gradlew :gradle-plugins:functionalTest --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add gradle/plugins/src/main/kotlin/workspace/maven/MavenModuleProjectPlugin.kt gradle/plugins/src/functionalTest/
git commit -m "feat(gradle-plugins): redirect outputs to Maven target/ layout"
```

---

## Task 11: `maven-publish` in Layer 1

**Files:**
- Modify: `gradle/plugins/src/main/kotlin/workspace/maven/MavenModuleProjectPlugin.kt`

- [ ] **Step 1: Add `maven-publish` configuration inside `plugins.withId("java")`**

Inside the `plugins.withId("java") { ... }` block in `MavenModuleProjectPlugin.apply()`, after the `tasks.named("clean", ...)` block, add:

```kotlin
            project.plugins.apply("maven-publish")
            project.extensions.configure(org.gradle.api.publish.PublishingExtension::class.java) { publishing ->
                publishing.publications.create("maven", org.gradle.api.publish.maven.MavenPublication::class.java) { pub ->
                    pub.from(project.components.getByName("java"))
                    pub.groupId = model.groupId
                    pub.artifactId = model.artifactId
                    pub.version = model.version
                }
            }
```

And inside `plugins.withId("war") { ... }`, after the `war` task config, add:

```kotlin
            project.extensions.configure(org.gradle.api.publish.PublishingExtension::class.java) { publishing ->
                publishing.publications.named("maven", org.gradle.api.publish.maven.MavenPublication::class.java) { pub ->
                    pub.setComponents(project.components.getByName("web"))
                }
            }
```

- [ ] **Step 2: Run publishToMavenLocal on the pilot module**

```bash
./gradlew :flow-components:vaadin-flow-components-shared-parent:vaadin-flow-components-base:publishToMavenLocal --no-daemon
```

Expected: BUILD SUCCESSFUL. Jar appears under `~/.m2/repository/com/vaadin/vaadin-flow-components-base/<version>/`.

- [ ] **Step 3: Verify the install**

```bash
ls ~/.m2/repository/com/vaadin/vaadin-flow-components-base/
```
Expected: a version directory containing the `.jar` and `.pom` file.

- [ ] **Step 4: Commit**

```bash
git add gradle/plugins/src/main/kotlin/workspace/maven/MavenModuleProjectPlugin.kt
git commit -m "feat(gradle-plugins): apply maven-publish for publishToMavenLocal"
```

---

## Task 12: Cross-module dep wiring + add `vaadin-flow-components-test-util`

**Files:**
- Modify: `gradle/plugins/src/main/kotlin/workspace/maven/MavenModuleProjectPlugin.kt` (deferred dep wiring)
- Create: `gradle/flow-components/vaadin-flow-components-shared-parent/vaadin-flow-components-test-util.gradle.kts`

- [ ] **Step 1: Add deferred dep wiring at the end of `apply()`**

In `MavenModuleProjectPlugin.apply()`, after the `plugins.withId("war")` block, add:

```kotlin
        // Defer cross-project dep wiring until all subprojects have registered their GAVs.
        val extras = project.gradle.extensions
        if (extras.findByName("mavenModuleDepsHookInstalled") == null) {
            extras.add("mavenModuleDepsHookInstalled", true)
            project.gradle.projectsEvaluated { gradle ->
                registry.get().finalize()
                val mapper = DependencyMapper(registry.get())
                gradle.rootProject.allprojects.forEach { p ->
                    val pModel = p.extensions.findByName("mavenModel") as? MavenModel ?: return@forEach
                    pModel.dependencyManagement
                        .filter { it.scope == "import" && it.type == "pom" }
                        .forEach { dep ->
                            val spec = mapper.mapBomImport(dep) as GradleDependencySpec.Platform
                            p.dependencies.add(spec.configuration, p.dependencies.platform(spec.coordinate))
                        }
                    pModel.dependencies.forEach { dep ->
                        if (dep.scope == "provided") {
                            mapper.mapProvided(dep).forEach { spec ->
                                p.dependencies.add(spec.configuration, (spec as GradleDependencySpec.External).coordinate)
                            }
                            return@forEach
                        }
                        if (dep.scope == "import" && dep.type == "pom") return@forEach
                        val spec = mapper.map(dep)
                        when (spec) {
                            is GradleDependencySpec.ProjectRef ->
                                p.dependencies.add(spec.configuration, p.project(spec.projectPath))
                            is GradleDependencySpec.External ->
                                p.dependencies.add(spec.configuration, spec.coordinate)
                            is GradleDependencySpec.Platform ->
                                p.dependencies.add(spec.configuration, p.dependencies.platform(spec.coordinate))
                        }
                    }
                }
            }
        }
```

Imports needed at the top of the file (add if missing):

```kotlin
import workspace.maven.GradleDependencySpec
import workspace.maven.DependencyMapper
import workspace.maven.MavenModel
```

(They're already in the `workspace.maven` package, so unqualified references work too if the file is in that package.)

- [ ] **Step 2: Add the test-util subproject**

Write to `gradle/flow-components/vaadin-flow-components-shared-parent/vaadin-flow-components-test-util.gradle.kts`:

```kotlin
plugins { id("vaadin.workspace.java-library") }
```

- [ ] **Step 3: Verify test-util resolves vaadin-flow-components-base as a project dep**

Run: `./gradlew :flow-components:vaadin-flow-components-shared-parent:vaadin-flow-components-test-util:dependencies --configuration implementation --no-daemon`

Expected output contains something like:
```
implementation - Implementation only dependencies for source set 'main'.
+--- project :flow-components:vaadin-flow-components-shared-parent:vaadin-flow-components-base
...
```

(The exact dep on `-base` exists in `vaadin-flow-components-test-util/pom.xml`. Confirm before running.)

- [ ] **Step 4: Build test-util**

```bash
./gradlew :flow-components:vaadin-flow-components-shared-parent:vaadin-flow-components-test-util:build --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add gradle/plugins/src/main/kotlin/workspace/maven/MavenModuleProjectPlugin.kt gradle/flow-components/vaadin-flow-components-shared-parent/vaadin-flow-components-test-util.gradle.kts
git commit -m "feat(gradle-plugins): deferred cross-module dep wiring via projectsEvaluated"
```

---

## Task 13: Button JAR pilots — `vaadin-button-flow` + `vaadin-button-testbench`

**Files:**
- Create: `gradle/flow-components/vaadin-button-flow-parent/vaadin-button-flow.gradle.kts`
- Create: `gradle/flow-components/vaadin-button-flow-parent/vaadin-button-testbench.gradle.kts`

- [ ] **Step 1: Create the button JAR build files**

Write to `gradle/flow-components/vaadin-button-flow-parent/vaadin-button-flow.gradle.kts`:

```kotlin
plugins { id("vaadin.workspace.java-library") }
```

Write to `gradle/flow-components/vaadin-button-flow-parent/vaadin-button-testbench.gradle.kts`:

```kotlin
plugins { id("vaadin.workspace.java-library") }
```

- [ ] **Step 2: Verify subprojects are visible**

Run: `./gradlew projects --no-daemon`
Expected: the tree shows `:flow-components:vaadin-button-flow-parent:vaadin-button-flow` and `:flow-components:vaadin-button-flow-parent:vaadin-button-testbench`.

- [ ] **Step 3: Build the two button JAR modules**

Ensure Maven Local is populated (run `./gradlew :flow-components:build --no-daemon` first if needed). Then:

```bash
./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow:build \
          :flow-components:vaadin-button-flow-parent:vaadin-button-testbench:build \
          --no-daemon
```
Expected: BUILD SUCCESSFUL. Unit tests in `vaadin-button-flow` run and pass.

- [ ] **Step 4: Verify dep resolution**

```bash
./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow:dependencies --configuration implementation --no-daemon
```
Expected: in-pilot deps appear as `project :...`; out-of-pilot deps (e.g. `flow-server`, `flow-data`) appear as external coordinates.

- [ ] **Step 5: Commit**

```bash
git add gradle/flow-components/vaadin-button-flow-parent/
git commit -m "build: add button JAR pilots — vaadin-button-flow + vaadin-button-testbench"
```

---

## Task 14: `vaadin.workspace.integration-tests` convention plugin

**Files:**
- Modify: `gradle/plugins/build.gradle.kts` (add Vaadin Gradle plugin + Gretty deps + plugin registration)
- Create: `gradle/plugins/src/main/kotlin/vaadin/workspace/IntegrationTestsConventionPlugin.kt`

- [ ] **Step 1: Add the IT-related plugin deps**

Modify `gradle/plugins/build.gradle.kts` — append to `dependencies { ... }`:

```kotlin
    implementation("com.vaadin:flow-gradle-plugin:25.3-SNAPSHOT")
    implementation("org.gretty:gretty:4.1.6")
```

Append to `gradlePlugin.plugins { ... }`:

```kotlin
        register("vaadinWorkspaceIntegrationTests") {
            id = "vaadin.workspace.integration-tests"
            implementationClass = "vaadin.workspace.IntegrationTestsConventionPlugin"
        }
```

- [ ] **Step 2: Implement the plugin**

Write to `gradle/plugins/src/main/kotlin/vaadin/workspace/IntegrationTestsConventionPlugin.kt`:

```kotlin
package vaadin.workspace

import org.codehaus.plexus.util.xml.Xpp3Dom
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import workspace.maven.MavenModel

class IntegrationTestsConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("vaadin.workspace.java-library")
        project.plugins.apply("war")
        project.plugins.apply("com.vaadin")
        project.plugins.apply("org.gretty")

        val model = project.extensions.getByName("mavenModel") as MavenModel
        val flowPlugin = model.pluginConfiguration("com.vaadin:flow-maven-plugin")

        // Map flow-maven-plugin config → Vaadin Gradle plugin extension.
        // The Vaadin Gradle plugin exposes its config via the `vaadin` extension (com.vaadin.gradle.VaadinFlowPluginExtension).
        // Properties names match flow-maven-plugin closely.
        val vaadinExt = project.extensions.findByName("vaadin")
        if (vaadinExt != null && flowPlugin != null) {
            applyChild(flowPlugin, "frontendDirectory") { value ->
                vaadinExt.javaClass.getMethod("setFrontendDirectory", String::class.java).invoke(vaadinExt, value)
            }
            applyChild(flowPlugin, "productionMode") { value ->
                vaadinExt.javaClass.getMethod("setProductionMode", Boolean::class.javaPrimitiveType).invoke(vaadinExt, value.toBoolean())
            }
            // pnpmEnable is resolved from <vaadin.pnpm.enable> property.
            model.property("vaadin.pnpm.enable")?.let { value ->
                vaadinExt.javaClass.getMethod("setPnpmEnable", Boolean::class.javaPrimitiveType).invoke(vaadinExt, value.toBoolean())
            }
            applyChild(flowPlugin, "nodeAutoUpdate") { value ->
                vaadinExt.javaClass.getMethod("setNodeAutoUpdate", Boolean::class.javaPrimitiveType).invoke(vaadinExt, value.toBoolean())
            }
        }

        // Gretty configuration.
        val grettyExt = project.extensions.findByName("gretty")
        if (grettyExt != null) {
            val httpPort = model.property("jetty.http.port")?.toIntOrNull() ?: 8080
            val stopPort = model.property("jetty.stop.port")?.toIntOrNull() ?: 9999
            grettyExt.javaClass.getMethod("setHttpPort", Int::class.javaPrimitiveType).invoke(grettyExt, httpPort)
            grettyExt.javaClass.getMethod("setStopPort", Int::class.javaPrimitiveType).invoke(grettyExt, stopPort)
        }

        // integrationTest task — JUnit 4, runs against Gretty-managed Jetty.
        project.extensions.configure(SourceSetContainer::class.java) { sourceSets ->
            sourceSets.create("integrationTest") { ss ->
                ss.compileClasspath += sourceSets.named("main").get().output + sourceSets.named("test").get().output
                ss.runtimeClasspath += ss.output + sourceSets.named("test").get().runtimeClasspath
                ss.java.srcDir("src/test/java")
            }
        }
        val integrationTest = project.tasks.register("integrationTest", Test::class.java) { task ->
            val its = (project.extensions.getByName("sourceSets") as SourceSetContainer).getByName("integrationTest")
            task.description = "Runs TestBench integration tests against Gretty's Jetty."
            task.group = "verification"
            task.testClassesDirs = its.output.classesDirs
            task.classpath = its.runtimeClasspath
            task.useJUnit()
            task.dependsOn("vaadinBuildFrontend")
            task.dependsOn("appBeforeIntegrationTest")
            task.finalizedBy("appAfterIntegrationTest")
        }
        project.tasks.named("check") { it.dependsOn(integrationTest) }
    }

    private fun applyChild(parent: Xpp3Dom, key: String, action: (String) -> Unit) {
        parent.getChild(key)?.value?.takeIf { it.isNotBlank() }?.let(action)
    }
}
```

> **Note for the implementer:** the reflective calls above (`getMethod(...).invoke(...)`) avoid a compile-time dependency on Vaadin Gradle plugin's internal class names, which may rename across versions. If exact method signatures are stable in the pinned `25.3-SNAPSHOT`, swap reflection for direct casts to the plugin's extension type. Verify on first run; if reflection fails, look up the actual setter signatures via `extension.javaClass.methods.joinToString("\n") { it.toString() }` from a diagnostic Gradle task and adjust.

- [ ] **Step 3: Run the included build's `check`**

Run: `./gradlew :gradle-plugins:check --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add gradle/plugins/build.gradle.kts gradle/plugins/src/main/kotlin/vaadin/workspace/IntegrationTestsConventionPlugin.kt
git commit -m "feat(gradle-plugins): vaadin.workspace.integration-tests convention plugin"
```

---

## Task 15: Button IT pilot

**Files:**
- Create: `gradle/flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests.gradle.kts`

- [ ] **Step 1: Create the IT subproject build file**

Write to `gradle/flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests.gradle.kts`:

```kotlin
plugins { id("vaadin.workspace.integration-tests") }
```

- [ ] **Step 2: Verify the subproject's tasks**

Run: `./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow-integration-tests:tasks --no-daemon`
Expected: tasks include `war`, `vaadinBuildFrontend`, `appBeforeIntegrationTest`, `appAfterIntegrationTest`, `integrationTest`, `publishToMavenLocal`.

- [ ] **Step 3: Bootstrap Maven Local and npm workspace if not warm**

```bash
./gradlew :flow-components:build --no-daemon
```

- [ ] **Step 4: Run the button IT via Gradle**

```bash
./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow-integration-tests:integrationTest --info --no-daemon
```

Expected: frontend bundle built; Jetty starts on port 8080; TestBench tests run and pass; Jetty stops.

If `flow-maven-plugin` config mapping has gaps that cause the frontend build to fail, override the relevant Vaadin extension settings directly in the build file:

```kotlin
plugins { id("vaadin.workspace.integration-tests") }

vaadin {
    frontendDirectory = file("./frontend")
    productionMode = false
    pnpmEnable = false
}
```

- [ ] **Step 5: Commit**

```bash
git add gradle/flow-components/vaadin-button-flow-parent/vaadin-button-flow-integration-tests.gradle.kts
git commit -m "build: add button IT pilot — vaadin-button-flow-integration-tests via Gradle"
```

---

## Task 16: Wire `:flow-components:build` to pilot publish + `:npmInstall`

**Files:**
- Modify: `gradle/flow-components.gradle.kts` (existing `install` + `build` tasks)

- [ ] **Step 1: Wire `:npmInstall` into `:flow-components:install`**

In `gradle/flow-components.gradle.kts`, modify the existing `tasks.named("install") { dependsOn(syncFlowOverlays) }` to:

```kotlin
tasks.named("install") {
    dependsOn(syncFlowOverlays, ":npmInstall")
}
```

- [ ] **Step 2: Wire pilot `publishToMavenLocal` into the existing `:flow-components:build` task**

In the same file, modify the `tasks.register<Exec>("build") { ... }` block to add a `dependsOn(...)` listing pilot subprojects' publish tasks. Replace the block with:

```kotlin
tasks.register<Exec>("build") {
    description = "Pilot modules via Gradle (publishToMavenLocal), then mvn -DskipTests install on the whole flow-components reactor."
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
```

- [ ] **Step 3: Verify the single-command flow**

```bash
./gradlew :flow-components:clean --no-daemon
./gradlew :flow-components:build --no-daemon
```

Expected: BUILD SUCCESSFUL. Output ordering: `:npmInstall` → pilot subprojects' `publishToMavenLocal` → mvn install. Pilot modules' mvn compile steps log as up-to-date / skipped.

- [ ] **Step 4: Commit**

```bash
git add gradle/flow-components.gradle.kts
git commit -m "build(flow-components): wire :flow-components:build to pilot publishToMavenLocal + :npmInstall"
```

---

## Task 17: CI updates — install job command + new `flow-components-pilot-gradle` job

**Files:**
- Modify: `.github/workflows/validation.yml:80-82` (Workspace install step), and append the new job

- [ ] **Step 1: Update the install job's command**

In `.github/workflows/validation.yml`, modify the existing `Workspace install` step:

```yaml
      - name: Workspace install
        if: steps.cache.outputs.cache-hit != 'true'
        run: ./gradlew :flow-components:build --no-daemon
```

(Change is `:flow-components:build` instead of `install`. The dependency wiring from Task 16 ensures everything cascades.)

- [ ] **Step 2: Add the new pilot-gradle job**

Insert this block in `.github/workflows/validation.yml`, after the `flow-components-its` job and before `web-components-verify`:

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

- [ ] **Step 3: Add the new job to `results.needs`**

In the same file, modify the `results` job's `needs:` line:

```yaml
  results:
    name: Collect results
    needs: [install, flow-components-unit, flow-components-wtr, flow-components-its, flow-components-pilot-gradle, web-components-verify, web-components-unit, web-components-visual]
```

- [ ] **Step 4: Validate the workflow file syntax**

Run: `yamllint -d "{rules: {line-length: disable}}" .github/workflows/validation.yml` (or just open in editor if `yamllint` not installed).
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "ci: install runs :flow-components:build; add flow-components-pilot-gradle job"
```

---

## Task 18: Documentation update

**Files:**
- Modify: `CLAUDE.md` (workspace)

- [ ] **Step 1: Update the workspace `CLAUDE.md`**

In `CLAUDE.md`, find the `## Workspace-Level Build` section and update the table to reflect the new behavior of `./gradlew build`, and add a subsection describing the pilot:

After the existing `| Command | What it does |` table, append:

```markdown
### Gradle-native pilot modules

A subset of `flow-components` modules is built natively by Gradle (no `mvn` invocation), reading their `pom.xml` files via the included build at `gradle/plugins/`. Current pilot scope: `vaadin-flow-components-shared-parent/*` and `vaadin-button-flow-parent/*` (5 leaf modules).

Pilot subprojects share Maven's `target/` output layout and publish to Maven Local, so `./gradlew :flow-components:build` runs them via Gradle and then `mvn -DskipTests install` over the rest of the reactor; mvn skips already-built pilot work.

Per-module entry points:

```bash
./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow:test
./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow-integration-tests:integrationTest
```

To add a new pilot module: create `gradle/flow-components/<parent>/<module>.gradle.kts` containing one line — `plugins { id("vaadin.workspace.java-library") }` (or `vaadin.workspace.integration-tests` for IT modules) — and run `./gradlew :flow-components:build`.

Design: `docs/superpowers/specs/2026-06-12-gradle-maven-bridge-design.md`.
```

- [ ] **Step 2: Verify the doc renders OK**

```bash
head -100 CLAUDE.md
```
Expected: well-formed Markdown.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document Gradle-native pilot module entry points"
```

---

## Acceptance checks

Run these end-to-end before opening a PR:

- [ ] `./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow:test --no-daemon` — passes.
- [ ] `./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow-integration-tests:integrationTest --no-daemon` — passes end-to-end (Jetty starts, TestBench tests run, Jetty stops).
- [ ] `./gradlew :flow-components:build --no-daemon` — runs pilot publish + mvn install over rest; succeeds.
- [ ] `./gradlew :flow-components:test --no-daemon` — passes (acknowledging duplicate pilot test execution).
- [ ] `./gradlew :gradle-plugins:check --no-daemon` — unit + functional tests for the plugin code pass.
- [ ] CI green on the branch, including `flow-components-pilot-gradle`.

---

## Notes for the implementer

- **Order matters for the plugins block.** Layer 2 plugins apply `workspace.maven-module` first; the model has to be parsed before any other configuration reads it. Don't reorder.
- **`gradle.projectsEvaluated` runs once after all projects are configured.** The deduplication flag on `gradle.extensions` ensures only the first plugin application installs the listener; subsequent applications skip it.
- **Reflective calls in `IntegrationTestsConventionPlugin`** are a hedge against Vaadin Gradle plugin / Gretty surface drift. If they fail at runtime, dump the extension's `javaClass.methods` to find current setter signatures.
- **Frontend bundle path collisions:** if `vaadinBuildFrontend` outputs collide with Maven's `flow-maven-plugin` outputs in Task 16's full-reactor run, add a `cleanBundleAfterIntegrationTest` finalizer task (spec §"Frontend bundle path collisions") to the IT subproject.
- **First-build network requirement:** `PomReader`'s `NetworkModelResolver` downloads `vaadin-parent` from Maven Central / vaadin-prereleases on first parse and caches in `~/.m2`. CI restores `~/.m2` from cache; local devs see one network round-trip on first build.
