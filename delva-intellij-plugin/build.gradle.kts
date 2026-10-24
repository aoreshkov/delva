// Delva Tools, the IntelliJ Platform plugin. It isn't on a Maven release train: its version is delvaTools.version,
// and it ships to JetBrains Marketplace. It builds on its own terms rather than the delva.kotlin-jvm conventions,
// because the IDE sets them: its Java 25 runtime, its Kotlin standard library and coroutines, and its JUnit 4 test
// framework.

import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import java.util.zip.ZipFile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

description = "Delva Tools: browse and search Delva stores from IntelliJ-based IDEs"
version = providers.gradleProperty("delvaTools.version").get()

// The plugin bundles a released Delva, pinned in the catalog, so it never ships Delva code that no Delva release
// contains, and reads exactly that release's store format. -Pdelva.delvaFromSource=true builds it on Delva's source in
// this repository instead, to try a Delva change in the plugin before it is released.
val delvaFromSource = providers.gradleProperty("delva.delvaFromSource").map(String::toBoolean).getOrElse(false)

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.delva) {
        // The IDE provides both, and a plugin must use the IDE's copies: a second kotlinx-coroutines in the plugin's
        // class loader breaks the platform's coroutine machinery.
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }

    testImplementation(libs.junit4)

    intellijPlatform {
        intellijIdea(libs.versions.intellijIdea)
        testFramework(TestFrameworkType.Platform)
        // Tests of the unified IntelliJ IDEA distribution need its Ultimate module; the JSONPath plugin is the IDE's own
        // JSONPath language, which the dialect test checks against RFC 9535.
        testBundledPlugins("com.intellij.modules.ultimate", "com.intellij.jsonpath")
    }
}

if (delvaFromSource) {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("app.oreshkov.delva:delva")).using(project(":delva"))
                .because("delva.delvaFromSource builds on Delva's source")
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        // The Marketplace description is the README's, between its plugin description markers.
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map { readme ->
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            check(start in readme && end in readme) { "README.md has no $start ... $end section" }
            markdownToHTML(readme.substringAfter(start).substringBefore(end))
        }

        // The change notes are this version's section of CHANGELOG.md, or Unreleased's for a snapshot.
        val changelog = project.changelog
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = "262"
            // JetBrains recommends leaving the upper bound open and verifying each new IDE instead.
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // The release of the lowest supported IDE and the newest EAP.
            recommended()
        }
        // The plugin must stay dynamic, and off API that may change without notice or is about to go.
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INVALID_PLUGIN,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
            FailureLevel.NON_EXTENDABLE_API_USAGES,
            FailureLevel.EXPERIMENTAL_API_USAGES,
            FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            FailureLevel.NOT_DYNAMIC,
        )
    }
}

changelog {
    repositoryUrl = "https://github.com/aoreshkov/delva"
    // A release's tag is delva-tools-vX.Y.Z, which tells it apart from Delva's and cli-kit's.
    versionPrefix = "delva-tools-v"
}

val checkPluginDistribution = tasks.register("checkPluginDistribution") {
    description = "Checks that the plugin ZIP bundles only the plugin's jar and Delva, not the libraries the IDE provides."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val zip = tasks.buildPlugin.flatMap { it.archiveFile }
    val delvaVersion = if (delvaFromSource) providers.gradleProperty("delva.version").get() else libs.versions.delva.get()
    val expected = sortedSetOf("${project.name}-${project.version}.jar", "delva-$delvaVersion.jar")
    inputs.file(zip)
    doLast {
        val jars = ZipFile(zip.get().asFile).use { file ->
            file.entries().asSequence()
                .map { it.name }
                .filter { it.substringBeforeLast('/').endsWith("/lib") && it.endsWith(".jar") }
                .map { it.substringAfterLast('/') }
                .toSortedSet()
        }
        check(jars == expected) { "The plugin ZIP bundles $jars; expected exactly $expected" }
    }
}

val checkSelfContainedComments = registerCheckSelfContainedComments()

tasks.check {
    dependsOn(checkPluginDistribution, checkSelfContainedComments)
}
