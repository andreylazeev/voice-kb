package ly.anoma.hex.typing

import kotlin.math.ln
import kotlin.math.pow

/** Trie-constrained search over FUTO Swipe emissions, using its published encoder scoring weights. */
internal class FutoSwipeDecoder(
    private val letters: String,
    dictionary: Map<String, Int>,
    private val pairs: Map<String, Map<String, Int>>,
) {
    private data class Word(val text: String, val frequency: Int)
    private data class Candidate(val word: Word, val score: Double)
    private data class State(val id: Int, val score: Float)

    private val letterIds = letters.withIndex().associate { (index, letter) -> letter to index }
    private val vocabulary = dictionary.keys.mapTo(HashSet()) { it.lowercase() }
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
                    learnContext: Boolean): List<String> {
        require(emissions.size == FutoLayout.STEPS * FutoLayout.CLASSES)
        val candidates = beam(emissions).toMutableList()
        val added = (learned.words.keys + typed.words.keys).asSequence()
            .filter { word -> word !in vocabulary && (learned.knows(word) || typed.knows(word)) }
            .sortedByDescending { (learned.words[it] ?: 0) + (typed.words[it] ?: 0) }
            .take(48)
        for (text in added) {
            val shape = spelling(text)
            if (shape.length !in 2..FutoLayout.STEPS) continue
            val ids = shape.map { letterIds[it] ?: -1 }
            if (-1 in ids) continue
            val score = emissionsFor(emissions, ids)
            candidates.add(Candidate(Word(text, 195), rank(score, ids.size, 195)))
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

    private fun beam(emissions: FloatArray): List<Candidate> {
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
            words.map { Candidate(it, rank(score, depth[node], it.frequency)) }
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

    private companion object {
        const val BEAM = 100
        const val BETA_PRUNE = 1.0382
        val PRUNE_NORM = DoubleArray(FutoLayout.STEPS + 1) { maxOf(1, it).toDouble().pow(-.4234) }
        val RANK_NORM = DoubleArray(FutoLayout.STEPS + 1) { maxOf(1, it).toDouble().pow(-.4056) }
        fun rank(ctc: Float, length: Int, frequency: Int) = ctc * RANK_NORM[length] + .0176 * frequency + .9866 * length
    }
}
