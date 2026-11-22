package ly.anoma.hex.speech

import android.content.Context
import android.content.res.Resources

internal enum class Style { Polished, Casual, Notes, Verbatim }
/** A keyboard layout's language: its keys, accents and typing model. Dictation languages are
 * [DictationLanguage]; a language can be dictated without a layout of its own. */
internal enum class Spoken(val code: String, val label: String) {
    English("en", "English"), Spanish("es", "Español"), German("de", "Deutsch"), Norwegian("no", "Norsk"),
    French("fr", "Français"), Italian("it", "Italiano"), Russian("ru", "Русский"), Ukrainian("uk", "Українська");

    // Whisper calls Bokmål "no"; the keyboard and its model use BCP 47 "nb".
    val layoutCode get() = if (this == Norwegian) "nb" else code
    companion object {
        fun matching(code: String?): Spoken? = when (code) {
            "no", "nb", "nn" -> Norwegian
            else -> entries.firstOrNull { it.code == code }
        }
    }
}

/** What Whisper is asked to transcribe ([code], Whisper's name for it) and the layout the
 * keyboard follows while it is the dictation language, when there is one. */
internal enum class DictationLanguage(val code: String, val label: String, val layout: Spoken?) {
    English("en", "English", Spoken.English), Spanish("es", "Español", Spoken.Spanish),
    German("de", "Deutsch", Spoken.German), Norwegian("no", "Norsk", Spoken.Norwegian),
    French("fr", "Français", Spoken.French), Italian("it", "Italiano", Spoken.Italian), Dutch("nl", "Nederlands", null),
    Russian("ru", "Русский", Spoken.Russian), Ukrainian("uk", "Українська", Spoken.Ukrainian);

    companion object {
        val supported = listOf(Russian, English, Ukrainian)
        fun matching(code: String?): DictationLanguage? = when (code) {
            "no", "nb", "nn" -> Norwegian
            else -> entries.firstOrNull { it.code == code }
        }
        /** Automatic: the keyboard's layout when one is chosen there, as iPhone dictation follows the
         * keyboard's language, else the first of the phone's languages Hex dictates, else English. */
        fun automatic(keyboard: Spoken?, system: List<String>): DictationLanguage =
            entries.firstOrNull { keyboard != null && it.layout == keyboard }
                ?: system.firstNotNullOfOrNull(::matching) ?: English
    }
}

internal data class SpeechOptions(val style: Style, val spoken: DictationLanguage, val clean: Boolean, val translate: Boolean,
                                   val lowercase: Boolean = false, val endWithPeriod: Boolean = true,
                                   val formatLists: Boolean = false, val spokenPunctuation: Boolean = false,
                                   val vocabulary: List<String> = emptyList(),
                                   val cleanupModel: CleanupModel? = null, val incognito: Boolean = false)
internal class SpeechPreferences(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
    var style: Style
        get() = if (prefs.getString("style", null) == Style.Verbatim.name || !prefs.getBoolean("clean", true))
            Style.Verbatim else Style.Polished
        set(value) { prefs.edit().putString("style", value.name).remove("clean").apply() }
    // Null means Automatic; never save the resolved locale over that choice.
    var language: DictationLanguage?
        get() = DictationLanguage.matching(prefs.getString("spoken", null))?.takeIf { it in DictationLanguage.supported }
        set(value) { prefs.edit().putString("spoken", value?.code).apply() }
    // Keyboard › Keyboard layout when chosen there (null while the layout follows dictation).
    private val typing = context.getSharedPreferences("typing", Context.MODE_PRIVATE)
    val spoken: DictationLanguage
        get() = language ?: Resources.getSystem().configuration.locales.let { locales ->
            DictationLanguage.automatic(Spoken.matching(typing.getString("layout", null))?.takeIf { it == Spoken.Russian || it == Spoken.English || it == Spoken.Ukrainian },
                (0 until locales.size()).map { locales[it].language }.filter { code -> DictationLanguage.supported.any { it.code == code } })
        }
    var translation: Boolean
        get() = prefs.getBoolean("translation", false)
        set(value) { prefs.edit().putBoolean("translation", value).apply() }
    // Settings › Dictation › Lowercase: everything dictation writes, after the style.
    var lowercase: Boolean
        get() = prefs.getBoolean("lowercase", false)
        set(value) { prefs.edit().putBoolean("lowercase", value).apply() }
    // Off, no line ends with a full stop; "?" and "!" stay.
    var endWithPeriod: Boolean
        get() = prefs.getBoolean("endWithPeriod", true)
        set(value) { prefs.edit().putBoolean("endWithPeriod", value).apply() }
    // Say "first, second" or "bullet" for a list (English and Spanish).
    var formatLists: Boolean
        get() = prefs.getBoolean("formatLists", false)
        set(value) { prefs.edit().putBoolean("formatLists", value).apply() }
    // Say "comma" or "question mark" for the mark.
    var spokenPunctuation: Boolean
        get() = prefs.getBoolean("spokenPunctuation", false)
        set(value) { prefs.edit().putBoolean("spokenPunctuation", value).apply() }
    // Custom vocabulary: names and terms spelled your way, kept even while the switch is off.
    var customVocabulary: Boolean
        get() = prefs.getBoolean("customVocabulary", false)
        set(value) { prefs.edit().putBoolean("customVocabulary", value).apply() }
    var vocabularyTerms: List<String>
        get() = prefs.getString("vocabularyTerms", null)?.split('\n').orEmpty().filter(String::isNotEmpty)
        set(value) { prefs.edit().putString("vocabularyTerms", VocabularySpelling.sanitize(value).joinToString("\n")).apply() }
    var cleanupModel: CleanupModel?
        get() = prefs.getString("cleanupModel", null)?.let { id -> CleanupModel.entries.firstOrNull { it.name == id } }
        set(value) { prefs.edit().putString("cleanupModel", value?.name).apply() }
    fun options(nextEnglish: Boolean?, incognito: Boolean = false): SpeechOptions {
        val selected = style
        return SpeechOptions(selected, spoken, selected != Style.Verbatim, false, lowercase, endWithPeriod,
            formatLists, spokenPunctuation, if (!incognito && customVocabulary) vocabularyTerms else emptyList(),
            cleanupModel?.takeIf { selected != Style.Verbatim && CleanupModels.ready(app, it) }, incognito)
    }
}
