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
}
