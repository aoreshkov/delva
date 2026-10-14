package app.oreshkov.delva.testkit.json

/**
 * The oracle's JSON tree. Data-class equality is exact (number spelling, member order); semantic equality is
 * [canonicalEquals].
 */
sealed interface OValue

data object ONull : OValue

data class OBool(val value: Boolean) : OValue

/** A number with its original JSON spelling. */
data class ONum(val text: String) : OValue {
    init {
        require(OracleNumbers.isJsonNumber(text)) { "not a JSON number: $text" }
    }
}

data class OStr(val value: String) : OValue

data class OArr(val items: List<OValue>) : OValue {
    constructor(vararg items: OValue) : this(items.toList())
}

data class OObj(val members: List<Pair<String, OValue>>) : OValue {
    constructor(vararg members: Pair<String, OValue>) : this(members.toList())

    init {
        require(members.map { it.first }.toSet().size == members.size) { "duplicate keys" }
    }

    operator fun get(key: String): OValue? = members.firstOrNull { it.first == key }?.second
}

/** Semantic equality: typed, numbers by value, strings by code points, member order ignored, array order kept. */
fun canonicalEquals(a: OValue, b: OValue): Boolean = canonicalForm(a) == canonicalForm(b)

/**
 * A string that is equal for two values exactly when they're semantically equal. Used as the oracle's term value.
 */
fun canonicalForm(value: OValue): String = StringBuilder().also { appendCanonical(it, value) }.toString()

private fun appendCanonical(sb: StringBuilder, value: OValue) {
    when (value) {
        ONull -> sb.append('n')
        is OBool -> sb.append(if (value.value) 't' else 'f')
        is ONum -> sb.append('N').append(OracleNumbers.canonical(value.text)).append(';')
        is OStr -> sb.append('S').append(value.value.length).append(':').append(value.value)
        is OArr -> {
            sb.append('[').append(value.items.size).append(':')
            value.items.forEach { appendCanonical(sb, it) }
            sb.append(']')
        }
        is OObj -> {
            sb.append('{').append(value.members.size).append(':')
            value.members.sortedWith { x, y -> compareCodePoints(x.first, y.first) }.forEach { (k, v) ->
                sb.append(k.length).append(':').append(k)
                appendCanonical(sb, v)
            }
            sb.append('}')
        }
    }
}

internal fun compareCodePoints(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a.codePointAt(i)
        val cb = b.codePointAt(j)
        if (ca != cb) return ca.compareTo(cb)
        i += Character.charCount(ca)
        j += Character.charCount(cb)
    }
    return (a.length - i).compareTo(b.length - j)
}
