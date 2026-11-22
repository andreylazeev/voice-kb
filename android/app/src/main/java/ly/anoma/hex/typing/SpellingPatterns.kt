package ly.anoma.hex.typing

/** Letter triples have logarithmic counts and quadruples have presence bits.
 * Letter codes match HXTM. Zero marks the boundaries of a word. */
internal class SpellingPatterns(private val bytes: ByteArray, private val radix: Int) {
    enum class Evidence { None, Weak, Strong }

    private val triples = radix * radix * radix

    init {
        require(bytes.size == triples + (triples * radix + 7) / 8)
    }

    fun evidence(typed: IntArray, candidate: IntArray): Evidence {
        val firstChange = typed.indices.first { typed[it] != candidate[it] }
        val lastChange = typed.indices.last { typed[it] != candidate[it] }
        var missingTriples = 0
        var missingQuadruples = 0
        var tripleGain = 0
        var firstUnsupported = false
        var lastUnsupported = false
        for (end in 2..typed.lastIndex) {
            val triple = triple(typed, end)
            val support = bytes[triple].toInt()
            if (support == 0) missingTriples++
            tripleGain += bytes[triple(candidate, end)].toInt() - support
            if (end >= 3 && !containsQuadruple(typed[end - 3] * triples + triple)) {
                missingQuadruples++
                val window = end - 3..end
                if (firstChange in window && lastChange !in window) firstUnsupported = true
                if (lastChange in window && firstChange !in window) lastUnsupported = true
            }
        }
        return when {
            (firstUnsupported && lastUnsupported) || missingTriples >= 2 || missingQuadruples >= 4 -> Evidence.Strong
            missingTriples > 0 || missingQuadruples >= 3 -> Evidence.Weak
            // Weights are quarter bits of log2(1 + count). Require a large
            // gain when every typed triple already occurs in the dictionary.
            missingQuadruples >= 2 && tripleGain >= 48 -> Evidence.Weak
            else -> Evidence.None
        }
    }

    private fun triple(letters: IntArray, end: Int): Int =
        (letters[end - 2] * radix + letters[end - 1]) * radix + letters[end]

    private fun containsQuadruple(index: Int): Boolean =
        bytes[triples + index / 8].toInt() and (1 shl (index % 8)) != 0
}
