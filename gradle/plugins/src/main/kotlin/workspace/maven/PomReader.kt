package workspace.maven

import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import org.apache.maven.model.Repository as MavenRepoModel
import org.apache.maven.model.building.FileModelSource
import org.apache.maven.model.building.ModelSource2
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import java.io.File

class PomReader(
    private val modelResolver: ModelResolver = NetworkModelResolver()
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

class NetworkModelResolver : ModelResolver {
    companion object {
        private val DEFAULT_REPOS = listOf(
            RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2").build(),
            RemoteRepository.Builder("vaadin-prereleases", "default", "https://maven.vaadin.com/vaadin-prereleases").build(),
        )

        private val system: RepositorySystem by lazy {
            val locator = MavenRepositorySystemUtils.newServiceLocator()
            locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
            locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
            locator.getService(RepositorySystem::class.java)
        }

        private val session by lazy {
            val s = MavenRepositorySystemUtils.newSession()
            val localM2 = File(System.getProperty("user.home"), ".m2/repository")
            s.localRepositoryManager = system.newLocalRepositoryManager(s, LocalRepository(localM2))
            s
        }
    }

    private val additionalRepos = mutableListOf<RemoteRepository>()

    override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource2 {
        val artifact = DefaultArtifact("$groupId:$artifactId:pom:$version")
        val req = ArtifactRequest(artifact, additionalRepos + DEFAULT_REPOS, null)
        return try {
            val result = system.resolveArtifact(session, req)
            FileModelSource(result.artifact.file)
        } catch (e: Exception) {
            throw UnresolvableModelException(e.message, groupId, artifactId, version, e)
        }
    }

    override fun resolveModel(parent: org.apache.maven.model.Parent): ModelSource2 =
        resolveModel(parent.groupId, parent.artifactId, parent.version)

    override fun resolveModel(dependency: org.apache.maven.model.Dependency): ModelSource2 =
        resolveModel(dependency.groupId, dependency.artifactId, dependency.version)

    override fun addRepository(repository: MavenRepoModel) = addRepository(repository, false)

    override fun addRepository(repository: MavenRepoModel, replace: Boolean) {
        if (additionalRepos.any { it.id == repository.id } && !replace) return
        additionalRepos.add(
            RemoteRepository.Builder(repository.id, "default", repository.url).build()
        )
    }

    override fun newCopy(): ModelResolver = NetworkModelResolver().also {
        it.additionalRepos.addAll(this.additionalRepos)
    }
}
