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
