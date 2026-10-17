import org.gradle.api.DomainObjectCollection
import org.gradle.api.Project
import org.gradle.api.publish.Publication
import org.gradle.api.publish.maven.MavenPom
import org.gradle.plugins.signing.SigningExtension

/**
 * Where a staging publish collects the files that make up a Central upload bundle: a directory per release train, so
 * one train's bundle never picks up the other's files.
 */
val Project.stagingRepository
    get() = rootProject.layout.buildDirectory.dir("staging-repository/${releaseTrain.id}")

/** The POM Maven Central requires, the same for every published module; the description is the module's own. */
fun MavenPom.delvaDefaults(project: Project) {
    name.set(project.name)
    description.set(project.provider { project.description ?: project.name })
    url.set("https://github.com/aoreshkov/delva")
    inceptionYear.set("2026")
    licenses {
        license {
            name.set("Apache-2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
        }
    }
    developers {
        developer {
            id.set("aoreshkov")
            name.set("Atanas Oreshkov")
            url.set("https://github.com/aoreshkov")
        }
    }
    scm {
        url.set("https://github.com/aoreshkov/delva")
        connection.set("scm:git:https://github.com/aoreshkov/delva.git")
        developerConnection.set("scm:git:ssh://git@github.com/aoreshkov/delva.git")
    }
    issueManagement {
        system.set("GitHub Issues")
        url.set("https://github.com/aoreshkov/delva/issues")
    }
}

/**
 * Signs [publications], including any registered later, only when the key is configured, so local publishing and CI
 * builds need no keys (project/RELEASING.md). Signing is then required whenever [stagingTask] is going to run.
 */
fun SigningExtension.signWhenKeyed(project: Project, stagingTask: String, publications: DomainObjectCollection<Publication>) {
    val providers = project.providers
    val signingKey = providers.gradleProperty("signingInMemoryKey")
        .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey"))
    setRequired { signingKey.isPresent && project.gradle.taskGraph.hasTask("${project.path}:$stagingTask") }
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), providers.gradleProperty("signingInMemoryKeyPassword").orElse("").get())
    }
    sign(publications)
}
