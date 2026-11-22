package ly.anoma.hex.typing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FutoSwipeDecoderTest {
    @Test fun selectedAlternativeCanOvertakeTheOriginalGuess() {
        val decoder = FutoSwipeDecoder("abc", mapOf("ab" to 160, "ac" to 155), emptyMap())
        val emissions = emissions(listOf(setOf(0), setOf(1, 2)))
        assertEquals("ab", decoder.suggestions(emissions, null, LearnedWords.Empty, LearnedWords.Empty, false).first())

        val learned = LearnedWords.Empty.record("ac", null, "ab", explicit = true)
        assertEquals("ac", decoder.suggestions(emissions, null, learned, LearnedWords.Empty, false).first())
    }

    @Test fun canDecodeAWordLearnedDuringTypingWithoutChangingTheBaseVocabulary() {
        val decoder = FutoSwipeDecoder("abcd", mapOf("ab" to 160, "ac" to 155), emptyMap())
        val emissions = emissions(listOf(setOf(2), setOf(3)))
        val typed = LearnedWords.Empty.record("cd", null, null, explicit = true)
        assertEquals("cd", decoder.suggestions(emissions, null, LearnedWords.Empty, typed, false).first())
        assertFalse("cd" in decoder.suggestions(emissions, null, LearnedWords.Empty, LearnedWords.Empty, false))
    }

    @Test fun keepsSpellingsThatShareAGestureAvailableToLearning() {
        val decoder = FutoSwipeDecoder("все", mapOf("все" to 210, "всё" to 203), emptyMap())
        val emissions = emissions(listOf(setOf(0), setOf(1), setOf(2)))
        val learned = LearnedWords.Empty.record("всё", null, "все", explicit = true)
        assertEquals("всё", decoder.suggestions(emissions, null, learned, LearnedWords.Empty, false).first())
    }

    @Test fun matchingNewPersonalWordIsConsideredEvenWhenManyOtherWordsHaveHigherUsage() {
        val decoder = FutoSwipeDecoder("abcdefghijklmnopqrstuvwxyz", mapOf("ab" to 190), emptyMap())
        val frequent = ('a'..'z').flatMap { first -> ('a'..'c').map { second -> "a$first${second}a" } }
        var typed = LearnedWords.Empty.record("cd", null, null, explicit = true)
        for (word in frequent) repeat(4) { typed = typed.record(word, null, null, explicit = true) }
        val scores = emissions(listOf(setOf(2), setOf(3), setOf(3)))
        assertEquals("cd", decoder.suggestions(scores, null, LearnedWords.Empty, typed, false).first())
    }

    private fun emissions(keys: List<Set<Int>>): FloatArray =
        FloatArray(FutoLayout.STEPS * FutoLayout.CLASSES) { index ->
            val step = index / FutoLayout.CLASSES
            val key = index % FutoLayout.CLASSES
            val best = keys.getOrNull(step / 10) ?: setOf(FutoLayout.KEYS)
            if (key in best) -.05f else -9f
        }
}
