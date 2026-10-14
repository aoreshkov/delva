plugins {
    id("delva.benchmarks")
}

description = "Delva benchmarks (kotlinx-benchmark / JMH)"

dependencies {
    implementation(project(":delva"))
}
