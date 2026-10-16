plugins {
    id("delva.cli-application")
}

description = "delva: the command line for Delva stores"

dependencies {
    implementation(project(":delva"))
    implementation(project(":cli-kit"))
    testImplementation(project(":cli-kit-testing"))
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
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains:annotations",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-coroutines-bom",
)

val runtimeRoot = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }

val checkRuntimeDependencies = tasks.register("checkRuntimeDependencies") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that the runtime classpath holds only delva, cli-kit and what they need."
    val root = runtimeRoot
    val allowed = allowedRuntimeModules
    doLast {
        val found = sortedSetOf<String>()
        fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            for (dependency in component.dependencies) {
                if (dependency !is org.gradle.api.artifacts.result.ResolvedDependencyResult) continue
                val selected = dependency.selected
                val module = selected.moduleVersion?.let { "${it.group}:${it.name}" } ?: selected.id.displayName
                if (found.add(module)) walk(selected)
            }
        }
        walk(root.get())
        val unexpected = found - allowed
        check(unexpected.isEmpty()) { "unexpected runtime dependencies: $unexpected (runtime classpath: $found)" }
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
