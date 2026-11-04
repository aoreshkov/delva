plugins {
    id("delva.kotlin-jvm")
    application
}

description = "Delva examples: runnable programs that use the public API"

dependencies {
    implementation(project(":delva"))
}

application {
    mainClass = "app.oreshkov.delva.examples.TypedSearchKt"
}

tasks.register<JavaExec>("runEmbeddingSearch") {
    description = "Runs the EmbeddingSearch example."
    group = ApplicationPlugin.APPLICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "app.oreshkov.delva.examples.EmbeddingSearchKt"
}
