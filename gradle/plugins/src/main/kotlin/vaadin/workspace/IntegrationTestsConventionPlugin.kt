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
        project.plugins.apply("com.vaadin.flow")
        project.plugins.apply("org.gretty")

        val model = project.extensions.getByName("mavenModel") as MavenModel
        val flowPlugin = model.pluginConfiguration("com.vaadin:flow-maven-plugin")

        // Map flow-maven-plugin config → Vaadin Gradle plugin extension.
        // The IT pom binds the `build-frontend` goal, which only makes sense in
        // production mode — enable it by default so `vaadinBuildFrontend` is wired
        // up correctly. A POM-level `<productionMode>` override still wins.
        val vaadinExt = project.extensions.findByName("vaadin")
        if (vaadinExt != null && flowPlugin != null) {
            trySetVaadinProperty(vaadinExt, "productionMode", true, Boolean::class.javaPrimitiveType!!)
            applyChild(flowPlugin, "frontendDirectory") { value ->
                // Resolve the relative POM path against the project dir — the Vaadin
                // Gradle plugin treats the value as an absolute file location.
                val resolved = project.file(value).absoluteFile
                trySetVaadinProperty(vaadinExt, "frontendDirectory", resolved.path, String::class.java)
            }
            applyChild(flowPlugin, "productionMode") { value ->
                trySetVaadinProperty(vaadinExt, "productionMode", value.toBoolean(), Boolean::class.javaPrimitiveType!!)
            }
            model.property("vaadin.pnpm.enable")?.let { value ->
                trySetVaadinProperty(vaadinExt, "pnpmEnable", value.toBoolean(), Boolean::class.javaPrimitiveType!!)
            }
            applyChild(flowPlugin, "nodeAutoUpdate") { value ->
                trySetVaadinProperty(vaadinExt, "nodeAutoUpdate", value.toBoolean(), Boolean::class.javaPrimitiveType!!)
            }
        }

        // Gretty configuration. Gretty exposes ports as plain mutable Groovy fields
        // (`httpPort`, `stopPort`), not Gradle `Property<Integer>` — use field access
        // so we don't depend on synthetic setter generation.
        val grettyExt = project.extensions.findByName("gretty")
        if (grettyExt != null) {
            val httpPort = model.property("jetty.http.port")?.toIntOrNull() ?: 8080
            val stopPort = model.property("jetty.stop.port")?.toIntOrNull() ?: 9999
            setGrettyField(grettyExt, "httpPort", httpPort)
            setGrettyField(grettyExt, "stopPort", stopPort)
        }

        // Translate the POM's `build-frontend` profile (active by default since `!skipFrontend`)
        // into a `war`→`vaadinBuildFrontend` dependency so the bundled `target/*.war`
        // contains the production frontend, matching Maven's lifecycle binding.
        // `vaadinBuildFrontend` reads token-service state from `vaadinPrepareFrontend`, so
        // the prepare task must run first.
        if (model.pluginConfiguration("com.vaadin:flow-maven-plugin") != null) {
            project.tasks.named("vaadinBuildFrontend") {
                dependsOn("vaadinPrepareFrontend")
            }
            // `vaadinPrepareFrontend` writes the token file under `build/vaadin-generated`,
            // which is included in the main source set's resources by the Vaadin plugin —
            // so `processResources` reads it as an input and must wait for prepare to finish.
            project.tasks.named("processResources") {
                dependsOn("vaadinPrepareFrontend")
            }
            project.tasks.named("war") {
                dependsOn("vaadinBuildFrontend")
            }
        }

        // integrationTest task — JUnit 4, runs against Gretty-managed Jetty.
        // The IT sources sit under `src/test/java`, same as unit tests, so we mirror
        // the `test` source set's classpath (TestBench / Selenium / JUnit 4 live there)
        // by extending its configurations instead of just copying its output.
        project.extensions.configure(SourceSetContainer::class.java) {
            create("integrationTest") {
                java.srcDir("src/test/java")
                compileClasspath += named("main").get().output + named("test").get().compileClasspath
                runtimeClasspath += output + named("main").get().output + named("test").get().runtimeClasspath
            }
        }
        project.configurations.named("integrationTestImplementation") {
            extendsFrom(project.configurations.getByName("testImplementation"))
        }
        project.configurations.named("integrationTestRuntimeOnly") {
            extendsFrom(project.configurations.getByName("testRuntimeOnly"))
        }
        val integrationTest = project.tasks.register("integrationTest", Test::class.java) {
            val its = (project.extensions.getByName("sourceSets") as SourceSetContainer).getByName("integrationTest")
            description = "Runs TestBench integration tests against Gretty's Jetty."
            group = "verification"
            testClassesDirs = its.output.classesDirs
            classpath = its.runtimeClasspath
            useJUnit()
            dependsOn("vaadinBuildFrontend")
            dependsOn("appBeforeIntegrationTest")
            finalizedBy("appAfterIntegrationTest")
        }
        project.tasks.named("check") { dependsOn(integrationTest) }
    }

    private fun applyChild(parent: Xpp3Dom, key: String, action: (String) -> Unit) {
        parent.getChild(key)?.value?.takeIf { it.isNotBlank() }?.let(action)
    }

    private fun setGrettyField(ext: Any, fieldName: String, value: Any) {
        // Walk the class hierarchy to find a declared field (the decorated class
        // typically inherits from `GrettyExtension`).
        var cls: Class<*>? = ext.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(ext, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
    }

    /**
     * Set a property on the Vaadin Gradle plugin extension. The extension exposes
     * its settings as Gradle `Property<T>` instances, so we:
     *   1. Try a plain setter `setName(rawType)` first (older plugin versions),
     *   2. Otherwise call `getName().set(...)`, coercing the value if the
     *      `Property` is typed to `java.io.File` and we have a `String` path.
     * Falling back gracefully avoids hard failures when the plugin API drifts.
     */
    private fun trySetVaadinProperty(ext: Any, name: String, value: Any, rawType: Class<*>) {
        val capitalized = name.replaceFirstChar { it.uppercaseChar() }
        try {
            ext.javaClass.getMethod("set$capitalized", rawType).invoke(ext, value)
            return
        } catch (_: NoSuchMethodException) { /* fall through to property accessor */ }

        try {
            val prop = ext.javaClass.getMethod("get$capitalized").invoke(ext) ?: return
            // Property exposes a `Class<T> getType()` for `Property<T>` (and similar
            // for ListProperty / DirectoryProperty / FileSystemLocationProperty).
            // Use it to know what to coerce to; fall back to the raw input on miss.
            val propType: Class<*>? = try {
                prop.javaClass.getMethod("getType").invoke(prop) as? Class<*>
            } catch (_: NoSuchMethodException) { null }
            val coerced: Any = when {
                propType == null -> value
                propType.isInstance(value) -> value
                propType == java.io.File::class.java && value is String -> java.io.File(value)
                else -> value
            }
            // Gradle's `Property<T>` declares both `set(T)` and `set(Provider<? extends T>)`.
            // Reflection may pick either via `methods`, so prefer the one whose parameter type
            // actually accepts our value.
            val setMethod = prop.javaClass.methods.firstOrNull {
                it.name == "set" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isInstance(coerced)
            } ?: prop.javaClass.methods.firstOrNull {
                it.name == "set" && it.parameterCount == 1 && it.parameterTypes[0] == Any::class.java
            } ?: return
            setMethod.invoke(prop, coerced)
        } catch (_: NoSuchMethodException) { /* leave property unset; downstream may complain. */ }
    }
}
