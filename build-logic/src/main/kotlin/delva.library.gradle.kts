// Published library modules: explicit API mode, ABI validation, signed Maven publication with sources and KDoc.
//
// Publishing (see project/RELEASING.md): `publishToMavenLocal` for a local try-out,
// `publishMavenPublicationToStagingRepository` for the signed bundle that goes to Maven Central's Portal. Signing is
// required only when signing properties are set, so an unsigned local publication needs no keys.
//
// A module other than delva names itself in its gradle.properties: `delva.moduleName`, its JPMS module name (its root
// package), and `delva.dokkaName`, the title of its KDoc.

import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("delva.kotlin-jvm")
    `java-library`
    `maven-publish`
    signing
    id("org.jetbrains.dokka")
}

extensions.configure<KotlinJvmProjectExtension> {
    explicitApi()
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()
}

java {
    withSourcesJar()
}

// Source links point at the release tag, so the KDoc matches the released source; snapshots link to main.
val sourceRef = version.toString().let { if (it.endsWith("-SNAPSHOT")) "main" else "v$it" }
dokka {
    moduleName = findProperty("delva.dokkaName")?.toString() ?: "Delva"
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory = layout.projectDirectory.dir("src/main/kotlin")
            remoteUrl("https://github.com/aoreshkov/delva/blob/$sourceRef/${project.name}/src/main/kotlin")
            remoteLineSuffix = "#L"
        }
        externalDocumentationLinks.register("kotlinx-coroutines") {
            url("https://kotlinlang.org/api/kotlinx.coroutines/")
        }
    }
}

// Maven Central requires a javadoc artifact; for Kotlin that's the rendered KDoc.
val javadocJar = tasks.register<Jar>("javadocJar") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Packages the rendered KDoc as the -javadoc artifact."
    archiveClassifier = "javadoc"
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

// The xxHash port (BSD-2-Clause) requires the notice in binary distributions.
val legalFiles = listOf("LICENSE", "NOTICE").map { rootProject.layout.projectDirectory.file(it) }
tasks.withType<Jar>().configureEach {
    into("META-INF") {
        from(legalFiles)
    }
}

// A stable JPMS module name, rather than one derived from the jar's file name.
val automaticModuleName = findProperty("delva.moduleName")?.toString() ?: "app.oreshkov.delva"
tasks.named<Jar>("jar") {
    manifest {
        attributes("Automatic-Module-Name" to automaticModuleName)
    }
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
            artifact(javadocJar)
            pom { delvaDefaults(project) }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(stagingRepository)
        }
    }
}

signing {
    signWhenKeyed(project, "publishMavenPublicationToStagingRepository", publishing.publications)
}

// Every public declaration has KDoc.
val checkPublicKDoc = registerCheckPublicKDoc()
tasks.named("check") {
    dependsOn(checkPublicKDoc)
}
