package workspace.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class PomReaderTest {
    private fun fixture(name: String) =
        Paths.get("src/test/resources/poms", name).toFile()

    @Test
    fun `parses GAV and packaging from a simple POM`() {
        val model = PomReader().read(fixture("simple.xml"))
        assertEquals("com.example", model.groupId)
        assertEquals("simple", model.artifactId)
        assertEquals("1.0.0", model.version)
        assertEquals("jar", model.packaging)
    }

    @Test
    fun `extracts properties from a simple POM`() {
        val model = PomReader().read(fixture("simple.xml"))
        assertEquals("21", model.properties["maven.compiler.release"])
        assertEquals("header.txt", model.properties["spotless.licence-header"])
    }

    @Test
    fun `extracts dependencies with scopes from a simple POM`() {
        val model = PomReader().read(fixture("simple.xml"))
        val slf4j = model.dependencies.single { it.artifactId == "slf4j-api" }
        assertEquals("org.slf4j", slf4j.groupId)
        assertEquals("2.0.13", slf4j.version)
        assertEquals("compile", slf4j.scope)

        val junit = model.dependencies.single { it.artifactId == "junit-jupiter" }
        assertEquals("test", junit.scope)
    }

    @Test
    fun `extracts repositories declared in the POM`() {
        val model = PomReader().read(fixture("simple.xml"))
        assertEquals(listOf("https://repo.example.com"), model.repositories.map { it.url })
    }

    @Test
    fun `resolves external parent POM transparently`() {
        // Use the actual flow-components root POM, which has vaadin-parent as external parent.
        val pomFile = Paths.get("../../flow-components/pom.xml").toFile()
        if (!pomFile.exists()) return  // skip if submodule not checked out

        val model = PomReader().read(pomFile)
        // vaadin-parent contributes the prereleases repo via inheritance.
        val urls = model.repositories.map { it.url }
        assertTrue(urls.any { it.contains("vaadin-prereleases") },
            "Expected vaadin-prereleases in resolved repositories: $urls")
    }
}
