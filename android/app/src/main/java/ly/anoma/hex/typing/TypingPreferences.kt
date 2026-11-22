package ly.anoma.hex.typing

import android.content.Context
import java.security.MessageDigest
import ly.anoma.hex.speech.Spoken

internal class TypingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("typing", Context.MODE_PRIVATE)
    private val learning = LearningStore(context)
    var layout: Spoken?
        get() = Spoken.matching(prefs.getString("layout", null))
        set(value) { prefs.edit().putString("layout", value?.layoutCode).apply() }
    /** Settings › Keyboard › Auto-capitalisation: shift comes on where the field asks for a capital. On by default. */
    var autoCapitalize: Boolean
        get() = prefs.getBoolean("autoCapitalize", true)
        set(value) { prefs.edit().putBoolean("autoCapitalize", value).apply() }
    var autocorrect: Boolean
        get() = prefs.getBoolean("autocorrect", true)
        set(value) { prefs.edit().putBoolean("autocorrect", value).apply() }
    var suggestSwearWords: Boolean
        get() = prefs.getBoolean(SUGGEST_SWEAR_WORDS, false)
        set(value) { prefs.edit().putBoolean(SUGGEST_SWEAR_WORDS, value).apply() }
    var spatial: Boolean
        get() = prefs.getBoolean("spatial", true)
        set(value) { prefs.edit().putBoolean("spatial", value).apply() }
    var popups: Boolean
        get() = prefs.getBoolean("popups", true)
        set(value) { prefs.edit().putBoolean("popups", value).apply() }
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) { prefs.edit().putBoolean("haptics", value).apply() }
    var learnTyping: Boolean
        get() = prefs.getBoolean("learnTyping", true)
        set(value) { prefs.edit().putBoolean("learnTyping", value).apply() }
    var learnSwipe: Boolean
        get() = prefs.getBoolean("learnSwipe", true)
        set(value) { prefs.edit().putBoolean("learnSwipe", value).apply() }
    var learnContext: Boolean
        get() = prefs.getBoolean("learnContext", true)
        set(value) { prefs.edit().putBoolean("learnContext", value).apply() }

    fun learned(language: String, mode: LearningMode): LearnedWords =
        if (learningEnabled(mode)) learning.snapshot(language, mode) else LearnedWords.Empty

    fun learn(language: String, mode: LearningMode, word: String, previous: String?,
              explicit: Boolean = false, replaced: String? = null) {
        if (learningEnabled(mode)) learning.record(language, mode, word, previous.takeIf { learnContext }, replaced, explicit)
    }

    private fun learningEnabled(mode: LearningMode) = when (mode) {
        LearningMode.Tap -> learnTyping
        LearningMode.Swipe -> learnSwipe
    }

    fun clearLearning() {
        learning.clear()
        val editor = prefs.edit()
        for (key in prefs.all.keys) if (key.length == 44 && key.endsWith("=")) editor.remove(key)
        editor.apply()
    }

    // Remember only explicit rejection of this exact pair in this language; never save typed text.
    private fun pair(language: String, original: String, replacement: String): String {
        val bytes = (language + "\u0000" + original.lowercase() + "\u0000" + replacement.lowercase()).toByteArray()
        return android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes), android.util.Base64.NO_WRAP)
    }
    fun blocked(language: String, original: String, replacement: String) =
        learnTyping && prefs.getInt(pair(language, original, replacement), 0) >= 2
    fun undo(language: String, original: String, replacement: String) {
        if (!learnTyping) return
        val key = pair(language, original, replacement)
        prefs.edit().putInt(key, (prefs.getInt(key, 0) + 1).coerceAtMost(2)).apply()
    }
    companion object { const val SUGGEST_SWEAR_WORDS = "suggestSwearWords" }
}
