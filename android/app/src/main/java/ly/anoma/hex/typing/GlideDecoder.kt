/*
 * Path scoring originated in FlorisBoard and Dictate Keyboard's
 * StatisticalGlideTypingClassifier, licensed under Apache-2.0.
 * See assets/licenses/swipe-notice.txt.
 */
package ly.anoma.hex.typing

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

internal class GlideDecoder(words: Map<String, Int>, private val pairs: Map<String, Map<String, Int>> = emptyMap()) {
    private data class Word(val text: String, val keys: String, val prior: Double)
    private data class Candidate(val word: Word, val paths: List<KeyPoint>, val distance: Double, val penalties: Double)
    private val byEnds = words.asSequence().mapNotNull { (word, frequency) -> entry(word, frequency) }.groupBy { it.ends }
    private val Word.ends get() = keys.first() to keys.last()

    fun suggestions(path: List<KeyPoint>, grid: KeyGrid, previous: String? = null,
                    learned: LearnedWords = LearnedWords.Empty, typed: LearnedWords = LearnedWords.Empty,
                    learnContext: Boolean = true, allow: (String) -> Boolean = { true }): List<String> {
        if (path.size < 2) return emptyList()
        val width = abs(grid.pitchX).coerceAtLeast(1.0)
        val height = abs(grid.pitchY).coerceAtLeast(1.0)
        fun scale(point: KeyPoint) = KeyPoint(point.x / width, point.y / height)
        val keys = grid.centres.mapValues { scale(it.value) }
        val trace = path.map(::scale)
        val traceLength = length(trace)
        if (traceLength < 0.6) return emptyList()
        val observed = resample(trace, traceLength)
        val pair = previous?.let { pairs[it.lowercase().replace('ё', 'е')] }
        val personalContext = learned.context(previous.takeIf { learnContext })
        val typedContext = typed.context(previous.takeIf { learnContext })
        val additional = (learned.words.keys + typed.words.keys).asSequence().mapNotNull { word ->
            val count = maxOf(learned.words[word] ?: 0, typed.words[word] ?: 0)
            if (count >= 3) entry(word, 70)?.takeIf { item -> byEnds[item.ends]?.none { it.text == word } != false }
            else null
        }.groupBy { it.ends }
        val nearest = keys.mapValues { (_, key) -> trace.minOf { distance(it, key) } }

        fun ends(point: KeyPoint) = keys.entries
            .map { it.key to distance(it.value, point) }
            .sortedBy { it.second }.take(4).filter { it.second < 1.55 }
        val first = ends(trace.first())
        val last = ends(trace.last())
        val candidates = ArrayList<Candidate>()
        for ((start, startError) in first) for ((end, endError) in last) {
            val ends = start to end
            for (word in byEnds[ends].orEmpty() + additional[ends].orEmpty()) {
                if (!allow(word.text)) continue
                val ideal = word.keys.mapNotNull(keys::get)
                if (ideal.size != word.keys.length) continue
                val idealLength = length(ideal)
                val difference = abs(idealLength - traceLength)
                if (difference > max(2.8, traceLength * .62)) continue
                val misses = (1 until word.keys.lastIndex).sumOf { i ->
                    val miss = (nearest.getValue(word.keys[i]) - .38).coerceAtLeast(0.0)
                    miss * miss
                }
                val sampled = resample(ideal, idealLength)
                val direct = sampled.indices.sumOf { distance(sampled[it], observed[it]) } / SAMPLES
                val count = pair?.get(word.text.lowercase().replace('ё', 'е')) ?: 0
                val context = (.13 * ln(count + 1.0)).coerceAtMost(.85)
                val usage = (.18 * learned.wordWeight(word.text) + .11 * typed.wordWeight(word.text)).coerceAtMost(.68)
                val contextUsage = .18 * ln(1.0 + (personalContext[word.text] ?: 0) + (typedContext[word.text] ?: 0))
                val edgeError = 1.2 * (startError * startError + endError * endError)
                val penalties = edgeError + 1.6 * misses + .18 * difference + word.prior - context - usage - contextUsage
                candidates.add(Candidate(word, sampled, direct, penalties))
            }
        }

        // Equal-distance samples are cheap for pruning. The final comparison tolerates corners
        // drawn early or late, while still penalizing an extra bend or a pass far from a letter.
        val ranked = candidates.sortedBy { 3.0 * it.distance + it.penalties }.take(FINALISTS)
            .map { it.word.text to (1.25 * it.distance + 2.8 * alignment(it.paths, observed) + it.penalties) }
            .sortedBy { it.second }.distinctBy { it.first }
        val best = ranked.firstOrNull() ?: return emptyList()
        val preferred = learned.preferencesAgainst(best.first)
        // A deliberate alternative changes the order only when the two gestures already match closely.
        val choices = ranked.take(3).sortedBy { (word, score) ->
            score - (.65 * ln(1.0 + (preferred[word] ?: 0))).coerceAtMost(.9)
        }
        return choices.map { it.first }
    }

    private fun entry(word: String, frequency: Int): Word? {
        if (word.length !in 2..48 || word.any { !it.isLetter() && it !in "-'’" }) return null
        val keys = buildString {
            for (char in word.lowercase().replace('ё', 'е').replace('ґ', 'г'))
                if (char.isLetter() && lastOrNull() != char) append(char)
        }
        return if (keys.length >= 2) Word(word, keys, -.28 * ln((frequency + 1.0) / 256.0)) else null
    }

    private fun alignment(ideal: List<KeyPoint>, observed: List<KeyPoint>): Double {
        var previous = DoubleArray(SAMPLES + 1) { Double.POSITIVE_INFINITY }
        var current = DoubleArray(SAMPLES + 1)
        previous[0] = 0.0
        for (i in ideal.indices) {
            current.fill(Double.POSITIVE_INFINITY)
            for (j in max(0, i - ALIGNMENT_BAND)..min(observed.lastIndex, i + ALIGNMENT_BAND)) {
                val diff = distance(ideal[i], observed[j])
                current[j + 1] = diff + min(previous[j], min(previous[j + 1], current[j]) + .20)
            }
            val old = previous
            previous = current
            current = old
        }
        return previous[SAMPLES] / SAMPLES
    }

    private fun length(points: List<KeyPoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += distance(points[i - 1], points[i])
        return sum
    }

    private fun resample(points: List<KeyPoint>, length: Double): List<KeyPoint> {
        val step = length / (SAMPLES - 1)
        var segment = 1
        var walked = 0.0
        var segmentLength = distance(points[0], points[1])
        return List(SAMPLES) { index ->
            val position = index * step
            while (segment < points.lastIndex && walked + segmentLength < position) {
                walked += segmentLength
                segment++
                segmentLength = distance(points[segment - 1], points[segment])
            }
            val a = points[segment - 1]
            val b = points[segment]
            val fraction = ((position - walked) / segmentLength.coerceAtLeast(.0001)).coerceIn(0.0, 1.0)
            KeyPoint(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction)
        }
    }

    private fun distance(a: KeyPoint, b: KeyPoint) = hypot(a.x - b.x, a.y - b.y)

    companion object {
        private const val SAMPLES = 32
        private const val FINALISTS = 36
        private const val ALIGNMENT_BAND = 8
        val languages = setOf("en", "ru", "uk")

        fun readPairs(lines: Sequence<String>): Map<String, Map<String, Int>> {
            val pairs = HashMap<String, MutableMap<String, Int>>()
            for (line in lines) {
                val separator = line.indexOf(' ')
                val tab = line.indexOf('\t')
                if (separator < 1 || tab <= separator + 1) continue
                val count = line.substring(tab + 1).toIntOrNull() ?: continue
                val previous = line.substring(0, separator).lowercase().replace('ё', 'е')
                val word = line.substring(separator + 1, tab).lowercase().replace('ё', 'е')
                val row = pairs.getOrPut(previous) { HashMap() }
                row[word] = maxOf(row[word] ?: 0, count)
            }
            return pairs
        }
    }
}
