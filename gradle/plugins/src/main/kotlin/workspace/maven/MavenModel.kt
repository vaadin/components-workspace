package workspace.maven

import org.codehaus.plexus.util.xml.Xpp3Dom

data class MavenModel(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val packaging: String,
    val properties: Map<String, String>,
    val dependencies: List<MavenDependency>,
    val dependencyManagement: List<MavenDependency>,
    val repositories: List<MavenRepository>,
    /** Raw `<configuration>` XML by `groupId:artifactId` of the plugin. */
    val pluginConfigurations: Map<String, Xpp3Dom>
) {
    val compilerRelease: Int
        get() = properties["maven.compiler.release"]?.toInt()
            ?: error("$groupId:$artifactId: maven.compiler.release not set")

    fun property(key: String): String? = properties[key]

    fun pluginConfiguration(coords: String): Xpp3Dom? = pluginConfigurations[coords]
}

data class MavenDependency(
    val groupId: String,
    val artifactId: String,
    val version: String?,
    val scope: String,
    val optional: Boolean,
    val type: String,   // "jar" or "pom" (for BOM imports)
)

data class MavenRepository(
    val id: String,
    val url: String,
)
