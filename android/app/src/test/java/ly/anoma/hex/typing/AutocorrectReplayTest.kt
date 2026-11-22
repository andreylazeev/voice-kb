package ly.anoma.hex.typing

import java.io.File
import ly.anoma.hex.keyboard.KeyboardLetters
import ly.anoma.hex.keyboard.KeyboardMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Replays dictionary spellings as centre taps through the same word and decoder used by the IME. */
class AutocorrectReplayTest {
    private data class Score(var total: Int = 0, var correct: Int = 0, var kept: Int = 0)

    @Test fun russianWordWithTwoNeighbourKeyTypos() {
        val word = type(decoderAndGrid("ru"), "полкчпется")
        val suggestions = word.strip()
        assertTrue(suggestions.toString(), suggestions.any { it.text == "получается" })
        assertEquals("получается", word.finish()?.replacement)
    }

    @Test fun russianWordWithMissingVowel() {
        val word = type(decoderAndGrid("ru"), "сделть")
        assertEquals("сделать", word.finish()?.replacement)
    }

    @Test fun russianDoubleMistakeCanAffectBothEnds() {
        val word = type(decoderAndGrid("ru"), "аайбен")
        assertEquals("вайбер", word.finish()?.replacement)
    }

    @Test fun russianDoubleCorrectionKeepsPlausibleRareSpellings() {
        val keyboard = decoderAndGrid("ru")
        for (spelling in listOf("наледную", "осиповка")) {
            assertEquals(spelling, type(keyboard, spelling).finish()?.replacement ?: spelling)
        }
    }

    @Test fun russianDoubleCorrectionUsesSpellingEvidenceWhenTapScoresAreClose() {
        val keyboard = decoderAndGrid("ru")
        for ((input, expected) in listOf("агмениеф" to "арменией", "аозбужденг" to "возбуждено")) {
            assertEquals(input, expected, type(keyboard, input).finish()?.replacement)
        }
    }

    @Test fun replay() {
        val source = System.getenv("VOICEKB_AUTOCORRECT_REPLAY")?.let(::File)
        assumeTrue(source?.isFile == true)
        val scores = LinkedHashMap<String, Score>()
        val models = HashMap<String, Pair<SpatialDecoder, KeyGrid>>()
        checkNotNull(source).forEachLine { line ->
            val fields = line.split('\t', limit = 6)
            val (language, corpus, group, input, expected) = fields
            val previous = fields[5]
            val word = type(models.getOrPut(language) { decoderAndGrid(language) }, input, previous)
            val result = word.finish()?.replacement ?: input
            val score = scores.getOrPut("$language/$corpus/$group", ::Score)
            score.total++
            if (result.equals(expected, ignoreCase = true)) score.correct++
            if (result.equals(input, ignoreCase = true)) score.kept++
        }
        scores.forEach { (key, score) -> println("AUTOCORRECT $key ${score.total} ${score.correct} ${score.kept}") }
    }

    private fun decoderAndGrid(language: String): Pair<SpatialDecoder, KeyGrid> {
        val rows = KeyboardLetters.rows(language)
        val columns = KeyboardMetrics.columns(rows)
        val side = (columns - rows[2].length) / 2.0
        val centres = buildMap {
            for ((row, letters) in rows.withIndex()) {
                val width = if (row < 2) 1.0 else (columns - 2.0 * side) / letters.length
                val start = if (row < 2) (columns - letters.length) / 2.0 else side
                letters.forEachIndexed { index, letter ->
                    put(letter, KeyPoint(start + (index + .5) * width, (row + .5) * 1.44))
                }
            }
        }
        val model = TypingModel.file(File("src/main/assets/typing/$language.hextm"), language)
        return SpatialDecoder(model) to KeyGrid(centres, 1.0, 1.44)
    }

    private fun type(keyboard: Pair<SpatialDecoder, KeyGrid>, input: String, previous: String = ""): TypingWord {
        val (decoder, grid) = keyboard
        val word = TypingWord(decoder, spatial = true)
        word.reset(previous.takeIf(String::isNotEmpty)?.plus(" ").orEmpty())
        for (letter in input) {
            val point = grid.centres.getValue(if (letter == 'ё') 'е' else letter)
            word.letter(letter.toString(), point.x, point.y, grid)
        }
        return word
    }
}
