package workspace.maven

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MavenModuleProjectPluginTest {
    @TempDir lateinit var projectDir: File

    @Test
    fun `plugin applies and exposes mavenModel extension`() {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        File(projectDir, "pom.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>fixture</artifactId>
                <version>1.0.0</version>
                <packaging>jar</packaging>
                <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                </properties>
            </project>
        """.trimIndent())
        File(projectDir, "build.gradle.kts").writeText("""
            plugins { id("workspace.maven-module") }
            tasks.register("printGav") {
                doLast {
                    val model = project.extensions.getByName("mavenModel") as workspace.maven.MavenModel
                    println("GAV=${'$'}{model.groupId}:${'$'}{model.artifactId}:${'$'}{model.version}")
                }
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("printGav")
            .withPluginClasspath()
            .build()

        assertTrue(result.output.contains("GAV=com.example:fixture:1.0.0"), result.output)
    }

    @Test
    fun `outputs land in Maven target layout when java plugin is applied`() {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        File(projectDir, "pom.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>fixture</artifactId>
                <version>1.0.0</version>
                <packaging>jar</packaging>
                <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                </properties>
            </project>
        """.trimIndent())
        File(projectDir, "src/main/java/com/example").mkdirs()
        File(projectDir, "src/main/java/com/example/Hello.java")
            .writeText("package com.example; public class Hello {}")
        File(projectDir, "build.gradle.kts").writeText("""
            plugins {
                id("workspace.maven-module")
                `java-library`
            }
            java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
        """.trimIndent())

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("jar")
            .withPluginClasspath()
            .build()

        val targetDir = File(projectDir, "target")
        assertTrue(File(targetDir, "classes/java/com/example/Hello.class").exists(),
            "Compiled class should be under target/classes/java/")
        assertTrue(File(targetDir, "fixture-1.0.0.jar").exists(),
            "Jar should be at target/fixture-1.0.0.jar")
    }

    @Test
    fun `publishMavenPublicationToSandboxRepository installs jar and pom into sandbox Maven repo`() {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "fixture-publish"""")
        File(projectDir, "pom.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>fixture-publish</artifactId>
                <version>1.0.0</version>
                <packaging>jar</packaging>
                <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                </properties>
            </project>
        """.trimIndent())
        File(projectDir, "src/main/java/com/example").mkdirs()
        File(projectDir, "src/main/java/com/example/Hello.java")
            .writeText("package com.example; public class Hello {}")
        File(projectDir, "build.gradle.kts").writeText("""
            plugins {
                id("workspace.maven-module")
                `java-library`
            }
            java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
            publishing { repositories { maven { name = "sandbox"; url = uri("${'$'}{rootDir}/sandbox-m2") } } }
        """.trimIndent())

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("publishMavenPublicationToSandboxRepository")
            .withPluginClasspath()
            .build()

        val sandboxM2 = File(projectDir, "sandbox-m2")
        assertTrue(File(sandboxM2, "com/example/fixture-publish/1.0.0/fixture-publish-1.0.0.jar").exists(),
            "Jar should be installed into the sandbox repo")
        assertTrue(File(sandboxM2, "com/example/fixture-publish/1.0.0/fixture-publish-1.0.0.pom").exists(),
            "POM should be installed into the sandbox repo")
    }
}
