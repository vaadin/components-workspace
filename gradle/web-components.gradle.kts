import com.github.gradle.node.yarn.task.YarnTask

tasks.register<YarnTask>("install") {
    description = "Runs `yarn install` in the web-components submodule."
    group = "build"
    args.set(listOf("install"))
    inputs.file("package.json")
    inputs.file("yarn.lock")
    outputs.dir("node_modules")
}

tasks.named("build") {
    description = "Build web-components — no-action; components are source-published. Triggers install."
    dependsOn("install")
}

tasks.register<YarnTask>("test") {
    description = "Runs `yarn test` (default: changed packages only)."
    group = "verification"
    args.set(listOf("test"))
    dependsOn("build")
}

tasks.named<Delete>("clean") {
    description = "Removes node_modules in the web-components submodule."
    delete(file("node_modules"))
}
