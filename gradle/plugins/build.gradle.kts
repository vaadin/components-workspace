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

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        // Plugins will be registered as they are introduced in later tasks.
    }
}
