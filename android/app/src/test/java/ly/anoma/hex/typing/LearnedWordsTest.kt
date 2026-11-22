package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedWordsTest {
    @Test fun cyrillicTypingUsesItsOwnModelAndLearnedWords() {
        for ((language, previous, next, custom) in listOf(
            listOf("ru", "надо", "идти", "зорбекс"), listOf("uk", "дуже", "добре", "зорбікс"),
        )) {
            val model = TypingModel.file(File("src/main/assets/typing/$language.hextm"), language)
            assertTrue(model.index(next) >= 0)
            assertTrue(model.hasPair(previous, next))
            val stats = LearnedWords.Empty.record(custom, previous, null, explicit = true)
            val typing = TypingWord(SpatialDecoder(model), spatial = false, learned = { stats }, contextEnabled = { true })
            assertEquals(custom, typing.predictions("$previous ").firstOrNull())
            typing.explicit(custom.take(3))
            assertTrue(typing.strip(autocorrect = false).any { it.text == custom })
        }
    }

    @Test fun countsChoicesAndContextAcrossReloads() {
        var words = LearnedWords.Empty.record("Зорбекс", "Я", "зверь", explicit = true)
        assertTrue(words.knows("зорбекс"))
        assertEquals(listOf("зорбекс"), words.completions("зор"))
        assertEquals(listOf("зорбекс"), words.following("я"))
        assertEquals(mapOf("зорбекс" to 1), words.preferencesAgainst("зверь"))
        assertFalse(words.encode().contains("зверь"))
        words = LearnedWords.decode(words.encode())
        assertEquals(1, words.contextCount)
        assertEquals(1, words.correctionCount)
        assertEquals(mapOf("зорбекс" to 1), words.preferencesAgainst("зверь"))
        assertEquals(listOf("зорбекс"), words.following("Я"))
    }

    @Test fun wordLearnedBySwipeCompletesTapInput() {
        val model = TypingModel.file(File("src/main/assets/typing/ru.hextm"), "ru")
        val stats = LearnedWords.Empty.record("зорбекс", null, null, explicit = true)
        val typing = TypingWord(SpatialDecoder(model), spatial = false, learnedFromSwipe = { stats })
        typing.explicit("зор")
        assertTrue(typing.strip(autocorrect = false).any { it.text == "зорбекс" })
    }

    @Test fun russianTypingModelIncludesConversationAndModernNames() {
        val model = TypingModel.file(File("src/main/assets/typing/ru.hextm"), "ru")
        for (word in listOf("клаве", "свайпом", "яндекс", "яндексе")) assertTrue(word, model.index(word) >= 0)
        val typing = TypingWord(SpatialDecoder(model), spatial = false)
        typing.explicit("яндек")
        assertTrue(typing.strip(autocorrect = false).any { it.text == "Яндекс" })
    }

    @Test fun rejectsPhrasesAndDoesNotTreatOneUnconfirmedUseAsVocabulary() {
        var words = LearnedWords.Empty
        for (text in listOf("one two", "mail@example", "secret123", "a\tb")) {
            words = words.record(text, "test", null, explicit = true)
        }
        assertTrue(words.words.isEmpty())
        words = words.record("zorbex", "a", null, explicit = false)
        assertFalse(words.knows("zorbex"))
        assertTrue(words.following("a").isEmpty())
        repeat(2) { words = words.record("zorbex", "a", null, explicit = false) }
        assertTrue(words.knows("zorbex"))
        assertEquals(listOf("zorbex"), words.following("a"))
    }
}
