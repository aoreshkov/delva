// The root project builds nothing itself. Its `check` covers what no module does: the build logic's own tests, and
// the comment check over the workflows, the root build scripts and the documents outside the modules, all of which
// are published as they are.

plugins {
    base
}

val checkSelfContainedComments = tasks.register<CheckSelfContainedComments>("checkSelfContainedComments") {
    description = "Fails on workflows, root build scripts and documents that cite documents outside the repository."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    sources.from(fileTree(".github"))
    sources.from(fileTree(".") { include("*.kts", "*.md") })
    sources.from(fileTree("project") { include("**/*.md") })
    report.set(layout.buildDirectory.file("reports/self-contained-comments.txt"))
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"), checkSelfContainedComments)
}
