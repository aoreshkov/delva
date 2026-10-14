plugins {
    id("delva.library")
    // For the @Serializable classes of the tests; the library itself only calls serializers.
    id("delva.kotlinx-serialization")
}

description = "Delva for kotlinx.serialization: store @Serializable values and read hits back as them"

dependencies {
    api(project(":delva"))
    // Its types are in the signatures: SerializationStrategy, DeserializationStrategy, Json, JsonElement.
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

dokka {
    dokkaSourceSets.configureEach {
        externalDocumentationLinks.register("kotlinx-serialization") {
            url("https://kotlinlang.org/api/kotlinx.serialization/")
        }
    }
}
