import org.gradle.api.Project

/**
 * The release trains: each is a set of published modules that share a Maven group, a version, a tag and a changelog,
 * and are released together, independently of the other train (project/RELEASING.md). This table is the one place
 * that says which project rides which train.
 *
 * cli-kit has a train of its own, under a group matching its package and JPMS name, because it is a library in its
 * own right: a program using it shouldn't depend on coordinates named after a database, or get a new version of the
 * kit each time the database changes.
 */
enum class ReleaseTrain(
    /** Names the train's staging directory, its stage task and its Portal deployment. */
    val id: String,
    /** Names the train's releases: "Delva 0.2.0". */
    val title: String,
    val group: String,
    /** The root gradle.properties entry holding the train's version. */
    val versionProperty: String,
    /** A release's tag is this prefix followed by its version. */
    val tagPrefix: String,
    /** The train's published projects, which its stage task bundles. */
    val publishedProjects: List<String>,
) {
    DELVA(
        id = "delva",
        title = "Delva",
        group = "app.oreshkov.delva",
        versionProperty = "delva.version",
        tagPrefix = "v",
        publishedProjects = listOf(":delva", ":delva-kotlinx-serialization"),
    ),
    CLI_KIT(
        id = "cli-kit",
        title = "cli-kit",
        group = "app.oreshkov.clikit",
        versionProperty = "clikit.version",
        tagPrefix = "cli-kit-v",
        publishedProjects = listOf(":cli-kit", ":cli-kit-testing"),
    ),
    ;

    /** The root task that writes the train's bundle to its staging directory: `stageDelva`, `stageCliKit`. */
    val stageTask: String
        get() = "stage" + id.split('-').joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }
}

/** The train a project rides: the one that publishes it, or Delva's for a project that isn't published. */
val Project.releaseTrain: ReleaseTrain
    get() = ReleaseTrain.entries.firstOrNull { path in it.publishedProjects } ?: ReleaseTrain.DELVA

/**
 * Sets the project's group and version from its train. The Kotlin conventions do this before anything else, so every
 * later read of `version` in a convention sees it.
 */
fun Project.joinReleaseTrain() {
    val train = releaseTrain
    group = train.group
    version = providers.gradleProperty(train.versionProperty).orNull
        ?: error("gradle.properties has no ${train.versionProperty}, the version of $path's release train")
}

/** Fails a published project that no train lists: it would get Delva's version and be left out of every bundle. */
fun Project.requirePublishedByATrain() {
    check(ReleaseTrain.entries.any { path in it.publishedProjects }) {
        "$path is published, so a train in ReleaseTrain has to list it in publishedProjects"
    }
}
