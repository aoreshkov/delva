plugins {
    id("delva.kotlin-jvm")
}

description = "Delva test kit: property harness, generators, oracle, simulated file system"

dependencies {
    api(project(":delva"))
    implementation(libs.kotlin.test)
}
