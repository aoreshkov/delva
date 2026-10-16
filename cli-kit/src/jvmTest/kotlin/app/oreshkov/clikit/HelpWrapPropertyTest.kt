package app.oreshkov.clikit

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wrapping over random words and widths. Each case has its own seed, printed when it fails; `-Ddelva.seed=<seed>`
 * runs just that case again.
 */
class HelpWrapPropertyTest {

    private val styled = Styler(enabled = true)

    @Test
    fun `wrapped lines fit the width unless they hold one word, and keep the words in order`() = forEachCase { random ->
        val width = random.nextInt(1, 121)
        val words = List(random.nextInt(0, 40)) { word(random) }
        val lines = HelpText.wrap(text(random, words), width)
        lines.forEach { line ->
            assertTrue(Styler.displayWidth(line) <= width || ' ' !in line, "'$line' is wider than $width")
            assertEquals(line.trim(), line, "'$line' has a space at an end")
        }
        assertEquals(words, lines.flatMap { line -> line.split(' ').filter { it.isNotEmpty() } })
    }

    @Test
    fun `a table's descriptions fit beside or below their entries, aligned, with every word in order`() = forEachCase { random ->
        val width = random.nextInt(20, 121)
        val options = List(random.nextInt(1, 6)) { i ->
            val name = "--" + letters(random, 1, 45) + i
            Option.Flag(name, help = List(random.nextInt(0, 25)) { word(random) }.joinToString(" "))
        }
        val page = HelpRenderer(Styler.Plain, null, width).render(listOf(CommandSpec("demo", options = options)))
        val table = page.substringAfter("Options:\n").lines().filter { it.isNotEmpty() }

        val described = mutableMapOf<String, MutableList<String>>()
        val margins = mutableSetOf<Int>()
        var current: String? = null
        for (line in table) {
            val text = line.trim()
            val name = options.firstOrNull { text == it.long || text.startsWith(it.long + " ") }?.long
            if (name != null) current = name else margins += line.length - line.trimStart().length
            val description = if (name != null) text.removePrefix(name).trim() else text
            assertTrue(Styler.displayWidth(line) <= width || ' ' !in description, "'$line' is wider than $width")
            described.getOrPut(current!!) { mutableListOf() } += description.split(' ').filter { it.isNotEmpty() }
        }
        assertTrue(margins.size <= 1, "continuation lines start at columns $margins")
        options.forEach { assertEquals(it.help.split(' ').filter(String::isNotEmpty), described[it.long].orEmpty()) }
    }

    /** [words] joined by what a help text holds between them: spaces, tabs, line breaks and paragraph breaks. */
    private fun text(random: Random, words: List<String>): String = buildString {
        words.forEach { word ->
            append(SEPARATORS[random.nextInt(SEPARATORS.size)])
            append(word)
        }
    }

    /** Mostly short words, now and then one too long for any line, and some styled. */
    private fun word(random: Random): String {
        val word = if (random.nextInt(10) == 0) letters(random, 20, 130) else letters(random, 1, 12)
        return if (random.nextInt(8) == 0) styled.bold(word) else word
    }

    private fun letters(random: Random, from: Int, until: Int): String =
        String(CharArray(random.nextInt(from, until)) { 'a' + random.nextInt(26) })

    private fun forEachCase(cases: Int = 300, property: (Random) -> Unit) {
        val seeds = System.getProperty("delva.seed")?.toLong()?.let(::listOf)
            ?: Random.nextLong().let { run -> List(cases) { run + it } }
        for (seed in seeds) {
            try {
                property(Random(seed))
            } catch (failure: AssertionError) {
                throw AssertionError("case failed (seed=$seed)\n  reproduce with: -Ddelva.seed=$seed", failure)
            }
        }
    }

    private companion object {
        val SEPARATORS = listOf(" ", " ", " ", "  ", "\t", "\n", "\n\n", " \n \n ")
    }
}
