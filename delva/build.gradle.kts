plugins {
    id("delva.library")
    id("delva.internal-benchmarks")
}

description = "Delva: embedded JSON document database engine"

dependencies {
    // Only for the suspend/Flow facade (Flow is part of the API); the engine core doesn't use coroutines.
    api(libs.kotlinx.coroutines.core)
    testImplementation(project(":delva-testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    compilerOptions {
        // Delva implements its own interfaces and uses its own new API; the markers are for its users.
        optIn.addAll("app.oreshkov.delva.DelvaImplementationApi", "app.oreshkov.delva.ExperimentalDelvaApi")
    }
}

tasks.withType<Test>().configureEach {
    // Golden files are rewritten here when a test runs with -Ddelva.updateGolden=true.
    systemProperty("delva.goldenDir", layout.projectDirectory.dir("src/test/resources/golden").asFile.absolutePath)
}
