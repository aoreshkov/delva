// Command-line applications built on cli-kit: delva.kotlin-jvm plus Gradle's application plugin, for start scripts
// (`installDist`) and the zip and tar the GitHub release carries (`distZip`, `distTar`). Not published to Maven.
//
// The module sets `application.mainClass` and `applicationName`; the scripts honour JAVA_OPTS and <NAME>_OPTS.

plugins {
    id("delva.kotlin-jvm")
    application
}

application {
    // cli-kit enables ANSI on a legacy Windows console with an FFM downcall, a restricted method (JEP 472): without
    // this the JVM warns on the first call today and will refuse it in a later release.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

val appVersion = version.toString()

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            // Read back by cli-kit's implementationVersion for --version.
            "Implementation-Version" to appVersion,
            // The same opt-in, for a launch with `java -jar`; ALL-UNNAMED is the only value a manifest accepts.
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}

tasks.named<JavaExec>("run") {
    // So a command reading `-` gets the terminal's input under ./gradlew run.
    standardInput = System.`in`
}

distributions {
    named("main") {
        contents {
            from(rootProject.layout.projectDirectory.file("LICENSE"))
            from(rootProject.layout.projectDirectory.file("NOTICE"))
        }
    }
}
