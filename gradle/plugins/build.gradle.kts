plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven { url = uri("https://maven.vaadin.com/vaadin-prereleases/") }
}

dependencies {
    implementation("org.apache.maven:maven-model-builder:3.9.9")
    implementation("org.apache.maven:maven-resolver-provider:3.9.9")
    implementation("org.apache.maven.resolver:maven-resolver-impl:1.9.22")
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.22")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.22")
    implementation("com.diffplug.spotless:spotless-plugin-gradle:6.25.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

val functionalTest by sourceSets.creating
configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
    "functionalTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(functionalTestTask) }

gradlePlugin {
    plugins {
        register("workspaceMavenModule") {
            id = "workspace.maven-module"
            implementationClass = "workspace.maven.MavenModuleProjectPlugin"
        }
        register("vaadinWorkspaceJavaLibrary") {
            id = "vaadin.workspace.java-library"
            implementationClass = "vaadin.workspace.JavaLibraryConventionPlugin"
        }
    }
    testSourceSets(functionalTest)
}
