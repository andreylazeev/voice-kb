package ly.anoma.hex.typing

import android.content.Context
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.ln

internal data class LearnedWords(
    val words: Map<String, Int> = emptyMap(),
    private val pairs: Map<String, Int> = emptyMap(),
) {
    val contextCount get() = pairs.keys.count { it.startsWith("p/") }
    val correctionCount get() = pairs.keys.count { it.startsWith("s/") }
    fun knows(word: String) = count(word) >= 3

    fun completions(prefix: String): List<String> = words.entries.asSequence()
        .filter { (word, count) -> count >= 3 && word.length > prefix.length && word.startsWith(prefix) }
        .sortedByDescending { it.value }.take(4).map { it.key }.toList()

    fun following(previous: String): List<String> {
        return context(previous).entries.asSequence().filter { it.value >= 2 }
            .sortedByDescending { it.value }.take(4).map { it.key }.toList()
    }

    fun wordWeight(word: String) = ln(1.0 + count(word))

    fun context(previous: String?) = related("p", previous)

    fun preferencesAgainst(original: String) = related("s", original)

    private fun related(type: String, text: String?): Map<String, Int> {
        if (text.isNullOrEmpty() || pairs.isEmpty()) return emptyMap()
        val prefix = pairPrefix(type, text)
        return pairs.asSequence().filter { it.key.startsWith(prefix) }
            .associate { it.key.removePrefix(prefix) to it.value }
    }

    private fun count(word: String) = words[word.lowercase().replace('’', '\'')] ?: 0

    fun record(text: String, previous: String?, replaced: String?, explicit: Boolean): LearnedWords {
        val word = normalize(text) ?: return this
        val updated = LinkedHashMap(words)
        updated.bump(word, if (explicit) 3 else 1, 384)
        if (previous == null && replaced == null) return LearnedWords(updated, pairs)
        val relationships = LinkedHashMap(pairs)
        normalize(previous, min = 1)?.let { relationships.bump(pairPrefix("p", it) + word, if (explicit) 2 else 1, 768) }
        normalize(replaced)?.takeUnless { it == word }?.let { relationships.bump(pairPrefix("s", it) + word, 1, 768) }
        return LearnedWords(updated, relationships)
    }

    fun encode() = buildString {
        for ((word, count) in words) append(word).append('\t').append(count).append('\n')
        for ((key, count) in pairs) append(key).append('\t').append(count).append('\n')
    }

    companion object {
        val Empty = LearnedWords()

        private fun normalize(text: String?, min: Int = 2): String? {
            val word = text?.lowercase()?.replace('’', '\'') ?: return null
            return word.takeIf { it.length in min..32 && it.first().isLetter() && it.last().isLetter() &&
                it.all { c -> c.isLetter() || c == '\'' || c == '-' } }
        }

        private fun pairPrefix(type: String, text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.lowercase().replace('’', '\'').toByteArray())
            return "$type/${Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(16))}/"
        }

        private fun LinkedHashMap<String, Int>.bump(key: String, amount: Int, limit: Int) {
            put(key, ((remove(key) ?: 0) + amount).coerceAtMost(24))
            if (size > limit) remove(keys.first())
        }

        fun decode(text: String): LearnedWords {
            val words = LinkedHashMap<String, Int>()
            val pairs = LinkedHashMap<String, Int>()
            for (line in text.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab < 1) continue
                val count = line.substring(tab + 1).toIntOrNull()?.takeIf { it in 1..24 } ?: continue
                val key = line.substring(0, tab)
                if ('/' in key) {
                    if (pairs.size < 768) pairs[key] = count
                } else if (words.size < 384 && normalize(key) != null) words[key] = count
            }
            return LearnedWords(words, pairs)
        }
    }
}

internal enum class LearningMode { Tap, Swipe }

internal data class LearningReport(val language: String, val mode: LearningMode, val learned: LearnedWords)

/** Each language and input mode keeps a bounded vocabulary and recent word associations on-device. */
internal class LearningStore(context: Context) {
    private val prefs = context.getSharedPreferences("learned-words", Context.MODE_PRIVATE)
    private val cache = HashMap<String, Pair<String, LearnedWords>>()

    @Synchronized
    fun snapshot(language: String, mode: LearningMode): LearnedWords {
        val key = "$language/${mode.name}"
        val text = prefs.getString(key, "").orEmpty()
        return cache[key]?.takeIf { it.first == text }?.second ?: LearnedWords.decode(text).also {
            cache[key] = text to it
        }
    }

    @Synchronized
    fun record(language: String, mode: LearningMode, word: String, previous: String?, replaced: String?, explicit: Boolean) {
        val original = snapshot(language, mode)
        val updated = original.record(word, previous, replaced, explicit)
        if (updated === original) return
        val key = "$language/${mode.name}"
        val text = updated.encode()
        cache[key] = text to updated
        prefs.edit().putString(key, text).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    fun reports(): List<LearningReport> = prefs.all.keys.mapNotNull { key ->
        val mode = LearningMode.entries.firstOrNull { it.name == key.substringAfter('/', "") } ?: return@mapNotNull null
        val language = key.substringBefore('/')
        val learned = snapshot(language, mode)
        if (learned.words.isEmpty()) null else LearningReport(language, mode, learned)
    }.sortedWith(compareBy<LearningReport> { it.language }.thenBy { it.mode })

    fun observe(changed: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
