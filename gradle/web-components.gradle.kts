// web-components is an npm workspace member of the outer workspace, so its
// dependencies are installed by the root :npmInstall task. The submodule keeps
// yarn-classic for its own internal dev workflow (run `yarn …` directly inside
// `web-components/`); the outer Gradle build does not invoke yarn.
//
// :web-components:install is a pure no-op — the root :install task triggers
// :npmInstall directly, so there is no need to wire that dependency here. This
// keeps :web-components:install from re-triggering a workspace-level install
// when invoked on its own.

tasks.register("install") {
    description = "No-op — workspace deps install via the root :npmInstall task."
    group = "build"
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
