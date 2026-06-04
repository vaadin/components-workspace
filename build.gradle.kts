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

