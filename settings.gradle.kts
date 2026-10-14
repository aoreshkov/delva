rootProject.name = "delva"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    ":delva",
    ":delva-testkit",
    ":delva-benchmarks",
    ":delva-examples",
    ":delva-kotlinx-serialization",
    ":cli-kit",
    ":cli-kit-testing",
    ":delva-cli",
)
