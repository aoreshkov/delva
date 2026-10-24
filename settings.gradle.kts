import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "delva"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// The IntelliJ Platform's repositories for delva-intellij-plugin. A settings plugin can't take its version from the
// catalog; delva-intellij-plugin applies the project plugin that comes with it, without a version.
plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Last, so only what Maven Central doesn't have is looked up there: the IDEs and the platform's own artifacts.
        intellijPlatform {
            defaultRepositories()
        }
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
    ":delva-intellij-plugin",
)
