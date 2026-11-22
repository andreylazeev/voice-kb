package ly.anoma.hex

import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import ly.anoma.hex.keyboard.IncognitoEmojiContext
import ly.anoma.hex.keyboard.KeyboardEngine
import ly.anoma.hex.keyboard.KeyboardPage
import ly.anoma.hex.keyboard.KeyboardSurface
import ly.anoma.hex.keyboard.KeyboardTextTarget
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.LearningMode
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences

internal object KeyboardPrivacyChecks {
    fun verify(context: Context) {
        val names = listOf("typing", "learned-words", "androidx.emoji2.emojipicker.preferences")
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("privacy-check-$name", mode)
        }
        try {
            checkKeyboard(isolated)
            val emojiPrefs = isolated.getSharedPreferences(names.last(), Context.MODE_PRIVATE)
            emojiPrefs.edit().putString("pref_key_recent_emoji", "🙂").putString("pref_key_sticky_variant", "👍=👍🏽").commit()
            val privateEmoji = IncognitoEmojiContext(isolated).getSharedPreferences(names.last(), Context.MODE_PRIVATE)
            check(privateEmoji.getString("pref_key_recent_emoji", null) == null)
            check(privateEmoji.getString("pref_key_sticky_variant", null) == null)
            privateEmoji.edit().putString("pref_key_recent_emoji", "😎").apply()
            privateEmoji.edit().putString("pref_key_sticky_variant", "👍=👍🏻").commit()
            check(emojiPrefs.getString("pref_key_recent_emoji", null) == "🙂")
            check(emojiPrefs.getString("pref_key_sticky_variant", null) == "👍=👍🏽")
        } finally {
            names.forEach { context.deleteSharedPreferences("privacy-check-$it") }
        }
    }

    private fun checkKeyboard(context: Context) {
        val feedback = TypingPreferences(context)
        feedback.learn("en", LearningMode.Tap, "zorbex", "hello", explicit = true)
        feedback.learn("en", LearningMode.Swipe, "zorbix", null, explicit = true)
        feedback.autocorrect = false
        val initial = feedback.learningReports()
        val target = Editor()
        val model = TypingModel.asset(context.assets, "en")
        var personalReads = 0
        val engine = KeyboardEngine(target, feedback, { Spoken.English }, { SpatialDecoder(model) }, readPersonal = {
            personalReads++
            PersonalWords(it, emptyList(), mapOf("qa123" to "test expansion"))
        })
        var shownPage: KeyboardPage? = null
        var suggestions = emptyList<Suggestion>()
        engine.surface = object : KeyboardSurface {
            override fun showPage(page: KeyboardPage) { shownPage = page }
            override fun suggestions(slots: List<Suggestion>) { suggestions = slots }
            override fun undoDictation(shown: Boolean) = Unit
        }
        fun start(flags: Int = 0, text: String = "", type: Int = InputType.TYPE_CLASS_TEXT) {
            target.text = text
            engine.startInput(EditorInfo().apply {
                inputType = type
                imeOptions = flags
                initialSelStart = text.length
                initialSelEnd = text.length
            })
            engine.configureTyping()
        }
        start()
        check(shownPage?.incognito == false && personalReads == 1)
        check(engine.learnedWords("en", LearningMode.Tap).knows("zorbex"))
        check(engine.learnedWords("en", LearningMode.Swipe).knows("zorbix"))

        val privateFlag = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        start(privateFlag, "qa123")
        check(checkNotNull(shownPage).incognito && engine.canSwipe && !engine.personalContextEnabled)
        check(LearningMode.entries.all { engine.learnedWords("en", it).words.isEmpty() })
        engine.personalWordsChanged()
        check(personalReads == 1)
        engine.space()
        check(target.text == "qa123 ") { "Applied a personal shortcut in incognito" }
        val grid = KeyGrid(mapOf('z' to KeyPoint(0.0, 0.0)), 1.0, 1.0)
        for (letter in "zor") engine.letter(letter.toString(), 0.0, 0.0, grid, explicit = true)
        check(suggestions.none { it.text in setOf("zorbex", "zorbix") })
        for (letter in "nix") engine.letter(letter.toString(), 0.0, 0.0, grid, explicit = true)
        engine.space()
        engine.insertSwipe(listOf("solnix", "rindlex"), engine.swipeRevision(), engine.swipeAnchor())
        check(target.text.endsWith("solnix "))
        engine.pickSuggestion("rindlex")
        check(target.text.endsWith("rindlex "))
        engine.insertSwipe(listOf("ornix"), engine.swipeRevision(), engine.swipeAnchor())
        engine.leave()
        check(feedback.learningReports() == initial) { "Incognito changed the learned words" }

        // EditorInfo captured from the empty Chrome incognito omnibox and Instagram composer on Fold8.
        val editors = listOf(Triple("Chrome incognito", 0x280011, 0x13000002),
            Triple("Instagram composer", 0x2ac001, 0x02000001))
        val unavailable = buildList {
            for ((name, type, flags) in editors) {
                start(flags, type = type)
                engine.insertSwipe(listOf("hello"), engine.swipeRevision(), engine.swipeAnchor())
                if (target.text != "hello ") add(name)
                engine.leave()
            }
        }
        check(unavailable.isEmpty()) { "Swipe did not insert a word in $unavailable" }
        check(feedback.learningReports() == initial)
        start(privateFlag, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        check(!engine.canSwipe)

        start(text = "qa123")
        engine.space()
        check(!checkNotNull(shownPage).incognito && personalReads == 2 && engine.personalContextEnabled)
        check(target.text == "test expansion ") { "Personal shortcuts did not return after incognito" }
        for (letter in "zivnex") engine.letter(letter.toString(), 0.0, 0.0, grid, explicit = true)
        engine.space()
        check("zivnex" in feedback.learned("en", LearningMode.Tap).words)
    }

    private class Editor : KeyboardTextTarget {
        var text = ""
        override val connected = true
        override fun before(length: Int) = text.takeLast(length)
        override fun after(length: Int) = ""
        override fun selected() = ""
        override fun capsMode(mask: Int) = 0
        override fun commit(text: String): Boolean { this.text += text; return true }
        override fun deleteBefore(length: Int): Boolean { text = text.dropLast(length); return true }
        override fun deleteCodePointsBefore(count: Int) = deleteBefore(count)
        override fun beginBatch() = Unit
        override fun endBatch() = Unit
        override fun setSelection(start: Int, end: Int) = false
        override fun performEditorAction(id: Int) = false
        override fun defaultEditorAction() = false
        override fun sendKey(code: Int) = Unit
    }
}
