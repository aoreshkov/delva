plugins {
    id("delva.cli-application")
}

description = "delva: the command line for Delva stores"

// cli-kit is a release train of its own, so delva-cli builds on a released cli-kit, pinned in the catalog: a Delva
// release then never ships cli-kit code that no cli-kit release contains. -Pdelva.cliKitFromSource=true builds it on
// cli-kit's source in this repository instead, to try a cli-kit change before it is released.
val cliKitFromSource = providers.gradleProperty("delva.cliKitFromSource").map(String::toBoolean).getOrElse(false)

dependencies {
    implementation(project(":delva"))
    implementation(libs.cli.kit)
    testImplementation(libs.cli.kit.testing)
}

if (cliKitFromSource) {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("app.oreshkov.clikit:cli-kit")).using(project(":cli-kit"))
                .because("delva.cliKitFromSource builds on cli-kit's source")
            substitute(module("app.oreshkov.clikit:cli-kit-testing")).using(project(":cli-kit-testing"))
                .because("delva.cliKitFromSource builds on cli-kit's source")
        }
    }
}

application {
    mainClass = "app.oreshkov.delva.cli.MainKt"
    applicationName = "delva"
}

// delva-cli-X.Y.Z.zip and .tar in the GitHub release, unpacking to delva-cli-X.Y.Z/bin/delva: a name that can't be
// mistaken for the library's jar.
distributions {
    named("main") {
        distributionBaseName = "delva-cli"
    }
}

// What the distribution ships: delva, cli-kit and what those two need, nothing else. A dependency added here by
// accident would ship in every release zip, so the resolved runtime classpath is checked rather than trusted.
val allowedRuntimeModules = setOf(
    "app.oreshkov.delva:delva",
    "app.oreshkov.clikit:cli-kit",
    // A released cli-kit's root module points the JVM at this one; built from source, only cli-kit itself appears.
    "app.oreshkov.clikit:cli-kit-jvm",
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains:annotations",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-coroutines-bom",
)

val runtimeRoot = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }

val checkRuntimeDependencies = tasks.register("checkRuntimeDependencies") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that the runtime classpath holds only delva, cli-kit and what they need, and where cli-kit " +
        "comes from."
    val root = runtimeRoot
    val allowed = allowedRuntimeModules
    val fromSource = cliKitFromSource
    doLast {
        val found = sortedSetOf<String>()
        val cliKitComponents = mutableListOf<org.gradle.api.artifacts.component.ComponentIdentifier>()
        fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            for (dependency in component.dependencies) {
                if (dependency !is org.gradle.api.artifacts.result.ResolvedDependencyResult) continue
                val selected = dependency.selected
                val module = selected.moduleVersion?.let { "${it.group}:${it.name}" } ?: selected.id.displayName
                if (!found.add(module)) continue
                if (selected.moduleVersion?.group == "app.oreshkov.clikit") cliKitComponents += selected.id
                walk(selected)
            }
        }
        walk(root.get())
        val unexpected = found - allowed
        check(unexpected.isEmpty()) { "unexpected runtime dependencies: $unexpected (runtime classpath: $found)" }
        // A release must ship the cli-kit that a cli-kit release published, so without the switch every cli-kit
        // component has to come from a repository: a project dependency on it would compile, and ship, its source.
        check(cliKitComponents.isNotEmpty()) { "cli-kit is missing from the runtime classpath: $found" }
        val misplaced = cliKitComponents.filter {
            if (fromSource) it !is org.gradle.api.artifacts.component.ProjectComponentIdentifier
            else it !is org.gradle.api.artifacts.component.ModuleComponentIdentifier
        }
        check(misplaced.isEmpty()) {
            val expected = if (fromSource) "cli-kit's source (delva.cliKitFromSource)" else "a repository"
            "cli-kit has to come from $expected, but resolved as ${misplaced.map { it.displayName }}"
        }
    }
}

// *ProcessTest runs the installed start script (bin/delva, bin/delva.bat) in a child process: the manifest version,
// exit statuses, UTF-8 through a real pipe, standard input and DELVA_OPTS are only real there.
val installDir = layout.buildDirectory.dir("install/delva-cli")
val testSourceSet = sourceSets["test"]

tasks.named<Test>("test") {
    exclude("**/*ProcessTest.class")
}

val processTest = tasks.register<Test>("processTest") {
    description = "Runs *ProcessTest classes against the installed distribution."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    include("**/*ProcessTest.class")
    dependsOn(tasks.named("installDist"))
    inputs.dir(installDir)
    systemProperty("delva.cli.home", installDir.get().asFile.absolutePath)
    systemProperty("delva.cli.version", version.toString())
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") {
    dependsOn(checkRuntimeDependencies, processTest)
}
