package ly.anoma.hex.speech

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

internal enum class CleanupModel(val label: String, val size: Long, val sha256: String,
                                 repository: String, revision: String, val filename: String) {
    Qwen("Qwen3.5 0.8B", 964_657_152L, "d6702088983b3a997db5299558a37d2fd924a94daf2b09bd42c4e618c6f694a4",
        "Qwen3.5-0.8B", "7c656dc9e84b135df6b702e54fbe14a05c67abb8", "Qwen3.5-0.8B_int8.litertlm"),
    Gemma2("Gemma 4 E2B", 2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        "gemma-4-E2B-it-litert-lm", "2e12bfd869b2962984f97a1f2a26807316d534cc", "gemma-4-E2B-it.litertlm"),
    Gemma4("Gemma 4 E4B", 3_659_530_240L, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        "gemma-4-E4B-it-litert-lm", "f254b5ba6e36f7d89cc7892e268e9877bc20f0a0", "gemma-4-E4B-it.litertlm");

    val url = "https://huggingface.co/litert-community/$repository/resolve/$revision/$filename?download=true"

    companion object {
        fun recommended(ramGiB: Double, lowRam: Boolean): CleanupModel? = when {
            lowRam || ramGiB < 3.5 -> null
            ramGiB < 5.5 -> Qwen
            ramGiB < 10.5 -> Gemma2
            else -> Gemma4
        }
    }
}

internal object CleanupModels {

    enum class Phase { Idle, Downloading, Verifying, Paused, Failed, Ready }
    data class Download(val phase: Phase, val bytes: Long = 0)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Gemma model download") }
    private val download = MutableStateFlow<Map<CleanupModel, Download>>(emptyMap())
    val state = download.asStateFlow()
    @Volatile private var paused = false

    fun file(context: Context, model: CleanupModel): File = File(context.getExternalFilesDir("models")
        ?: File(context.noBackupFilesDir, "models"), model.filename)
    fun ready(context: Context, model: CleanupModel) = file(context, model).let { it.isFile && it.length() == model.size }
    private fun busy() = download.value.values.any { it.phase == Phase.Downloading || it.phase == Phase.Verifying }
    private fun status(model: CleanupModel, phase: Phase, bytes: Long = 0) {
        download.value = download.value + (model to Download(phase, bytes))
    }

    @Synchronized fun download(context: Context, model: CleanupModel) {
        if (busy() || ready(context, model)) return
        val destination = file(context, model)
        val partial = File(destination.path + ".part")
        paused = false
        status(model, Phase.Downloading, partial.length())
        worker.execute {
            try {
                val directory = checkNotNull(destination.parentFile)
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
                if (partial.length() > model.size) partial.delete()
                if (partial.length() < model.size) transfer(model, partial)
                if (paused) {
                    status(model, Phase.Paused, partial.length())
                } else {
                    status(model, Phase.Verifying, model.size)
                    if (partial.length() != model.size) throw IOException("Incomplete download")
                    if (!digestMatches(partial, model.sha256)) {
                        partial.delete()
                        throw IOException("Model checksum mismatch")
                    }
                    if (!partial.renameTo(destination)) throw IOException("Cannot store the verified model")
                    status(model, Phase.Ready, model.size)
                }
            } catch (_: IOException) {
                status(model, if (paused) Phase.Paused else Phase.Failed, partial.length())
            }
        }
    }

    fun pause() { paused = true }

    @Synchronized fun delete(context: Context, model: CleanupModel) {
        if (busy()) return
        val destination = file(context, model)
        destination.delete()
        File(destination.path + ".part").delete()
        ModelCleanup.release(model)
        status(model, Phase.Idle)
    }

    private fun transfer(model: CleanupModel, partial: File) {
        val existing = partial.length()
        if (checkNotNull(partial.parentFile).usableSpace < model.size - existing + 32_000_000) throw IOException("Insufficient storage")
        val connection = URL(model.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
            val response = connection.responseCode
            val append = response == HttpURLConnection.HTTP_PARTIAL && existing > 0 &&
                connection.getHeaderField("Content-Range")?.startsWith("bytes $existing-") == true
            if (response != HttpURLConnection.HTTP_OK && !append) throw IOException("Model server returned $response")
            var total = if (append) existing else 0L
            var lastUpdate = total
            connection.inputStream.use { source ->
                java.io.FileOutputStream(partial, append).buffered().use { target ->
                    val buffer = ByteArray(256 * 1024)
                    while (!paused) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > model.size) throw IOException("Unexpected model size")
                        target.write(buffer, 0, count)
                        if (total - lastUpdate >= 4_000_000) {
                            status(model, Phase.Downloading, total)
                            lastUpdate = total
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun digestMatches(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == expected
    }
}
