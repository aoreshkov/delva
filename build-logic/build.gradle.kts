plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.kotlinx.benchmark.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.changelog.gradle.plugin)

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
