package ly.anoma.hex.typing

import kotlin.math.ln
import kotlin.math.pow

/** Trie-constrained search over FUTO Swipe emissions, using its published encoder scoring weights. */
internal class FutoSwipeDecoder(
    private val letters: String,
    private val dictionary: Map<String, Int>,
    private val pairs: Map<String, Map<String, Int>>,
    private val russianRanking: Boolean = false,
) {
    private data class Word(val text: String, val frequency: Int)
    private data class Candidate(val word: Word, val score: Double)
    private data class State(val id: Int, val score: Float)
    private data class PersonalWord(val word: Word, val keys: List<Int>, val priority: Double)

    private val letterIds = letters.withIndex().associate { (index, letter) -> letter to index }
    private val terminals = HashMap<Int, Word>()
    private val alternateSpellings = HashMap<Int, MutableList<Word>>()
    private var first = IntArray(1 shl 16)
    private var next = IntArray(first.size)
    private var letter = IntArray(first.size)
    private var depth = IntArray(first.size)
    private var size = 1

    init {
        for ((text, frequency) in dictionary) {
            val spelling = spelling(text)
            if (spelling.length !in 2..FutoLayout.STEPS || spelling.any { it !in letterIds }) continue
            var at = 0
            for (c in spelling) at = addChild(at, letterIds.getValue(c))
            val word = Word(text, frequency)
            val existing = terminals[at]
            if (existing == null) terminals[at] = word
            else if (frequency > existing.frequency) {
                terminals[at] = word
                if (!existing.text.equals(text, ignoreCase = true))
                    alternateSpellings.getOrPut(at) { ArrayList() }.add(existing)
            } else if (!existing.text.equals(text, ignoreCase = true)) {
                alternateSpellings.getOrPut(at) { ArrayList() }.add(word)
            }
        }
    }

    private fun addChild(parent: Int, key: Int): Int {
        var child = first[parent]
        while (child != 0) {
            if (letter[child] == key) return child
            child = next[child]
        }
        val id = size++
        if (id == first.size) {
            val capacity = first.size * 2
            first = first.copyOf(capacity)
            next = next.copyOf(capacity)
            letter = letter.copyOf(capacity)
            depth = depth.copyOf(capacity)
        }
        letter[id] = key
        depth[id] = depth[parent] + 1
        next[id] = first[parent]
        first[parent] = id
        return id
    }

    fun suggestions(emissions: FloatArray, previous: String?, learned: LearnedWords, typed: LearnedWords,
                    learnContext: Boolean, allow: (String) -> Boolean = { true }): List<String> {
        require(emissions.size == FutoLayout.STEPS * FutoLayout.CLASSES)
        val candidates = beam(emissions, allow).toMutableList()
        if (learned.words.isNotEmpty() || typed.words.isNotEmpty()) {
            val alreadyScored = candidates.mapTo(HashSet()) { it.word.text.lowercase() }
            for ((word, keys) in personalWords(emissions, learned, typed, alreadyScored, allow)) {
                val score = emissionsFor(emissions, keys)
                candidates.add(Candidate(word, rank(score, keys.size, word.frequency)))
            }
        }
        val leading = candidates.maxByOrNull { it.score } ?: return emptyList()
        val preferred = learned.preferencesAgainst(leading.word.text)
        val priorWord = previous?.lowercase()?.replace('ё', 'е')
        val corpus = pairs[priorWord].orEmpty()
        val context = learned.context(previous.takeIf { learnContext })
        val typedContext = typed.context(previous.takeIf { learnContext })
        return candidates.sortedByDescending { candidate ->
            val word = candidate.word.text.lowercase()
            val adjacent = corpus[word.replace('ё', 'е')] ?: 0
            val personal = .27 * learned.wordWeight(word) + .15 * typed.wordWeight(word)
            val choice = (.65 * ln(1.0 + (preferred[word] ?: 0))).coerceAtMost(.9)
            val following = .19 * ln(1.0 + (context[word] ?: 0) + (typedContext[word] ?: 0))
            candidate.score + (.10 * ln(1.0 + adjacent)).coerceAtMost(.65) + personal + choice + following
        }.asSequence().map { it.word.text }.distinctBy(String::lowercase).take(3).toList()
    }

    private fun personalWords(emissions: FloatArray, learned: LearnedWords, typed: LearnedWords,
                              alreadyScored: Set<String>, allow: (String) -> Boolean): List<PersonalWord> {
        val window = FutoLayout.STEPS / 3
        val start = FloatArray(letters.length) { key -> (0 until window).maxOf { emissions[it * FutoLayout.CLASSES + key] } }
        val end = FloatArray(letters.length) { key ->
            (FutoLayout.STEPS - window until FutoLayout.STEPS).maxOf { emissions[it * FutoLayout.CLASSES + key] }
        }
        return (learned.words.keys + typed.words.keys).asSequence()
            .filter { it !in alreadyScored && (learned.knows(it) || typed.knows(it)) && allow(it) }
            .mapNotNull { text ->
                val shape = spelling(text)
                if (shape.length !in 2..FutoLayout.STEPS || shape.any { it !in letterIds }) return@mapNotNull null
                val keys = shape.map(letterIds::getValue)
                val display = text.replaceFirstChar(Char::uppercaseChar).takeIf { text !in dictionary && it in dictionary } ?: text
                val usage = .15 * (learned.wordWeight(text) + typed.wordWeight(text))
                PersonalWord(Word(display, dictionary[display] ?: 195), keys, start[keys.first()] + end[keys.last()] + usage)
            }.sortedByDescending { it.priority }.take(48).toList()
    }

    private fun beam(emissions: FloatArray, allow: (String) -> Boolean): List<Candidate> {
        var states = listOf(State(1, 0f))
        val expanded = HashMap<Int, Float>(BEAM * 8)
        for (step in 0 until FutoLayout.STEPS) {
            val row = step * FutoLayout.CLASSES
            expanded.clear()
            fun put(id: Int, score: Float) {
                if (score > (expanded[id] ?: Float.NEGATIVE_INFINITY)) expanded[id] = score
            }
            for ((id, score) in states) {
                val node = id ushr 1
                put((node shl 1) + 1, score + emissions[row + FutoLayout.KEYS])
                var child = first[node]
                while (child != 0) {
                    put(child shl 1, score + emissions[row + letter[child]])
                    child = next[child]
                }
                if (node != 0 && id and 1 == 0) put(id, score + emissions[row + letter[node]])
            }
            states = expanded.entries.sortedByDescending {
                val length = depth[it.key ushr 1]
                it.value * PRUNE_NORM[length] + BETA_PRUNE * length
            }.take(BEAM).map { State(it.key, it.value) }
        }
        val best = HashMap<Int, Float>()
        for ((id, score) in states) {
            val node = id ushr 1
            if (node in terminals && score > (best[node] ?: Float.NEGATIVE_INFINITY)) best[node] = score
        }
        return best.flatMap { (node, score) ->
            val words = listOf(terminals.getValue(node)) + alternateSpellings[node].orEmpty()
            words.filter { allow(it.text) }.map { Candidate(it, rank(score, depth[node], it.frequency)) }
        }.sortedByDescending { it.score }.distinctBy { it.word.text.lowercase() }.take(16)
    }

    /** Score a learned word even when it was absent from the shipped vocabulary. */
    private fun emissionsFor(emissions: FloatArray, ids: List<Int>): Float {
        var blank = FloatArray(ids.size + 1) { Float.NEGATIVE_INFINITY }
        var seen = blank.copyOf()
        blank[0] = 0f
        for (step in 0 until FutoLayout.STEPS) {
            val row = step * FutoLayout.CLASSES
            val nextBlank = FloatArray(blank.size)
            val nextSeen = FloatArray(blank.size)
            nextSeen[0] = Float.NEGATIVE_INFINITY
            for (i in blank.indices) {
                nextBlank[i] = maxOf(blank[i], seen[i]) + emissions[row + FutoLayout.KEYS]
                if (i > 0) nextSeen[i] = maxOf(blank[i - 1], seen[i - 1], seen[i]) + emissions[row + ids[i - 1]]
            }
            blank = nextBlank
            seen = nextSeen
        }
        return maxOf(blank.last(), seen.last())
    }

    private fun spelling(text: String) = buildString {
        for (c in text.lowercase()) {
            if (c in "-'’") continue
            append(when (c) { 'ё' -> if ('ё' in letterIds) c else 'е'; else -> c })
        }
    }

    private fun rank(ctc: Float, length: Int, frequency: Int): Double = if (russianRanking) {
        ctc * RUSSIAN_RANK_NORM[length] + .013 * frequency + .6 * length
    } else {
        ctc * RANK_NORM[length] + .0176 * frequency + .9866 * length
    }

    private companion object {
        const val BEAM = 100
        const val BETA_PRUNE = 1.0382
        val PRUNE_NORM = DoubleArray(FutoLayout.STEPS + 1) { maxOf(1, it).toDouble().pow(-.4234) }
        val RANK_NORM = DoubleArray(FutoLayout.STEPS + 1) { maxOf(1, it).toDouble().pow(-.4056) }
        val RUSSIAN_RANK_NORM = DoubleArray(FutoLayout.STEPS + 1) { maxOf(1, it).toDouble().pow(-.45) }
    }
}
