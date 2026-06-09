// web-components is an npm workspace member of the outer workspace, so its
// dependencies are installed by the root :npmInstall task. The submodule keeps
// yarn-classic for its own internal dev workflow (run `yarn …` directly inside
// `web-components/`); the outer Gradle build does not invoke yarn.

tasks.register("install") {
    description = "Delegates to the workspace-root npm install."
    group = "build"
    dependsOn(":npmInstall")
}

tasks.named("build") {
    description = "No-op — web-components is source-published. Triggers install."
    dependsOn("install")
}

tasks.register("test") {
    description = "No-op — run `yarn test` inside `web-components/` for the submodule's own test suite."
    group = "verification"
}

tasks.named("clean") {
    description = "No-op — web-components has no build output to clean."
}
