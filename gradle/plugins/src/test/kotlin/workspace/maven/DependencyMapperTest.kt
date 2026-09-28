package workspace.maven

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DependencyMapperTest {
    private val registry = InMemoryGavRegistry().apply {
        register("com.vaadin", "vaadin-button-flow", ":flow-components:vaadin-button-flow-parent:vaadin-button-flow")
    }
    private val mapper = DependencyMapper(registry)

    @Test
    fun `compile scope maps to implementation`() {
        val spec = mapper.map(MavenDependency("org.slf4j", "slf4j-api", "2.0.13", "compile", false, "jar"))
        assertEquals("implementation", spec.configuration)
        assertTrue(spec is GradleDependencySpec.External)
        assertEquals("org.slf4j:slf4j-api:2.0.13", (spec as GradleDependencySpec.External).coordinate)
    }

    @Test
    fun `test scope maps to testImplementation`() {
        val spec = mapper.map(MavenDependency("org.junit.jupiter", "junit-jupiter", "5.10.2", "test", false, "jar"))
        assertEquals("testImplementation", spec.configuration)
    }

    @Test
    fun `runtime scope maps to runtimeOnly`() {
        val spec = mapper.map(MavenDependency("ch.qos.logback", "logback-classic", "1.5", "runtime", false, "jar"))
        assertEquals("runtimeOnly", spec.configuration)
    }

    @Test
    fun `provided scope maps to compileOnly plus testImplementation`() {
        val specs = mapper.mapProvided(MavenDependency("jakarta.servlet", "jakarta.servlet-api", "6.0.0", "provided", false, "jar"))
        assertEquals(setOf("compileOnly", "testImplementation"), specs.map { it.configuration }.toSet())
    }

    @Test
    fun `registered GAV resolves to ProjectRef`() {
        val spec = mapper.map(MavenDependency("com.vaadin", "vaadin-button-flow", null, "compile", false, "jar"))
        assertTrue(spec is GradleDependencySpec.ProjectRef)
        assertEquals(":flow-components:vaadin-button-flow-parent:vaadin-button-flow",
            (spec as GradleDependencySpec.ProjectRef).projectPath)
    }

    @Test
    fun `unregistered GAV without version maps to external without version`() {
        val spec = mapper.map(MavenDependency("com.vaadin", "vaadin-icons-flow", null, "compile", false, "jar"))
        assertTrue(spec is GradleDependencySpec.External)
        assertEquals("com.vaadin:vaadin-icons-flow", (spec as GradleDependencySpec.External).coordinate)
    }

    @Test
    fun `BOM import becomes platform dependency`() {
        val spec = mapper.mapBomImport(MavenDependency("com.vaadin", "vaadin-bom", "25.3-SNAPSHOT", "import", false, "pom"))
        assertEquals("implementation", spec.configuration)
        assertTrue(spec is GradleDependencySpec.Platform)
        assertEquals("com.vaadin:vaadin-bom:25.3-SNAPSHOT", (spec as GradleDependencySpec.Platform).coordinate)
    }
}
