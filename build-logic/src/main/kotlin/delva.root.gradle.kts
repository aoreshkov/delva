// The root project builds nothing itself. Its `check` covers what no module does: the build logic's own tests, and
// the comment check over the workflows, the root build scripts and the documents, all of which are published as they
// are. It also holds the release trains' stage tasks.

plugins {
    base
}

val checkSelfContainedComments = tasks.register<CheckSelfContainedComments>("checkSelfContainedComments") {
    description = "Fails on workflows, root build scripts and documents that cite documents outside the repository."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    sources.from(fileTree(".github"))
    sources.from(fileTree(".") { include("*.kts", "*.md") })
    sources.from(fileTree("project") { include("**/*.md") })
    // The modules' READMEs and changelogs, which their own check, over their sources, doesn't see.
    sources.from(fileTree(".") { include("*/README.md", "*/CHANGELOG.md") })
    report.set(layout.buildDirectory.file("reports/self-contained-comments.txt"))
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"), checkSelfContainedComments)
}

// A stage task per release train: every publication of the train's modules, signed when the key is configured, in
// the train's staging directory. The release workflow zips that directory into the train's Portal bundle.
ReleaseTrain.entries.forEach { train ->
    tasks.register(train.stageTask) {
        group = "publishing"
        description = "Stages the ${train.title} train's publications in build/staging-repository/${train.id}."
        dependsOn(train.publishedProjects.map { "$it:publishAllPublicationsToStagingRepository" })
    }
}
