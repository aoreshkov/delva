package app.oreshkov.delva.testkit.property

import kotlin.random.Random

/**
 * Runs [property] against values from [gen].
 *
 * Case `i` uses its own seed derived from the run seed and a size that ramps up to [maxSize]. On failure:
 * 1. the same case seed is retried with smaller sizes, keeping the smallest size that still fails;
 * 2. [shrinker] then simplifies that value step by step while the property keeps failing (at most [maxShrinks]
 *    property evaluations).
 * The report shows the shrunk value, and the seed and size that reproduce the unshrunk case.
 *
 * System properties:
 * - `delva.seed`: run seed (random by default). Together with `delva.size`, runs exactly one case with that seed and size.
 * - `delva.testProfile=long`: multiplies [iterations] by 10.
 */
fun <T> forAll(
    name: String,
    gen: Gen<T>,
    iterations: Int = 200,
    maxSize: Int = 100,
    shrinker: Shrinker<T> = Shrinkers.none(),
    maxShrinks: Int = 2000,
    property: (T) -> Unit,
) {
    val seedProperty = System.getProperty("delva.seed")?.toLong()
    val sizeProperty = System.getProperty("delva.size")?.toInt()
    if (seedProperty != null && sizeProperty != null) {
        val failed = runCase(name, gen, seedProperty, sizeProperty, property) ?: return
        throw report(name, failed, shrink(name, failed, shrinker, maxShrinks, property), sizeShrunkFrom = null)
    }

    val runSeed = seedProperty ?: Random.nextLong()
    val cases = if (System.getProperty("delva.testProfile") == "long") iterations * 10 else iterations
    for (i in 0 until cases) {
        val caseSeed = mix(runSeed + i)
        val size = if (cases == 1) maxSize else (i.toLong() * maxSize / (cases - 1)).toInt()
        val failed = runCase(name, gen, caseSeed, size, property) ?: continue
        val smallest = shrinkBySize(name, gen, failed, property)
        throw report(name, smallest, shrink(name, smallest, shrinker, maxShrinks, property), sizeShrunkFrom = failed.size)
    }
}

class Failure<T> internal constructor(val seed: Long, val size: Int, val value: T, val error: Throwable)

private class Shrunk<T>(val value: T, val error: Throwable, val steps: Int)

private fun <T> runCase(name: String, gen: Gen<T>, seed: Long, size: Int, property: (T) -> Unit): Failure<T>? {
    val value = try {
        gen.generate(Random(seed), size)
    } catch (e: Throwable) {
        throw AssertionError("Property '$name': generator failed (seed=$seed, size=$size)", e)
    }
    return check(name, seed, size, value, property)?.let { Failure(seed, size, value, it) }
}

/**
 * A [VirtualMachineError] isn't shrunk, since the JVM may be unable to go on, but it carries the seed and size of
 * the case that threw it (or, while shrinking, of the case being shrunk) so the failure can be reproduced.
 */
private fun <T> check(name: String, seed: Long, size: Int, value: T, property: (T) -> Unit): Throwable? = try {
    property(value)
    null
} catch (e: VirtualMachineError) {
    e.addSuppressed(
        AssertionError(
            "Property '$name' threw ${e.javaClass.name} (seed=$seed, size=$size)\n" +
                "  reproduce with: -Ddelva.seed=$seed -Ddelva.size=$size",
        ),
    )
    throw e
} catch (e: Throwable) {
    e
}

/** Same seed, smaller sizes: keeps the smallest size that still fails. */
private fun <T> shrinkBySize(name: String, gen: Gen<T>, failed: Failure<T>, property: (T) -> Unit): Failure<T> {
    var best = failed
    var size = failed.size / 2
    while (size >= 0) {
        runCase(name, gen, failed.seed, size, property)?.let { best = it }
        if (size == 0) break
        size /= 2
    }
    return best
}

private fun <T> shrink(
    name: String,
    failed: Failure<T>,
    shrinker: Shrinker<T>,
    maxShrinks: Int,
    property: (T) -> Unit,
): Shrunk<T> {
    var value = failed.value
    var error = failed.error
    var steps = 0
    var evaluations = 0
    improve@ while (evaluations < maxShrinks) {
        for (candidate in shrinker.candidates(value)) {
            if (++evaluations > maxShrinks) break@improve
            val candidateError = check(name, failed.seed, failed.size, candidate, property) ?: continue
            value = candidate
            error = candidateError
            steps++
            continue@improve
        }
        break
    }
    return Shrunk(value, error, steps)
}

private fun report(name: String, failure: Failure<*>, shrunk: Shrunk<*>, sizeShrunkFrom: Int?): AssertionError {
    val message = buildString {
        append("Property '").append(name).append("' failed (seed=").append(failure.seed)
        append(", size=").append(failure.size)
        if (sizeShrunkFrom != null && sizeShrunkFrom != failure.size) append(", shrunk from size ").append(sizeShrunkFrom)
        append(")")
        if (shrunk.steps > 0) {
            append("\n  value after ").append(shrunk.steps).append(" shrink steps: ").append(shrunk.value.describe())
            append("\n  original value: ").append(failure.value.describe())
        } else {
            append("\n  value: ").append(failure.value.describe())
        }
        append("\n  error: ").append(shrunk.error)
        append("\n  reproduce with: -Ddelva.seed=").append(failure.seed).append(" -Ddelva.size=").append(failure.size)
    }
    return AssertionError(message, shrunk.error)
}

private fun Any?.describe(): String {
    val text = when (this) {
        is ByteArray -> "ByteArray(${size})[" + joinToString(" ") { "%02x".format(it) } + "]"
        is LongArray -> contentToString()
        is IntArray -> contentToString()
        else -> toString()
    }
    return if (text.length > 2000) text.take(2000) + "… (${text.length} chars)" else text
}

/** SplitMix64 finalizer: well-distributed case seeds from consecutive integers. */
private fun mix(value: Long): Long {
    var z = value + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}
