package app.oreshkov.delva.testkit.json

import app.oreshkov.delva.testkit.property.Shrinker
import app.oreshkov.delva.testkit.property.Shrinkers

/**
 * Structural shrinking of JSON trees: replace a container by one of its children or by an empty container, drop
 * members and elements, shrink children, shorten strings and keys, simplify numbers.
 */
object JsonShrinker : Shrinker<OValue> {
    override fun candidates(value: OValue): Sequence<OValue> = when (value) {
        ONull -> emptySequence()
        is OBool -> sequenceOf(ONull)
        is ONum -> numberCandidates(value)
        is OStr -> Shrinkers.string().candidates(value.value).map { OStr(it) }
        is OArr -> sequence {
            yieldAll(value.items.filter { it !is ONull })
            if (value.items.isNotEmpty()) yield(OArr(emptyList()))
            yieldAll(Shrinkers.list(this@JsonShrinker).candidates(value.items).map { OArr(it) })
        }
        is OObj -> sequence {
            yieldAll(value.members.map { it.second }.filter { it !is ONull })
            if (value.members.isNotEmpty()) yield(OObj(emptyList()))
            val memberShrinker = Shrinkers.pair(Shrinkers.string(), this@JsonShrinker)
            for (members in Shrinkers.list(memberShrinker).candidates(value.members)) {
                // Shrinking a key may create a duplicate; skip those.
                if (members.map { it.first }.toSet().size == members.size) yield(OObj(members))
            }
        }
    }

    private fun numberCandidates(value: ONum): Sequence<OValue> = sequence {
        if (value.text != "0") yield(ONum("0"))
        val integerPart = value.text.substringBefore('.').substringBefore('e').substringBefore('E')
        if (integerPart != value.text && integerPart != "-0") yield(ONum(integerPart))
        if (value.text.startsWith("-")) yield(ONum(value.text.removePrefix("-")))
    }
}
