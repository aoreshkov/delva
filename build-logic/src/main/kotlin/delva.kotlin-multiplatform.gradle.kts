// Common Kotlin Multiplatform setup, the counterpart of delva.kotlin-jvm for a module whose code isn't tied to the JVM.
// One target today, the JVM, with the same toolchain, bytecode and API floor and warnings as errors. The source-set
// layout is what keeps a second target a line in the module's build file rather than a refactor: `commonMain` stays
// platform-free, and `jvmMain` holds anything that names a JDK type.
//
// Tests run on the JVM target as `jvmTest`, part of `check`, with none of delva.kotlin-jvm's special suites.
// Rewrite golden files: ./gradlew <module>:jvmTest -Ddelva.updateGolden=true
// Reproduce a property failure: ./gradlew <module>:jvmTest --tests <Class> -Ddelva.seed=<seed>

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = the<VersionCatalogsExtension>().named("libs")

extensions.configure<KotlinMultiplatformExtension> {
    jvmToolchain(25)
    jvm {
        compilerOptions {
            // The same floor as delva.kotlin-jvm: JDK 22, and no JDK API newer than that.
            jvmTarget = JvmTarget.JVM_22
            freeCompilerArgs.addAll("-Xjdk-release=22", "-jvm-default=no-compatibility")
        }
    }
    compilerOptions {
        allWarningsAsErrors = true
    }
    sourceSets.named("commonTest") {
        dependencies {
            // Resolves to kotlin-test-junit5 on the JVM, because the test task uses the JUnit Platform.
            implementation(libs.findLibrary("kotlin-test").get())
        }
    }
    sourceSets.named("jvmTest") {
        dependencies {
            runtimeOnly(libs.findLibrary("junit-platform-launcher").get())
        }
    }
}

// Kotlin's JVM target validation requires the Java compile target to match.
tasks.withType<JavaCompile>().configureEach {
    options.release = 22
}

val forwardedSystemProperties = listOf("delva.updateGolden", "delva.seed")
    .associateWith { providers.systemProperty(it) }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Forwarded from the Gradle command line to the test JVM.
    forwardedSystemProperties.forEach { (name, value) -> value.orNull?.let { systemProperty(name, it) } }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val checkSelfContainedComments = registerCheckSelfContainedComments()

tasks.named("check") {
    dependsOn(checkSelfContainedComments)
}
