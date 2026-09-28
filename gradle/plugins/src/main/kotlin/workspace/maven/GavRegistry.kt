package workspace.maven

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentHashMap

interface GavRegistry {
    fun register(groupId: String, artifactId: String, projectPath: String)
    fun lookup(groupId: String, artifactId: String): String?
    fun finalize()
    val isFinalized: Boolean
}

/** Plain-object implementation used in unit tests and as the runtime store inside [GavRegistryBuildService]. */
class InMemoryGavRegistry : GavRegistry {
    private val entries = ConcurrentHashMap<String, String>()
    @Volatile private var finalized = false

    override fun register(groupId: String, artifactId: String, projectPath: String) {
        entries["$groupId:$artifactId"] = projectPath
    }

    override fun lookup(groupId: String, artifactId: String): String? =
        entries["$groupId:$artifactId"]

    override fun finalize() {
        finalized = true
    }

    override val isFinalized: Boolean
        get() = finalized
}

abstract class GavRegistryBuildService :
    BuildService<BuildServiceParameters.None>,
    GavRegistry by InMemoryGavRegistry()
