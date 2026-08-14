import com.github.gradle.node.npm.task.NpmTask

plugins {
    base
    id("com.github.node-gradle.node") version "7.1.0" apply false
}

project(":web-components") {
    apply(plugin = "base")
}

apply(plugin = "com.github.node-gradle.node")
extensions.configure<com.github.gradle.node.NodeExtension> {
    download.set(false)
    nodeProjectDir.set(rootDir)
    workDir.set(layout.buildDirectory.dir("nodejs"))
    npmWorkDir.set(layout.buildDirectory.dir("npm"))
}

val npmInstall = tasks.named<NpmTask>("npmInstall") {
    description = "Installs all workspace npm dependencies."
    group = "build"
    dependsOn(":flow-components:syncFlowOverlays")
    // `ignore-scripts=true` is set in `.npmrc` at the workspace root; see the
    // comment there for why postinstall hooks must be skipped.
    inputs.file("package.json")
    inputs.file("package-lock.json")
    inputs.file("flow-components/package.json")
    outputs.dir("node_modules")
}

val applyWebComponentsPatches = tasks.register<Exec>("applyWebComponentsPatches") {
    description = "Applies web-components/patches/*.patch to node_modules. " +
        "Required because workspace `.npmrc` disables postinstall scripts."
    group = "build"
    workingDir = rootDir
    commandLine("bash", "scripts/apply-web-components-patches.sh")
    inputs.dir("web-components/patches")
    inputs.dir("node_modules/@web")
    outputs.file("node_modules/@web/test-runner-visual-regression/index.d.ts")
    outputs.file("node_modules/@web/test-runner-visual-regression/dist/visualDiffCommand.js")
    outputs.file("node_modules/@web/rollup-plugin-html/dist/output/emitAssets.js")
    outputs.file("node_modules/lerna/dist/index.js")
}

val symlinkWebComponentsBin = tasks.register<Exec>("symlinkWebComponentsBin") {
    description = "Creates web-components/node_modules/.bin symlink to the " +
        "hoisted workspace-root bin, so upstream code finds lerna at the " +
        "relative path it expects."
    group = "build"
    workingDir = rootDir
    commandLine("bash", "scripts/setup-web-components-bin.sh")
    inputs.dir("node_modules/.bin")
    outputs.dir("web-components/node_modules/.bin")
}

npmInstall.configure {
    finalizedBy(applyWebComponentsPatches, symlinkWebComponentsBin)
}

tasks.register("install") {
    description = "Installs dependencies for all subprojects."
    group = "build"
    dependsOn(":web-components:install", ":flow-components:install", npmInstall)
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

