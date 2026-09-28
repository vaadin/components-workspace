import org.gradle.internal.os.OperatingSystem

plugins {
    id("workspace.maven-module")
}

val mvnCommand = if (OperatingSystem.current().isWindows) "mvn.cmd" else "mvn"

tasks.register("install") {
    description = "No-op for flow-components — Maven resolves dependencies on demand. Kept for task-surface symmetry with :web-components."
    group = "build"
}

tasks.register<Exec>("build") {
    description = "Pilot modules via Gradle (publishToMavenLocal), then mvn -DskipTests install on the whole flow-components reactor."
    group = "build"
    workingDir = projectDir
    commandLine(mvnCommand, "-DskipTests", "install")
    dependsOn("install")
}

gradle.projectsEvaluated {
    tasks.named("build").configure {
        dependsOn(rootProject.subprojects
            .filter { it.path.startsWith(":flow-components:") }
            .filter {
                it.plugins.hasPlugin("vaadin.workspace.java-library") ||
                it.plugins.hasPlugin("vaadin.workspace.integration-tests")
            }
            .map { "${it.path}:publishToMavenLocal" })
    }
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
