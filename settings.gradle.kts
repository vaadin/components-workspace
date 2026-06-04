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
