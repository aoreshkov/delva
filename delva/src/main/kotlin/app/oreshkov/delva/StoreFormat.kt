package app.oreshkov.delva

/**
 * The format a store's files are written in, as [JsonStore.probe] read it from the store's metadata. Until 1.0, a minor
 * version of Delva may write a new format version and read only its own. A store is in one format for its whole life:
 * format 1, or format 2 when it was created with a [CreationOptions.vectorField].
 */
@ExperimentalDelvaApi
public class StoreFormat internal constructor(
    /** The format version the store is in. */
    public val version: Int,
    /** The format versions this version of Delva reads, ascending; the store opens when they include [version]. */
    public val supportedVersions: List<Int>,
) {
    /** Whether this version of Delva reads the store: otherwise opening it throws [UnsupportedFormatException]. */
    public val supported: Boolean get() = version in supportedVersions

    override fun toString(): String = "StoreFormat(version=$version, supportedVersions=$supportedVersions)"
}
