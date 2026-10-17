plugins {
    id("delva.multiplatform-library")
}

description = "cli-kit-testing: test doubles, a run harness and golden files for programs built on cli-kit"

// Published beside cli-kit, in its release train, for a program's tests: a program's own copy of a test double
// drifts from the kit it doubles. Test-only in spirit, so it depends on cli-kit and kotlin-test and nothing else;
// `checkPublishedDependencies` asserts that of the published module metadata rather than trusting this comment.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":cli-kit"))
                implementation(libs.kotlin.test)
            }
        }
    }
}

// A stable JPMS module name, rather than one derived from the jar's file name.
tasks.named<Jar>("jvmJar") {
    manifest {
        attributes("Automatic-Module-Name" to "app.oreshkov.clikit.testing")
    }
}

val allowedPublishedModules = setOf(
    "app.oreshkov.clikit:cli-kit",
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains.kotlin:kotlin-test",
    "org.jetbrains.kotlin:kotlin-test-junit5",
)

val jvmModuleFile = tasks.named<GenerateModuleMetadata>("generateMetadataFileForJvmPublication")
    .flatMap { it.outputFile }

val checkPublishedDependencies = tasks.register("checkPublishedDependencies") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that the published JVM module lists only cli-kit, kotlin-test and the standard library."
    val moduleFile = jvmModuleFile
    val allowed = allowedPublishedModules
    inputs.file(moduleFile)
    doLast {
        // Every dependency of every variant (API, runtime, sources) in the Gradle module metadata.
        val metadata = groovy.json.JsonSlurper().parse(moduleFile.get().asFile) as Map<*, *>
        val found = (metadata["variants"] as List<*>)
            .flatMap { variant -> ((variant as Map<*, *>)["dependencies"] as List<*>?).orEmpty() }
            .map { dependency -> (dependency as Map<*, *>).let { "${it["group"]}:${it["module"]}" } }
            .toSortedSet()
        val unexpected = found - allowed
        check(unexpected.isEmpty()) { "cli-kit-testing would publish unexpected dependencies: $unexpected (all: $found)" }
        check("app.oreshkov.clikit:cli-kit" in found) { "cli-kit-testing must publish its dependency on cli-kit (all: $found)" }
    }
}

tasks.named("check") {
    dependsOn(checkPublishedDependencies)
}
