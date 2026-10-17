// Common Kotlin/JVM setup for every Delva module: JDK 25 toolchain, JDK 22 bytecode and API floor, warnings as
// errors, test tasks.
//
// Test tasks (selected by test class name suffix, so test code needs no JUnit-specific annotations):
//   test             fast unit and property tests (everything not matched below)
//   simTest          *SimTest          deterministic simulation; -Pdelva.testProfile=long for the long seed list
//   conformanceTest  *ConformanceTest  parser conformance (JSONTestSuite)
//   heapCappedTest   *HeapCappedTest   huge documents under a 256 MB heap
//   crashTest        *CrashTest        real crashes of a child JVM
//   reportTest       *ReportTest       benchmark report (minutes; numbers for the record)
// `check` runs test, conformanceTest, simTest (short profile) and checkSelfContainedComments, which rejects comments
// citing documents outside the repository. heapCappedTest and crashTest run weekly in CI (long-tests.yml), which also
// runs reportTest and the long simTest profile on demand.
// Reproduce a property failure: ./gradlew test --tests <Class> -Ddelva.seed=<seed> -Ddelva.size=<size>
// Rewrite golden files: ./gradlew test -Ddelva.updateGolden=true

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Group and version come from the module's release train (ReleaseTrain.kt), first, so every later read of `version`
// sees them.
joinReleaseTrain()

val libs = the<VersionCatalogsExtension>().named("libs")

extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors = true
        // Consumers need JDK 22 (where the FFM API behind mapped reads became final), not the toolchain's 25.
        // -Xjdk-release also rejects JDK API newer than 22, which jvmTarget alone does not.
        jvmTarget = JvmTarget.JVM_22
        // No DefaultImpls classes: there are no pre-2.2 Kotlin consumers to stay binary compatible with.
        freeCompilerArgs.addAll("-Xjdk-release=22", "-jvm-default=no-compatibility")
    }
}

// Kotlin's JVM target validation requires the Java compile target to match.
tasks.withType<JavaCompile>().configureEach {
    options.release = 22
}

dependencies {
    "testImplementation"(libs.findLibrary("kotlin-test-junit5").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

val specialTestSuffixes = mapOf(
    "simTest" to "SimTest",
    "conformanceTest" to "ConformanceTest",
    "heapCappedTest" to "HeapCappedTest",
    "crashTest" to "CrashTest",
    "reportTest" to "ReportTest",
)

val testProfile = providers.gradleProperty("delva.testProfile").orElse("short")
// Forwarded from the Gradle command line to the test JVM.
val forwardedSystemProperties = listOf("delva.seed", "delva.size", "delva.updateGolden", "delva.heapCapped.bytes", "delva.sample.json", "delva.model.iterations", "delva.compare.iterations", "delva.sim.scenarios", "delva.sim.operations", "delva.sample.key", "delva.sample.value", "delva.report.megabytes", "delva.report.shapes")
    .associateWith { providers.systemProperty(it) }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("delva.testProfile", testProfile.get())
    forwardedSystemProperties.forEach { (name, value) -> value.orNull?.let { systemProperty(name, it) } }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<Test>("test") {
    specialTestSuffixes.values.forEach { exclude("**/*$it.class") }
}

val testSourceSet = the<SourceSetContainer>()["test"]

specialTestSuffixes.forEach { (taskName, suffix) ->
    tasks.register<Test>(taskName) {
        description = "Runs *$suffix test classes."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        include("**/*$suffix.class")
        // Most modules have no tests of a given kind.
        failOnNoDiscoveredTests = false
        shouldRunAfter(tasks.named("test"))
    }
}

tasks.named<Test>("heapCappedTest") {
    maxHeapSize = "256m"
    jvmArgs("-XX:MaxDirectMemorySize=64m")
}

val checkSelfContainedComments = registerCheckSelfContainedComments()

tasks.named("check") {
    dependsOn("conformanceTest", "simTest", checkSelfContainedComments)
}
