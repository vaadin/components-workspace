package workspace.maven

import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import org.apache.maven.model.Repository as MavenRepoModel
import org.apache.maven.model.building.ModelSource2
import org.codehaus.plexus.util.xml.Xpp3Dom
import java.io.File

class PomReader(
    private val modelResolver: ModelResolver = OfflineModelResolver()
) {
    fun read(pomFile: File): MavenModel {
        val builder = DefaultModelBuilderFactory().newInstance()
        val request = DefaultModelBuildingRequest().apply {
            setPomFile(pomFile)
            validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
            isProcessPlugins = true
            modelResolver = this@PomReader.modelResolver
            // Default profile only — no -P activations honored.
        }
        val result = builder.build(request)
        val model = result.effectiveModel

        val pluginConfigs = mutableMapOf<String, Xpp3Dom>()
        model.build?.plugins?.forEach { plugin ->
            val cfg = plugin.configuration as? Xpp3Dom ?: return@forEach
            pluginConfigs["${plugin.groupId}:${plugin.artifactId}"] = cfg
        }

        return MavenModel(
            groupId = model.groupId,
            artifactId = model.artifactId,
            version = model.version,
            packaging = model.packaging ?: "jar",
            properties = model.properties.entries.associate { it.key.toString() to it.value.toString() },
            dependencies = model.dependencies.map { dep ->
                MavenDependency(
                    groupId = dep.groupId,
                    artifactId = dep.artifactId,
                    version = dep.version,
                    scope = dep.scope ?: "compile",
                    optional = dep.optional?.toBoolean() ?: false,
                    type = dep.type ?: "jar",
                )
            },
            dependencyManagement = model.dependencyManagement?.dependencies?.map { dep ->
                MavenDependency(
                    groupId = dep.groupId,
                    artifactId = dep.artifactId,
                    version = dep.version,
                    scope = dep.scope ?: "compile",
                    optional = false,
                    type = dep.type ?: "jar",
                )
            } ?: emptyList(),
            // Filter out the super-POM-injected Maven Central repository so we
            // surface only repositories that were declared by the project (or
            // inherited from in-build parents). The super POM injects an entry
            // with id "central" pointing at repo.maven.apache.org.
            repositories = model.repositories
                .filterNot { it.id == "central" && it.url == "https://repo.maven.apache.org/maven2" }
                .map { MavenRepository(it.id, it.url) },
            pluginConfigurations = pluginConfigs,
        )
    }
}

/** Placeholder — full resolver added in Task 3. */
class OfflineModelResolver : ModelResolver {
    override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource2 =
        throw UnresolvableModelException("External parents not supported by OfflineModelResolver", groupId, artifactId, version)
    override fun resolveModel(parent: org.apache.maven.model.Parent): ModelSource2 =
        resolveModel(parent.groupId, parent.artifactId, parent.version)
    override fun resolveModel(dependency: org.apache.maven.model.Dependency): ModelSource2 =
        resolveModel(dependency.groupId, dependency.artifactId, dependency.version)
    override fun addRepository(repository: MavenRepoModel) {}
    override fun addRepository(repository: MavenRepoModel, replace: Boolean) {}
    override fun newCopy(): ModelResolver = this
}
