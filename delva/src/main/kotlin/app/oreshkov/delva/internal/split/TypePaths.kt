package app.oreshkov.delva.internal.split

import app.oreshkov.delva.Normalization
import app.oreshkov.delva.internal.canon.Nfc
import app.oreshkov.delva.internal.path.PathStep
import app.oreshkov.delva.internal.path.SingularPath
import app.oreshkov.delva.internal.types.TypeNames
import java.util.Arrays

/**
 * Type assignments given with an insert, and the store's document key path, compiled into a trie that the splitter
 * follows while streaming: each open container remembers its trie node, and a value start looks up the child for its
 * key or index. The node of the document key is [keyNode].
 *
 * Paths are [SingularPath]s (SPEC.md §6.1). Nodes are ints; [NONE] means no path continues. Immutable and thread-safe.
 */
internal class TypePaths private constructor(
    private val childStart: IntArray,
    private val childEnd: IntArray,
    private val childIsIndex: BooleanArray,
    private val childIndex: LongArray,
    private val childKeys: Array<ByteArray>,
    private val childNode: IntArray,
    private val typeNames: Array<String?>,
    private val pathTexts: Array<String?>,
    /** The node the document key path ends at, or [NONE]. */
    val keyNode: Int,
    /** The document key path as it was given, or null. */
    val keyPath: String?,
) {
    /** Number of trie nodes; node ids are `0 until nodeCount`. */
    val nodeCount: Int get() = typeNames.size

    val isEmpty: Boolean = keyNode == NONE && typeNames.all { it == null }

    /** The node of the document root (`$`), or [NONE] when there are no paths. */
    val root: Int get() = if (isEmpty) NONE else 0

    fun typeOf(node: Int): String? = typeNames[node]

    /** The first path text that assigns a type at [node]. */
    fun pathOf(node: Int): String? = pathTexts[node]

    fun childByKey(node: Int, key: ByteArray, offset: Int, length: Int): Int {
        if (node == NONE) return NONE
        for (c in childStart[node] until childEnd[node]) {
            if (!childIsIndex[c]) {
                val k = childKeys[c]
                if (k.size == length && Arrays.equals(k, 0, length, key, offset, offset + length)) return childNode[c]
            }
        }
        return NONE
    }

    fun childByIndex(node: Int, index: Long): Int {
        if (node == NONE) return NONE
        for (c in childStart[node] until childEnd[node]) {
            if (childIsIndex[c] && childIndex[c] == index) return childNode[c]
        }
        return NONE
    }

    /** Whether some path continues below [node]. */
    fun hasChildren(node: Int): Boolean = node != NONE && childEnd[node] > childStart[node]

    companion object {
        const val NONE: Int = -1

        val EMPTY: TypePaths = compile(emptyMap())

        /**
         * Compiles `path → type name`, and the document key path [keyPath] when given. Throws [IllegalArgumentException]
         * for invalid path syntax or when two paths select the same value with different types, and
         * [app.oreshkov.delva.InvalidTypeNameException] for an invalid type name. With NFC, a type name or a name in a
         * path whose normalized form has too long a run of combining marks is rejected ([Nfc.normalize]).
         */
        fun compile(paths: Map<String, String>, normalization: Normalization = Normalization.NONE, keyPath: String? = null): TypePaths {
            val nfc = normalization == Normalization.NFC
            class Node {
                val keyChildren = LinkedHashMap<String, Node>()
                val indexChildren = LinkedHashMap<Long, Node>()
                var type: String? = null
                var path: String? = null
            }

            val root = Node()
            for ((text, name) in paths) {
                // A path selects keys, and with NFC the stored keys are normalized, so its steps have to be too.
                val type = if (nfc) Nfc.normalize(name, "type name") else name
                TypeNames.validate(type)
                var node = root
                for (step in SingularPath.parse(text)) {
                    node = when (step) {
                        is PathStep.Name -> node.keyChildren.getOrPut(if (nfc) Nfc.normalize(step.key, "type path") else step.key) { Node() }
                        is PathStep.Index -> node.indexChildren.getOrPut(step.index) { Node() }
                    }
                }
                val previous = node.type
                require(previous == null || previous == type) { "paths ${node.path} and $text assign different types to the same value" }
                node.type = type
                if (node.path == null) node.path = text
            }
            var key: Node? = null
            if (keyPath != null) {
                key = root
                for (step in SingularPath.parse(keyPath)) {
                    key = when (step) {
                        is PathStep.Name -> key!!.keyChildren.getOrPut(if (nfc) Nfc.normalize(step.key, "document key path") else step.key) { Node() }
                        is PathStep.Index -> key!!.indexChildren.getOrPut(step.index) { Node() }
                    }
                }
            }

            // Breadth-first numbering; each node's children are contiguous.
            val order = ArrayList<Node>()
            order += root
            var i = 0
            while (i < order.size) {
                val n = order[i++]
                order += n.keyChildren.values
                order += n.indexChildren.values
            }
            val ids = HashMap<Node, Int>().apply { order.forEachIndexed { id, n -> put(n, id) } }
            val childCount = order.size - 1
            val childStart = IntArray(order.size)
            val childEnd = IntArray(order.size)
            val isIndex = BooleanArray(childCount)
            val index = LongArray(childCount)
            val keys = Array(childCount) { ByteArray(0) }
            val child = IntArray(childCount)
            var c = 0
            for ((id, n) in order.withIndex()) {
                childStart[id] = c
                for ((key, k) in n.keyChildren) {
                    keys[c] = key.toByteArray(Charsets.UTF_8)
                    child[c++] = ids.getValue(k)
                }
                for ((position, k) in n.indexChildren) {
                    isIndex[c] = true
                    index[c] = position
                    child[c++] = ids.getValue(k)
                }
                childEnd[id] = c
            }
            return TypePaths(
                childStart, childEnd, isIndex, index, keys, child,
                Array(order.size) { order[it].type }, Array(order.size) { order[it].path },
                if (key == null) NONE else ids.getValue(key), keyPath,
            )
        }
    }
}
