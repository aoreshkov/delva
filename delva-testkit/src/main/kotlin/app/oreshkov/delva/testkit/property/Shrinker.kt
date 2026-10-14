package app.oreshkov.delva.testkit.property

/**
 * Proposes simpler variants of a failing value, simplest first. [forAll] keeps the first candidate that still fails
 * and repeats until no candidate fails.
 */
fun interface Shrinker<T> {
    fun candidates(value: T): Sequence<T>
}

object Shrinkers {
    fun <T> none(): Shrinker<T> = Shrinker { emptySequence() }

    /** Integers toward zero: 0, then halving distances. */
    fun long(): Shrinker<Long> = Shrinker { value ->
        sequence {
            if (value == 0L) return@sequence
            yield(0L)
            var delta = value / 2
            while (delta != 0L) {
                yield(value - delta)
                delta /= 2
            }
        }
    }

    fun string(): Shrinker<String> = Shrinker { value ->
        sequence {
            if (value.isEmpty()) return@sequence
            yield("")
            if (value.length > 1) {
                yield(value.substring(0, value.length / 2))
                yield(value.substring(value.length / 2))
            }
            yield(value.dropLast(1))
            yield(value.drop(1))
        }
    }

    /**
     * Operation sequences and other lists: the empty list, then drop halves, then quarters … then single elements,
     * then shrink elements one at a time with [element].
     */
    fun <T> list(element: Shrinker<T> = none()): Shrinker<List<T>> = Shrinker { value ->
        sequence {
            if (value.isEmpty()) return@sequence
            yield(emptyList())
            var chunk = value.size / 2
            while (chunk >= 1) {
                var start = 0
                while (start < value.size) {
                    yield(value.subList(0, start) + value.subList(minOf(start + chunk, value.size), value.size))
                    start += chunk
                }
                chunk /= 2
            }
            for (i in value.indices) {
                for (smaller in element.candidates(value[i])) {
                    yield(value.toMutableList().also { it[i] = smaller })
                }
            }
        }
    }

    fun <A, B> pair(first: Shrinker<A>, second: Shrinker<B>): Shrinker<Pair<A, B>> = Shrinker { (a, b) ->
        first.candidates(a).map { it to b } + second.candidates(b).map { a to it }
    }
}
