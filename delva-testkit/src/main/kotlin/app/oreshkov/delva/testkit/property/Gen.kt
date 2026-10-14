package app.oreshkov.delva.testkit.property

import kotlin.random.Random

/**
 * A sized, seeded generator. `size` grows during a property run and shrinks while reducing a failure,
 * so generators should produce "smaller" values for smaller sizes.
 */
fun interface Gen<out T> {
    fun generate(random: Random, size: Int): T
}

fun <T, R> Gen<T>.map(transform: (T) -> R): Gen<R> = Gen { random, size -> transform(generate(random, size)) }

fun <T, R> Gen<T>.flatMap(transform: (T) -> Gen<R>): Gen<R> =
    Gen { random, size -> transform(generate(random, size)).generate(random, size) }

fun <T> Gen<T>.filter(maxAttempts: Int = 1000, predicate: (T) -> Boolean): Gen<T> = Gen { random, size ->
    repeat(maxAttempts) {
        val value = generate(random, size)
        if (predicate(value)) return@Gen value
    }
    error("filter: no value accepted after $maxAttempts attempts")
}

object Gens {
    fun <T> constant(value: T): Gen<T> = Gen { _, _ -> value }

    fun boolean(): Gen<Boolean> = Gen { random, _ -> random.nextBoolean() }

    /** Uniform in [range]; the size doesn't bound the value. */
    fun int(range: IntRange): Gen<Int> {
        require(!range.isEmpty())
        return Gen { random, _ -> random.nextLong(range.first.toLong(), range.last.toLong() + 1).toInt() }
    }

    /** Uniform over all 64-bit values. */
    fun long(): Gen<Long> = Gen { random, _ -> random.nextLong() }

    fun long(range: LongRange): Gen<Long> = Gen { random, _ ->
        if (range.last == Long.MAX_VALUE) {
            if (range.first == Long.MIN_VALUE) random.nextLong() else random.nextLong(range.first - 1, range.last) + 1
        } else {
            random.nextLong(range.first, range.last + 1)
        }
    }

    /**
     * Longs biased to interesting values: small magnitudes (scaled by size), powers of two and ten with neighbours,
     * and the extremes.
     */
    fun edgyLong(): Gen<Long> = Gen { random, size ->
        when (random.nextInt(4)) {
            0 -> random.nextLong(-size.toLong() - 1, size.toLong() + 2)
            1 -> {
                val base = if (random.nextBoolean()) 1L shl random.nextInt(63) else POWERS_OF_TEN[random.nextInt(POWERS_OF_TEN.size)]
                val value = base + random.nextInt(-1, 2)
                if (random.nextBoolean()) value else -value
            }
            2 -> EXTREMES[random.nextInt(EXTREMES.size)]
            else -> random.nextLong()
        }
    }

    /** Length in `0..min(size, maxLength)`. */
    fun bytes(maxLength: Int = Int.MAX_VALUE): Gen<ByteArray> = Gen { random, size ->
        random.nextBytes(random.nextInt(0, minOf(size, maxLength) + 1))
    }

    fun <T> list(element: Gen<T>, maxLength: Int = Int.MAX_VALUE): Gen<List<T>> = Gen { random, size ->
        List(random.nextInt(0, minOf(size, maxLength) + 1)) { element.generate(random, size) }
    }

    fun <T> element(values: List<T>): Gen<T> {
        require(values.isNotEmpty())
        return Gen { random, _ -> values[random.nextInt(values.size)] }
    }

    fun <T> oneOf(vararg gens: Gen<T>): Gen<T> {
        require(gens.isNotEmpty())
        return Gen { random, size -> gens[random.nextInt(gens.size)].generate(random, size) }
    }

    fun <T> frequency(vararg weighted: Pair<Int, Gen<T>>): Gen<T> {
        require(weighted.isNotEmpty() && weighted.all { it.first > 0 })
        val total = weighted.sumOf { it.first }
        return Gen { random, size ->
            var pick = random.nextInt(total)
            for ((weight, gen) in weighted) {
                if (pick < weight) return@Gen gen.generate(random, size)
                pick -= weight
            }
            error("unreachable")
        }
    }

    fun <A, B> pair(first: Gen<A>, second: Gen<B>): Gen<Pair<A, B>> =
        Gen { random, size -> first.generate(random, size) to second.generate(random, size) }

    private val POWERS_OF_TEN = LongArray(19) { exp -> (0 until exp).fold(1L) { acc, _ -> acc * 10 } }
    private val EXTREMES = longArrayOf(0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong())
}
