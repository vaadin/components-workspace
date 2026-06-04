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
    }
}
