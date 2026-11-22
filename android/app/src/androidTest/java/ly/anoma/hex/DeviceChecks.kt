package ly.anoma.hex

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import ly.anoma.hex.speech.LocalSpeech
import ly.anoma.hex.speech.CleanupModel
import ly.anoma.hex.speech.CleanupModels
import ly.anoma.hex.speech.DictationLanguage
import ly.anoma.hex.speech.ModelCleanup
import ly.anoma.hex.speech.SpeechOptions
import ly.anoma.hex.speech.SpokenText
import ly.anoma.hex.speech.Style
import ly.anoma.hex.keyboard.KeyboardLetters
import ly.anoma.hex.typing.FutoLayout
import ly.anoma.hex.typing.FutoSwipeDecoder
import ly.anoma.hex.typing.FutoSwipeModel
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingWord
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** ARM64 smoke test for the recognizer used by the IME, reading the APK's bundled model. */
class DeviceChecks : Instrumentation() {
    private var cleanupModel: CleanupModel? = null
    private var downloadModel = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        cleanupModel = arguments?.getString("cleanup_model")?.let(CleanupModel::valueOf)
        downloadModel = arguments?.getString("download_model") == "true"
        start()
    }

    override fun onStart() {
        val summary = Bundle()
        var passed = false
        try {
            val recognizer = LocalSpeech(targetContext)
            for ((lang, expected) in listOf("ru" to "привет", "en" to "hello")) {
                val wav = context.assets.open("$lang.pcm").use { it.readBytes() }
                val pcm = ShortArray(wav.size / 2).also {
                    ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
                }
                val text = recognizer.decode(pcm, pcm.size, lang, translate = false).orEmpty()
                summary.putString(lang, text)
                check(text.contains(expected, ignoreCase = true)) { "$lang did not recognize '$expected'" }
                val live = checkNotNull(recognizer.openStream(lang)) { "$lang stream could not start" }
                live.use { stream ->
                    val previews = mutableListOf<String>()
                    for (start in pcm.indices step 3200) {
                        val preview = checkNotNull(stream.push(pcm, start, minOf(3200, pcm.size - start))).text
                        if (preview.isNotBlank() && previews.lastOrNull() != preview) previews.add(preview)
                    }
                    check(previews.size >= 2) { "$lang did not produce evolving previews before finish: $previews" }
                    val final = stream.finish().orEmpty()
                    check(final.contains(expected, ignoreCase = true)) { "$lang live session did not recognize '$expected'" }
                    summary.putInt("${lang}_preview_updates", previews.size)
                    summary.putString("${lang}_stream_final", final)
                }
            }
            val input = context.assets.open("ru-restart.pcm").use { it.readBytes() }
            val sample = ShortArray(input.size / 2).also {
                ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
            }
            val options = SpeechOptions(Style.Polished, DictationLanguage.Russian, clean = true, translate = false)
            checkNotNull(recognizer.openStream("ru")).use { stream ->
                var latest = ""
                for (offset in sample.indices step 3200) {
                    val preview = checkNotNull(stream.push(sample, offset, minOf(3200, sample.size - offset)))
                    latest = SpokenText.forPreview(preview, options).text
                }
                val raw = checkNotNull(stream.finish())
                val clean = SpokenText.forDictation(raw, options)
                check(raw.contains("хотел, я хотел", ignoreCase = true)) { "Expected ASR restart: $raw" }
                check(clean == "Я хотел бы уточнить время встречи.") { "Cleanup returned: $clean" }
                check(!latest.contains("хотел, я хотел", ignoreCase = true)) { "Unclean live preview: $latest" }
                summary.putString("ru_cleanup", clean)
            }
            FutoSwipeModel(targetContext).use { model ->
                for ((lang, expected) in listOf("ru" to "привет", "en" to "hello")) {
                    val rows = KeyboardLetters.rows(lang)
                    val width = rows.maxOf { it.length }
                    val centres = buildMap {
                        rows.forEachIndexed { y, row ->
                            row.forEachIndexed { x, letter ->
                                put(letter, KeyPoint((x + (width - row.length) / 2.0) * 30, y * 45.0))
                            }
                        }
                    }
                    val layout = FutoLayout(KeyGrid(centres, 30.0, 45.0))
                    val wordList = targetContext.assets.open("glide/$lang.json").bufferedReader().use { JSONObject(it.readText()) }
                    val typing = TypingModel.asset(targetContext.assets, lang)
                    val words = wordList.keys().asSequence().associateWith(wordList::getInt).toMutableMap()
                    typing.addSwipeSpellings(words)
                    val decoder = FutoSwipeDecoder(layout.letters, words, emptyMap())
                    val trace = expected.mapIndexed { index, letter ->
                        centres.getValue(letter).copy(timeMillis = index * 75L)
                    }
                    val started = android.os.SystemClock.elapsedRealtime()
                    val recognized = decoder.suggestions(model.emissions(trace, layout), null,
                        LearnedWords.Empty, LearnedWords.Empty, learnContext = false)
                    summary.putString("${lang}_futo", recognized.joinToString())
                    summary.putLong("${lang}_futo_ms", android.os.SystemClock.elapsedRealtime() - started)
                    check(expected in recognized) { "$lang FUTO did not recognize '$expected': $recognized" }
                    val swear = if (lang == "ru") "пизда" else "fuck"
                    val taps = TypingWord(SpatialDecoder(typing), spatial = false)
                    taps.explicit(swear.dropLast(1))
                    val points = swear.mapIndexed { index, letter -> centres.getValue(letter).copy(timeMillis = index * 75L) }
                    val emissions = model.emissions(points, layout)
                    for (enabled in listOf(false, true)) {
                        taps.suggestSwearWords = enabled
                        check(taps.strip().any { it.text == swear } == enabled) { "$lang tap opt-in: $enabled" }
                        val swipe = decoder.suggestions(emissions, null, LearnedWords.Empty, LearnedWords.Empty, false) {
                            !typing.neverSuggest.blocks(it.lowercase(), enabled, -1)
                        }
                        check((swear in swipe) == enabled) { "$lang swipe opt-in: $enabled, candidates: $swipe" }
                    }
                    summary.putString("${lang}_swear_opt_in", "tap and swipe OK")
                }
            }
            cleanupModel?.let { model ->
                if (downloadModel && !CleanupModels.ready(targetContext, model)) {
                    CleanupModels.download(targetContext, model)
                    val deadline = android.os.SystemClock.elapsedRealtime() + 120_000L
                    while (CleanupModels.state.value[model]?.phase in listOf(CleanupModels.Phase.Downloading, CleanupModels.Phase.Verifying) &&
                        android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
                    check(CleanupModels.ready(targetContext, model)) { "Model download: ${CleanupModels.state.value[model]}" }
                    summary.putString("downloaded_model", model.label)
                }
                val finished = CountDownLatch(1)
                val before = "Собственно говоря, э, я хотел, я хотел бы уточнить время встречи."
                var generated = before
                var cleaned = before
                val started = android.os.SystemClock.elapsedRealtime()
                ModelCleanup.submit(targetContext, model, before, { false }) {
                    generated = it
                    cleaned = SpokenText.forDictation(it, options)
                    finished.countDown()
                }
                check(finished.await(90, TimeUnit.SECONDS)) { "${model.label} did not finish" }
                summary.putString("learned_cleanup", cleaned)
                summary.putString("model_output", generated)
                summary.putLong("local_model_ms", android.os.SystemClock.elapsedRealtime() - started)
                check(generated != before) { "${model.label} returned the fallback transcript" }
                check(cleaned == "Я хотел бы уточнить время встречи." || cleaned == "Я хотел уточнить время встречи.") {
                    "${model.label} did not remove the restart and fillers: $cleaned"
                }
                ModelCleanup.release(model)
            }
            passed = true
        } catch (error: Throwable) {
            summary.putString("error", error.stackTraceToString())
        } finally {
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, summary)
        }
    }
}
