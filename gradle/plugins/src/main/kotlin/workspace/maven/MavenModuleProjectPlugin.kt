package workspace.maven

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.net.URI

class MavenModuleProjectPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val pomFile = project.projectDir.resolve("pom.xml")
        check(pomFile.exists()) { "workspace.maven-module requires ${pomFile} to exist" }

        val model = PomReader().read(pomFile)

        val registry = project.gradle.sharedServices.registerIfAbsent(
            "gavRegistry",
            GavRegistryBuildService::class.java
        ) {}
        registry.get().register(model.groupId, model.artifactId, project.path)

        // 1. Repositories — Maven defaults + anything declared in the resolved POM.
        project.repositories.mavenLocal()
        project.repositories.mavenCentral()
        model.repositories.forEach { repo ->
            project.repositories.maven { url = URI.create(repo.url) }
        }

        // 2. Expose the parsed model for build files and Layer 2 conventions to read.
        project.extensions.add("mavenModel", model)

        project.plugins.withId("java") {
            project.extensions.configure(org.gradle.api.tasks.SourceSetContainer::class.java) {
                named("main") {
                    java.destinationDirectory.set(project.file("target/classes"))
                    output.setResourcesDir(project.file("target/classes"))
                }
                named("test") {
                    java.destinationDirectory.set(project.file("target/test-classes"))
                    output.setResourcesDir(project.file("target/test-classes"))
                }
            }
            project.tasks.named("jar", org.gradle.api.tasks.bundling.Jar::class.java) {
                destinationDirectory.set(project.file("target"))
                archiveBaseName.set(model.artifactId)
                archiveVersion.set(model.version)
            }
            project.tasks.named("clean", org.gradle.api.tasks.Delete::class.java) {
                delete(project.file("target"))
            }
        }
        project.plugins.withId("war") {
            project.tasks.named("war", org.gradle.api.tasks.bundling.War::class.java) {
                destinationDirectory.set(project.file("target"))
                archiveBaseName.set(model.artifactId)
                archiveVersion.set(model.version)
            }
        }

        // maven-publish and deferred dep wiring are added in later tasks.
    }
}
