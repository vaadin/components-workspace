package vaadin.workspace

import com.diffplug.gradle.spotless.SpotlessExtension
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

        project.extensions.configure(JavaPluginExtension::class.java) {
            toolchain.languageVersion.set(JavaLanguageVersion.of(model.compilerRelease))
        }

        project.tasks.withType(Test::class.java).configureEach { useJUnitPlatform() }

        project.extensions.configure(SpotlessExtension::class.java) {
            val licenseHeader = model.property("spotless.licence-header")?.let { File(it) }
            java {
                target("src/main/java/**/*.java", "src/test/java/**/*.java")
                googleJavaFormat()
                if (licenseHeader?.exists() == true) {
                    licenseHeaderFile(licenseHeader)
                }
            }
        }

        project.extensions.configure(CheckstyleExtension::class.java) {
            val checkstyleConfig = project.rootDir
                .resolve("flow-components/checkstyle/checkstyle.xml")
            if (checkstyleConfig.exists()) {
                configFile = checkstyleConfig
            }
            model.property("checkstyle.version")?.let { toolVersion = it }
        }
    }
}
