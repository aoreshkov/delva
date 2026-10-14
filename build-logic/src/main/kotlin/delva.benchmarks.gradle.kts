// Benchmark module: kotlinx-benchmark (JMH) over the module's main source set, all-open for @State classes.

import kotlinx.benchmark.gradle.BenchmarksExtension
import org.jetbrains.kotlin.allopen.gradle.AllOpenExtension

plugins {
    id("delva.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("org.jetbrains.kotlinx.benchmark")
}

val libs = the<VersionCatalogsExtension>().named("libs")

extensions.configure<AllOpenExtension> {
    annotation("org.openjdk.jmh.annotations.State")
}

dependencies {
    "implementation"(libs.findLibrary("kotlinx-benchmark-runtime").get())
}

extensions.configure<BenchmarksExtension> {
    targets.register("main")
}
