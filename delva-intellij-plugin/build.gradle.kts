// Delva Tools, the IntelliJ Platform plugin. It isn't on a Maven release train: its version is delvaTools.version,
// and it ships to JetBrains Marketplace. It builds on its own terms rather than the delva.kotlin-jvm conventions,
// because the IDE sets them: its Java 25 runtime, its Kotlin standard library and coroutines, and its JUnit 4 test
// framework.

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
        // As in Delva's own modules. It also keeps the compiler from overriding the platform interfaces' default methods
        // with stubs, which would count as uses of the experimental ones among them.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

// The integration test runs a real IDE with the built plugin ZIP installed, through JetBrains' Starter and Driver
// frameworks, from a JVM of its own: unlike the unit tests, it sees the plugin as a user gets it.
val integrationTest: SourceSet = sourceSets.create("integrationTest")

dependencies {
    implementation(libs.delva) {
        // The IDE provides both, and a plugin must use the IDE's copies: a second kotlinx-coroutines in the plugin's
        // class loader breaks the platform's coroutine machinery.
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }

    testImplementation(libs.junit4)

    // Starter leaves out what the IDE provides, but its test JVM isn't the IDE: it needs the standard library,
    // coroutines and TeamCity's service messages of its own, and Delva to make the store the IDE opens.
    "integrationTestImplementation"(kotlin("stdlib"))
    "integrationTestImplementation"(libs.kotlinx.coroutines.core)
    "integrationTestImplementation"(libs.delva)
    "integrationTestRuntimeOnly"(libs.teamcity.serviceMessages)
    "integrationTestRuntimeOnly"(libs.junit.platform.launcher)
    // Starter brings JUnit's engine for JUnit 4 tests, which fails without JUnit 4 beside it.
    "integrationTestRuntimeOnly"(libs.junit4)

    intellijPlatform {
        intellijIdea(libs.versions.intellijIdea)
        testFramework(TestFrameworkType.Platform)
        // Tests of the unified IntelliJ IDEA distribution need its Ultimate module; the JSONPath plugin is the IDE's own
        // JSONPath language, which the dialect test checks against RFC 9535.
        testBundledPlugins("com.intellij.modules.ultimate", "com.intellij.jsonpath")
        testFramework(TestFrameworkType.Starter, configurationName = integrationTest.implementationConfigurationName)
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

val bundledDelvaVersion = if (delvaFromSource) providers.gradleProperty("delva.version") else libs.versions.delva

/** The minor of a version: `0.2` of `0.2.1` or of `0.2.0-SNAPSHOT`. */
fun minorOf(version: String): String = version.split('.', '-').take(2).joinToString(".")

// The plugin names the Delva minor it reads in its messages; the constant comes from the version it bundles, so the two
// can't disagree.
val generateBundledDelva = tasks.register("generateBundledDelva") {
    description = "Generates the constant that names the Delva minor the plugin bundles."
    val minor = minorOf(bundledDelvaVersion.get())
    val outputDirectory = layout.buildDirectory.dir("generated/sources/bundledDelva/kotlin")
    inputs.property("minor", minor)
    outputs.dir(outputDirectory)
    doLast {
        val file = outputDirectory.get().file("app/oreshkov/delva/intellij/store/BundledDelva.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package app.oreshkov.delva.intellij.store
            |
            |/** The Delva minor this plugin bundles, whose store format is the only one it reads. */
            |internal const val BUNDLED_DELVA_MINOR: String = "$minor"
            |""".trimMargin(),
        )
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateBundledDelva)
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

        // The change notes are this version's section of CHANGELOG.md, or Unreleased's for a snapshot: the IntelliJ
        // Platform plugin's convention when the changelog plugin is applied.

        ideaVersion {
            sinceBuild = "262"
            // JetBrains recommends leaving the upper bound open and verifying each new IDE instead.
            untilBuild = provider { null }
        }
    }
    // signPlugin and publishPlugin read the key, its certificate chain and the Marketplace token from the environment
    // (PRIVATE_KEY, PRIVATE_KEY_PASSWORD, CERTIFICATE_CHAIN, PUBLISH_TOKEN), so the build holds no secret.
    publishing {
        // An upload stays hidden after Marketplace approves it, until a person unhides it: the version goes public
        // together with its GitHub release, as a Central deployment waits to be published.
        hidden = true
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

configurations.named(integrationTest.runtimeClasspathConfigurationName) {
    // Starter's kotlin-reflect would otherwise lag the standard library it runs with.
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-reflect:${kotlin.coreLibrariesVersion}")
}

// Downloads and starts IntelliJ IDEA of the version the plugin builds against, installs the ZIP buildPlugin made, and
// drives the IDE's UI; left out of check, as it takes minutes and a desktop.
intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        useJUnitPlatform()
        systemProperty("delva.ideVersion", libs.versions.intellijIdea.get())
        // The project the IDE opens. Starter keeps the IDE it downloads and each run's logs and screenshots in the
        // repository's out/ide-tests.
        systemProperty("delva.integrationTestDir", layout.buildDirectory.dir("integrationTest").get().asFile.path)
        // Starter reports each run to Allure as well, by default into the module's own directory.
        systemProperty("allure.results.directory", layout.buildDirectory.dir("integrationTest/allure-results").get().asFile.path)
    }
}

// The plugin's own verifyPluginSignature names the signed ZIP by its path alone, so Gradle won't run it after signPlugin
// in one build; taking signPlugin's output makes it depend on the task that writes the file.
tasks.verifyPluginSignature {
    inputArchiveFile = tasks.signPlugin.flatMap { it.signedArchiveFile }
}

// publishPlugin on its own uploads the signed ZIP only when signPlugin did work in the same build: after an up-to-date
// signPlugin, or one skipped for want of a key, it uploads the unsigned ZIP. Here it always takes the signed one, and
// fails without it.
tasks.publishPlugin {
    // The changelog is edited by hand in a release's pull request; patchChangelog, which the IntelliJ Platform plugin
    // runs before a publish, would rewrite it in the working tree.
    setDependsOn(dependsOn - "patchChangelog")
    val signedZip = tasks.signPlugin.flatMap { it.signedArchiveFile }
    archiveFiles.setFrom(signedZip)
    doFirst {
        val file = signedZip.get().asFile
        check(file.isFile) { "${file.name} doesn't exist: an unsigned plugin isn't published" }
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
    val delvaVersion = bundledDelvaVersion.get()
    val expected = sortedSetOf("${project.name}-${project.version}.jar", "delva-$delvaVersion.jar")
    // A release's minor is the Delva minor it reads; built on Delva's source, the plugin reads whatever that is.
    val pluginVersion = project.version.toString()
    val minorsAgree = delvaFromSource || minorOf(pluginVersion) == minorOf(delvaVersion)
    inputs.file(zip)
    doLast {
        check(minorsAgree) {
            "Delva Tools $pluginVersion bundles Delva $delvaVersion; its minor must be Delva's"
        }
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
