package workspace.maven

sealed interface GradleDependencySpec {
    val configuration: String

    data class ProjectRef(override val configuration: String, val projectPath: String) : GradleDependencySpec
    data class External(override val configuration: String, val coordinate: String) : GradleDependencySpec
    data class Platform(override val configuration: String, val coordinate: String) : GradleDependencySpec
}

class DependencyMapper(private val registry: GavRegistry) {

    fun map(dep: MavenDependency): GradleDependencySpec {
        val configuration = scopeToConfiguration(dep.scope)
        val registered = registry.lookup(dep.groupId, dep.artifactId)
        if (registered != null) {
            return GradleDependencySpec.ProjectRef(configuration, registered)
        }
        val coord = if (dep.version != null) "${dep.groupId}:${dep.artifactId}:${dep.version}"
                    else "${dep.groupId}:${dep.artifactId}"
        return GradleDependencySpec.External(configuration, coord)
    }

    /** Maven `provided` scope maps to both `compileOnly` and `testImplementation`. */
    fun mapProvided(dep: MavenDependency): List<GradleDependencySpec> {
        require(dep.scope == "provided") { "mapProvided requires provided scope, got ${dep.scope}" }
        val coord = if (dep.version != null) "${dep.groupId}:${dep.artifactId}:${dep.version}"
                    else "${dep.groupId}:${dep.artifactId}"
        return listOf(
            GradleDependencySpec.External("compileOnly", coord),
            GradleDependencySpec.External("testImplementation", coord),
        )
    }

    /** Map a `<scope>import</scope>` + `<type>pom</type>` BOM entry to a Gradle platform dependency. */
    fun mapBomImport(dep: MavenDependency): GradleDependencySpec {
        require(dep.scope == "import" && dep.type == "pom") {
            "mapBomImport requires <scope>import</scope> + <type>pom</type>, got ${dep.scope}/${dep.type}"
        }
        val version = dep.version ?: error("BOM import must have explicit version")
        return GradleDependencySpec.Platform("implementation", "${dep.groupId}:${dep.artifactId}:$version")
    }

    private fun scopeToConfiguration(scope: String): String = when (scope) {
        "compile" -> "implementation"
        "runtime" -> "runtimeOnly"
        "test" -> "testImplementation"
        "provided" -> error("Call mapProvided() for provided scope")
        "system" -> error("system scope is not supported")
        "import" -> error("Call mapBomImport() for import scope")
        else -> error("Unknown Maven scope: $scope")
    }
}
