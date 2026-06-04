import com.github.gradle.node.npm.task.NpmTask

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

apply(plugin = "com.github.node-gradle.node")
extensions.configure<com.github.gradle.node.NodeExtension> {
    download.set(false)
    nodeProjectDir.set(rootDir)
    workDir.set(layout.buildDirectory.dir("nodejs"))
    npmWorkDir.set(layout.buildDirectory.dir("npm"))
    yarnWorkDir.set(layout.buildDirectory.dir("yarn"))
}

val npmInstall = tasks.named<NpmTask>("npmInstall") {
    description = "Installs all workspace npm dependencies."
    group = "build"
    dependsOn(":flow-components:syncFlowOverlays")
    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
}

tasks.register("install") {
    description = "Installs dependencies for all subprojects."
    group = "build"
    dependsOn(":web-components:install", ":flow-components:install", npmInstall)
}

project(":web-components") {
    afterEvaluate {
        tasks.named("install") {
            mustRunAfter(":npmInstall")
        }
    }
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

