import java.io.File

pluginManagement {
    includeBuild("gradle/plugins")
    repositories {
        gradlePluginPortal()
        mavenLocal()
        maven { url = uri("https://maven.vaadin.com/vaadin-prereleases/") }
    }
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
            buildFileName = buildFile.relativeTo(projectDir).path
        }
    }
