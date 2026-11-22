package ly.anoma.hex.speech

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import java.util.zip.GZIPInputStream

internal interface Recognizer {
    fun decode(pcm: ShortArray, count: Int, language: String, translate: Boolean, prompt: String? = null): String?
    fun openStream(language: String): SpeechStream? = null
}

internal data class LiveSpeech(val committed: String, val partial: String) {
    val text get() = listOf(committed, partial).filter(String::isNotEmpty).joinToString(" ")
}

internal interface SpeechStream : AutoCloseable {
    fun push(pcm: ShortArray, offset: Int, count: Int): LiveSpeech?
    fun finish(): String?
}

internal class LocalSpeech(private val app: Context) : Recognizer {
    override fun decode(pcm: ShortArray, count: Int, language: String, translate: Boolean, prompt: String?): String? =
        TinyAsr.decode(app.assets, pcm, count, language)
    override fun openStream(language: String): SpeechStream? = TinyAsr.stream(app.assets, language)
}

internal object TinyAsr {
    init { System.loadLibrary("voicekb") }

    // The setup screen and the IME share one resident model, with a separate native state per stream.
    private var model = 0L

    @Synchronized
    private fun model(assets: AssetManager): Long {
        if (model == 0L) {
            model = load(asset(assets, "en-ru-uk.tzf.gzip"), asset(assets, "silero_vad.onnx.gzip"))
        }
        return model
    }

    fun decode(assets: AssetManager, pcm: ShortArray, count: Int, language: String): String? {
        val encoded = transcribe(model(assets), pcm, count, language) ?: return null
        return buildList {
            encoded.decodeToString().lineSequence().filter(String::isNotBlank).forEach { line ->
                addAll(words(JSONArray(line)))
            }
        }.joinToString(" ")
    }

    fun stream(assets: AssetManager, language: String): SpeechStream? =
        openSession(model(assets), language).takeIf { it != 0L }?.let(::Stream)

    private fun words(array: JSONArray?): List<String> = if (array == null) emptyList() else
        List(array.length()) { array.getJSONObject(it).getString("text") }

    private class Stream(private var handle: Long) : SpeechStream {
        private val committed = ArrayList<String>()
        private var partial = emptyList<String>()

        override fun push(pcm: ShortArray, offset: Int, count: Int): LiveSpeech? {
            if (!applyEvents(pushSession(handle, pcm, offset, count))) return null
            return LiveSpeech(committed.joinToString(" "), partial.joinToString(" "))
        }

        override fun finish(): String? {
            if (!applyEvents(finishSession(handle))) return null
            return committed.joinToString(" ")
        }

        private fun applyEvents(json: ByteArray?): Boolean {
            val events = JSONArray(json?.decodeToString() ?: return false)
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                when (event.getString("type")) {
                    "commit" -> { committed.addAll(words(event.optJSONArray("words"))); partial = emptyList() }
                    "partial" -> partial = words(event.optJSONArray("open")) + words(event.optJSONArray("tail"))
                }
            }
            return true
        }

        override fun close() {
            closeSession(handle)
            handle = 0
        }
    }

    private fun asset(assets: AssetManager, name: String) =
        assets.open("speech/$name").use { input -> GZIPInputStream(input).use { it.readBytes() } }

    private external fun load(weights: ByteArray, detector: ByteArray): Long
    private external fun transcribe(model: Long, pcm: ShortArray, count: Int, language: String): ByteArray?
    private external fun openSession(model: Long, language: String): Long
    private external fun pushSession(handle: Long, pcm: ShortArray, offset: Int, count: Int): ByteArray?
    private external fun finishSession(handle: Long): ByteArray?
    private external fun closeSession(handle: Long)
}
