// Published multiplatform library modules, the counterpart of delva.library: explicit API mode, ABI validation, and a
// signed Maven publication per target plus the root one that carries the Gradle module metadata, each with sources
// and KDoc. The module sets its own JPMS name on `jvmJar`, and a consumer depends on the root coordinate.
//
// Publishing (see project/RELEASING.md): `publishToMavenLocal` for a local try-out,
// `publishAllPublicationsToStagingRepository` for the signed bundle that goes to Maven Central's Portal.

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("delva.kotlin-multiplatform")
    `maven-publish`
    signing
    id("org.jetbrains.dokka")
}

extensions.configure<KotlinMultiplatformExtension> {
    explicitApi()
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()
}

// Source links point at the release tag, so the KDoc matches the released source; snapshots link to main.
val sourceRef = version.toString().let { if (it.endsWith("-SNAPSHOT")) "main" else "v$it" }
dokka {
    moduleName = project.name
    dokkaSourceSets.configureEach {
        val sourceSet = name
        sourceLink {
            localDirectory = layout.projectDirectory.dir("src/$sourceSet/kotlin")
            remoteUrl("https://github.com/aoreshkov/delva/blob/$sourceRef/${project.name}/src/$sourceSet/kotlin")
            remoteLineSuffix = "#L"
        }
    }
}

// Maven Central requires a javadoc artifact for every publication; for Kotlin that's the rendered KDoc.
val javadocJar = tasks.register<Jar>("javadocJar") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Packages the rendered KDoc as the -javadoc artifact."
    archiveClassifier = "javadoc"
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

// Only the licence: NOTICE is about code in the engine module.
tasks.withType<Jar>().configureEach {
    into("META-INF") {
        from(rootProject.layout.projectDirectory.file("LICENSE"))
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
        pom { delvaDefaults(project) }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(stagingRepository)
        }
    }
}

signing {
    signWhenKeyed(project, "publishAllPublicationsToStagingRepository", publishing.publications)
}

// Every sign task writes signatures next to the one javadoc jar, so each publish task has to wait for all of them.
tasks.withType<AbstractPublishToMaven>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}

// Every public declaration has KDoc.
val checkPublicKDoc = registerCheckPublicKDoc()
tasks.named("check") {
    dependsOn(checkPublicKDoc)
}
