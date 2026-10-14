package app.oreshkov.delva.internal.payload

/**
 * Where a fragment sits in its document, beyond its key. One instance is reused between calls; views
 * are valid only during the call that receives it.
 *
 * - [parentPre]: `pre` of the container that directly holds the fragment's top-level values: for a PART its split
 *   container, otherwise the parent of the fragment's value; -1 for the root value.
 * - [ownerObjectPre] and the owner key: the object that has the fragment's value (SUBTREE, HEADER) or split container
 *   (PART) as a direct member, and that member's key; -1 when there's no such object, and always for STRCHUNK. A PART
 *   or SUBTREE of an array uses it for the in-array postings of its elements.
 * - [firstIndex]: for a PART, the position of its first member in the container; 0 otherwise.
 */
internal class FragmentContext {
    var parentPre: Long = -1
        private set
    var ownerObjectPre: Long = -1
        private set
    var ownerKey: ByteArray = EMPTY
        private set
    var ownerKeyOffset: Int = 0
        private set
    var ownerKeyLength: Int = 0
        private set
    var firstIndex: Long = 0
        private set

    /**
     * Path hashes ([app.oreshkov.delva.internal.term.PathHash]) of the container the fragment holds at its top (the
     * SUBTREE's value, the PART's split container), of that container's parent, and of its grandparent; `NONE` where
     * there's no such container, and for fragments without postings.
     */
    var pathHash: Int = 0
        private set
    var parentPathHash: Int = 0
        private set
    var grandparentPathHash: Int = 0
        private set

    val hasOwner: Boolean get() = ownerObjectPre >= 0

    /** Sets the context; the path hashes become `NONE` until [setPaths]. */
    fun set(parentPre: Long, ownerObjectPre: Long, ownerKey: ByteArray, ownerKeyOffset: Int, ownerKeyLength: Int, firstIndex: Long): FragmentContext {
        require(parentPre >= -1 && ownerObjectPre >= -1 && firstIndex >= 0)
        require(ownerObjectPre <= parentPre)
        this.parentPre = parentPre
        this.ownerObjectPre = ownerObjectPre
        this.ownerKey = if (ownerObjectPre < 0) EMPTY else ownerKey
        this.ownerKeyOffset = if (ownerObjectPre < 0) 0 else ownerKeyOffset
        this.ownerKeyLength = if (ownerObjectPre < 0) 0 else ownerKeyLength
        this.firstIndex = firstIndex
        pathHash = 0
        parentPathHash = 0
        grandparentPathHash = 0
        return this
    }

    fun setPaths(pathHash: Int, parentPathHash: Int, grandparentPathHash: Int): FragmentContext {
        this.pathHash = pathHash
        this.parentPathHash = parentPathHash
        this.grandparentPathHash = grandparentPathHash
        return this
    }

    /** No parent, no owner: the root value's fragment. */
    fun setRoot(): FragmentContext = set(-1, -1, EMPTY, 0, 0, 0)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
