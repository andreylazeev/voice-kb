package ly.anoma.hex

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import ly.anoma.hex.speech.LocalSpeech
import ly.anoma.hex.keyboard.KeyboardLetters
import ly.anoma.hex.typing.FutoLayout
import ly.anoma.hex.typing.FutoSwipeDecoder
import ly.anoma.hex.typing.FutoSwipeModel
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.LearnedWords
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ARM64 smoke test for the recognizer used by the IME, reading the APK's bundled model. */
class DeviceChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
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
                    val words = wordList.keys().asSequence().associateWith(wordList::getInt)
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
                }
            }
            passed = true
        } catch (error: Throwable) {
            summary.putString("error", error.stackTraceToString())
        } finally {
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, summary)
        }
    }
}
