package app.oreshkov.delva

/**
 * Marks API that is new in this release and may still change, in its signature or its behaviour, in a minor release.
 * Using it requires `@OptIn(ExperimentalDelvaApi::class)`. The mark is lifted from a declaration once a release has
 * shipped it unchanged; the CHANGELOG says when.
 */
@RequiresOptIn(
    message = "This part of Delva's API may still change in a minor release; opt in with @OptIn(ExperimentalDelvaApi::class).",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
public annotation class ExperimentalDelvaApi

/**
 * Marks the interfaces that are Delva's to implement: [ReadableJsonStore], [JsonStore], [SecondaryJsonStore], their
 * blocking faces ([BlockingReadableJsonStore], [BlockingJsonStore], [BlockingSecondaryJsonStore]), [SearchResult],
 * [SearchHit], [SelectResult] and [SelectedNode]. Calling them needs no opt-in. Implementing one, typically as a fake in a test,
 * requires `@OptIn(DelvaImplementationApi::class)` and accepts that later minor releases add members to it, which an
 * implementation then has to add too.
 */
@RequiresOptIn(
    message = "Delva adds members to this interface in minor releases, which an implementation has to follow; " +
        "opt in with @OptIn(DelvaImplementationApi::class).",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
public annotation class DelvaImplementationApi
