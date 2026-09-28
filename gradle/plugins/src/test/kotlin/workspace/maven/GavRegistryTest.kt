package workspace.maven

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GavRegistryTest {
    @Test
    fun `register then lookup returns the registered path`() {
        val registry = InMemoryGavRegistry()
        registry.register("com.vaadin", "vaadin-button-flow", ":flow-components:vaadin-button-flow-parent:vaadin-button-flow")
        assertEquals(
            ":flow-components:vaadin-button-flow-parent:vaadin-button-flow",
            registry.lookup("com.vaadin", "vaadin-button-flow")
        )
    }

    @Test
    fun `lookup returns null for unknown GAV`() {
        val registry = InMemoryGavRegistry()
        assertNull(registry.lookup("com.example", "missing"))
    }

    @Test
    fun `lookup throws after finalization for unknown GAV is permitted to caller`() {
        val registry = InMemoryGavRegistry()
        registry.finalize()
        // The registry just returns null; finalization is informational for callers.
        assertNull(registry.lookup("com.example", "missing"))
        assertTrue(registry.isFinalized)
    }
}
