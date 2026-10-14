// Micro-benchmarks of internal hot paths inside a library module: a `jmh` compilation (src/jmh/kotlin) associated
// with `main`, so it sees `internal` declarations. It isn't part of the published artifact.
// Run: ./gradlew :<module>:jmhBenchmark
// Allocation profile: build with :<module>:jmhBenchmarkJar, then run the jar from build/benchmarks/jmh/jars
// with `java -jar <jar> -prof gc`.

import kotlinx.benchmark.gradle.BenchmarksExtension
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.allopen.gradle.AllOpenExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool

plugins {
    id("delva.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("org.jetbrains.kotlinx.benchmark")
}

val libs = the<VersionCatalogsExtension>().named("libs")

extensions.configure<AllOpenExtension> {
    annotation("org.openjdk.jmh.annotations.State")
}

val jmhCompilation = extensions.getByType<KotlinJvmProjectExtension>().target.compilations.let { compilations ->
    compilations.create("jmh") {
        associateWith(compilations.getByName("main"))
    }
}

// The plugin fills its jar from a provider that checks which classpath directories exist, and the configuration cache
// stores that answer: in a clean tree the `jmh` classes aren't there yet, so the jar left them out and its generated
// code failed with NoClassDefFoundError. The compiler's output property is resolved when the jar is built; when the
// plugin found the classes too, its copy wins. (Its task is an `org.gradle.jvm.tasks.Jar`, which the Kotlin DSL's
// default `Jar` extends, hence the import.)
tasks.withType<Jar>().named { it == "jmhBenchmarkJar" }.configureEach {
    from(tasks.named<KotlinCompileTool>(jmhCompilation.compileKotlinTaskName).flatMap { it.destinationDirectory }) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

dependencies {
    "jmhImplementation"(libs.findLibrary("kotlinx-benchmark-runtime").get())
}

extensions.configure<BenchmarksExtension> {
    targets.register("jmh")
}

