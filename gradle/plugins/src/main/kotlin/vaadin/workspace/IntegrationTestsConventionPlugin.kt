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
        val vaadinExt = project.extensions.findByName("vaadin")
        if (vaadinExt != null && flowPlugin != null) {
            applyChild(flowPlugin, "frontendDirectory") { value ->
                vaadinExt.javaClass.getMethod("setFrontendDirectory", String::class.java).invoke(vaadinExt, value)
            }
            applyChild(flowPlugin, "productionMode") { value ->
                vaadinExt.javaClass.getMethod("setProductionMode", Boolean::class.javaPrimitiveType).invoke(vaadinExt, value.toBoolean())
            }
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
        project.extensions.configure(SourceSetContainer::class.java) {
            create("integrationTest") {
                compileClasspath += named("main").get().output + named("test").get().output
                runtimeClasspath += output + named("test").get().runtimeClasspath
                java.srcDir("src/test/java")
            }
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
}
