package app.oreshkov.clikit

/**
 * Marks API that is new and may still change, in its signature or its behaviour, in a minor release. Using it requires
 * `@OptIn(ExperimentalCliKitApi::class)`. The mark is lifted from a declaration in the minor release after one that
 * shipped it unchanged; cli-kit's README says where each marked feature stands, and the CHANGELOG when a mark is lifted.
 */
@RequiresOptIn(
    message = "This part of cli-kit's API may still change in a minor release; opt in with @OptIn(ExperimentalCliKitApi::class).",
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
public annotation class ExperimentalCliKitApi
