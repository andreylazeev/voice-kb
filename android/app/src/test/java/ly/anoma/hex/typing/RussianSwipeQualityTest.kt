package ly.anoma.hex.typing

import java.io.DataInputStream
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional public-gesture replay. Neither CI nor the installed keyboard includes the dataset. */
class RussianSwipeQualityTest {
    private class Metrics {
        var cases = 0
        var covered = 0
        var first = 0
        var top3 = 0
        var points = 0.0

        fun add(target: String, known: Boolean, candidates: List<String>) {
            cases++
            if (known) covered++
            val at = candidates.indexOfFirst { it.equals(target, ignoreCase = true) }
            if (at == 0) first++
            if (at in 0..2) top3++
            points += when (at) { 0 -> 1.0; 1 -> .1; 2 -> .09; else -> 0.0 }
        }

        override fun toString() = "top1=$first/$cases top3=$top3/$cases coverage=$covered/$cases swipeMRR=" + points / cases
    }

    @Test fun replayPublicGestures() {
        val path = System.getenv("VOICEKB_SWIPE_EMISSIONS")
        assumeTrue(!path.isNullOrBlank())
        val assets = File("src/main/assets")
        val stock = Regex(""""([^"]+)":\s*(\d+)""").findAll(File(assets, "glide/ru.json").readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }.toMutableMap()
        val model = TypingModel.file(File(assets, "typing/ru.hextm"), "ru")
        model.addSwipeSpellings(stock)
        val expanded = stock.toMutableMap()
        File(assets, "glide/ru-extra.tsv").useLines { GlideDecoder.addVocabulary(expanded, it) }
        val dictionaries = listOf("stock" to stock, "expanded" to expanded, "tuned" to expanded)
        val decoders = dictionaries.map { HashMap<String, FutoSwipeDecoder>() }
        val vocabularies = dictionaries.map { (_, entries) -> entries.keys.mapTo(HashSet()) { it.lowercase() } }
        val results = dictionaries.map { Array(2) { Metrics() } }
        val partition = System.getenv("VOICEKB_SWIPE_PARTITION")
        require(partition == null || partition in setOf("calibration", "validation"))
        val calibrationDigits = setOf(1, 3, 6)
        DataInputStream(File(checkNotNull(path)).inputStream().buffered(512 * 1024)).use { input ->
            while (input.available() > 0) {
                val index = input.readInt()
                val lettersLength = input.readUnsignedShort()
                val targetLength = input.readUnsignedShort()
                val letters = input.readNBytes(lettersLength).decodeToString()
                val target = input.readNBytes(targetLength).decodeToString()
                val scores = FloatArray(FutoLayout.STEPS * FutoLayout.CLASSES) { input.readFloat() }
                val calibration = index % 10 in calibrationDigits
                if (partition == "calibration" && !calibration || partition == "validation" && calibration) continue
                val group = if (calibration) 0 else 1
                for ((slot, dictionary) in dictionaries.withIndex()) {
                    val decoder = decoders[slot].getOrPut(letters) {
                        FutoSwipeDecoder(letters, dictionary.second, emptyMap(), russianRanking = dictionary.first == "tuned")
                    }
                    val candidates = decoder.suggestions(scores, null, LearnedWords.Empty, LearnedWords.Empty, false) {
                        !model.neverSuggest.blocks(it.lowercase(), false, -1)
                    }
                    results[slot][group].add(target, target in vocabularies[slot], candidates)
                }
            }
        }
        for ((index, dictionary) in dictionaries.withIndex()) {
            if (partition != "validation") println(dictionary.first + " calibration " + results[index][0])
            if (partition != "calibration") println(dictionary.first + " validation " + results[index][1])
        }
    }
}
