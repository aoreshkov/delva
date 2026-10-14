package app.oreshkov.delva.testkit.generate

import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.OracleNumbers

/**
 * The expected search results for one `key = value` query (scalar values only) over a [GeneratedJson] document,
 * computed from its events in one pass with O(depth) memory: the oracle for documents too big for the in-memory
 * `OracleStore`.
 *
 * Attach it as the generator's listener, then read or drain the stream.
 */
class StreamingExpectation(private val key: String, private val value: OValue) : JsonEventListener {
    init {
        require(value is OStr || value is ONum || value is OBool || value == ONull) { "only scalar query values" }
    }

    /** Whether the document matches in WHOLE_DOCUMENT mode. */
    val documentMatches: Boolean get() = containingHits > 0

    /** Number of CONTAINING_ELEMENT hits with nested hits included. */
    var containingHits: Long = 0
        private set

    /** Number of CONTAINING_ELEMENT hits that aren't nested inside another hit. */
    var outermostHits: Long = 0
        private set

    /** Pre-order numbers of the first [maxRecordedHits] containing objects that matched, in match order. */
    val firstHitContainers: MutableList<Long> = ArrayList()

    var nodeCount: Long = 0
        private set

    var maxDepth: Int = 0
        private set

    private val maxRecordedHits = 1000

    private class Frame(val isObject: Boolean, val pre: Long, val ownerKey: String?) {
        var currentKey: String? = null
        var matched = false
        var outermostBelow = 0L
    }

    private val stack = ArrayList<Frame>()
    private var string: StringBuilder? = null
    private val maxStringChars = (value as? OStr)?.value?.length?.plus(1) ?: 0

    override fun startObject() = open(isObject = true)

    override fun startArray() = open(isObject = false)

    override fun endObject() = close()

    override fun endArray() = close()

    override fun key(name: String) {
        stack.last().currentKey = name
    }

    override fun stringStart() {
        valueStart()
        string = StringBuilder()
    }

    override fun stringPart(text: String) {
        val sb = string ?: return
        if (sb.length <= maxStringChars) sb.append(text, 0, minOf(text.length, maxStringChars + 1 - sb.length))
    }

    override fun stringEnd() {
        val s = checkNotNull(string).toString()
        string = null
        if (value is OStr && s == value.value) matched()
    }

    override fun number(text: String) {
        valueStart()
        if (value is ONum && OracleNumbers.compare(text, value.text) == 0) matched()
    }

    override fun literal(value: OValue) {
        valueStart()
        if (value == this.value) matched()
    }

    private fun open(isObject: Boolean) {
        val pre = valueStart()
        val parent = stack.lastOrNull()
        val ownerKey = if (!isObject && parent != null && parent.isObject) parent.currentKey else null
        stack += Frame(isObject, pre, ownerKey)
        maxDepth = maxOf(maxDepth, stack.size)
    }

    private fun close() {
        val frame = stack.removeAt(stack.size - 1)
        val contribution = if (frame.matched) 1L else frame.outermostBelow
        val parent = stack.lastOrNull()
        if (parent != null) parent.outermostBelow += contribution else outermostHits = contribution
    }

    private fun valueStart(): Long = nodeCount++

    /** The scalar just seen equals the query value: find its containing object, if the key fits. */
    private fun matched() {
        val parent = stack.lastOrNull() ?: return // scalar root
        val container = when {
            parent.isObject && parent.currentKey == key -> parent
            !parent.isObject && parent.ownerKey == key -> stack[stack.size - 2]
            else -> return
        }
        if (container.matched) return
        container.matched = true
        containingHits++
        if (firstHitContainers.size < maxRecordedHits) firstHitContainers += container.pre
    }
}
