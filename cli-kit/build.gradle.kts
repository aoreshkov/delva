plugins {
    id("delva.multiplatform-library")
}

description = "cli-kit: parser, help, completions, tables, terminal I/O and a program runner for command-line tools"

// No dependencies, deliberately: cli-kit depends on the Kotlin standard library and nothing else, not even delva, and
// knows no command of any program's; a program declares its own tree of `CommandSpec`s. That is what keeps it reusable
// as it stands, and what makes its `commonMain` compile for Kotlin/Native the day a native target appears.
// `IsolationTest` asserts it rather than trusting this comment. The tests use cli-kit-testing, which is test-only.
kotlin {
    compilerOptions {
        // The kit uses its own new API; the marker is for its users.
        optIn.add("app.oreshkov.clikit.ExperimentalCliKitApi")
    }
    sourceSets {
        commonTest {
            dependencies {
                implementation(project(":cli-kit-testing"))
            }
        }
    }
}

// A stable JPMS module name, rather than one derived from the jar's file name.
tasks.named<Jar>("jvmJar") {
    manifest {
        attributes("Automatic-Module-Name" to "app.oreshkov.clikit")
    }
}

tasks.withType<Test>().configureEach {
    // The Windows VT downcall goes through the FFM API. Without this the JVM warns on the first downcall today and
    // refuses it in a later release. A program using cli-kit passes the same flag to its own JVM.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
