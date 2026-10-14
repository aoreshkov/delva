package app.oreshkov.delva.internal.engine

/** How [Engine.open] treats the directory it's given. */
internal enum class Opening {
    /** Create a store; fail if there is one already. */
    CREATE,

    /** Open the store there is; fail, changing nothing, if there is none. */
    OPEN,

    /** Open the store there is, or create one. */
    OPEN_OR_CREATE,
}
