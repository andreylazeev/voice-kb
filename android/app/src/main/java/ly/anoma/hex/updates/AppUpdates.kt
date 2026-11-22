package ly.anoma.hex.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("""(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})""")
        fun parse(text: String): ReleaseVersion? {
            val match = pattern.matchEntire(text) ?: return null
            val (major, minor, patch) = match.destructured
            return ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

internal data class VoiceKbRelease(val version: ReleaseVersion, val url: String, val size: Long, val digest: String)

internal object AppUpdates {
    private const val LATEST = "https://api.github.com/repos/andreylazeev/voice-kb/releases/latest"
    private const val DOWNLOADS = "https://github.com/andreylazeev/voice-kb/releases/download"
    private const val MAX_APK_SIZE = 512L * 1024 * 1024
    private val downloadHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    enum class Phase { Idle, Checking, Current, Available, Downloading, Ready, Failed }
    data class Status(val phase: Phase = Phase.Idle, val release: VoiceKbRelease? = null, val percent: Int = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()

    fun preferences(context: Context) = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    fun check(context: Context) {
        if (status.value.phase in setOf(Phase.Checking, Phase.Downloading)) return
        val app = context.applicationContext
        mutableStatus.value = Status(Phase.Checking)
        scope.launch {
            mutableStatus.value = try {
                val release = latest(app)
                if (release == null) Status(Phase.Current)
                else if (withContext(Dispatchers.IO) { verifiedCache(app, release) }) Status(Phase.Ready, release)
                else Status(Phase.Available, release)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Status(Phase.Failed)
            }
        }
    }

    fun download(context: Context, release: VoiceKbRelease) {
        if (status.value.phase in setOf(Phase.Checking, Phase.Downloading)) return
        val app = context.applicationContext
        mutableStatus.value = Status(Phase.Downloading, release)
        scope.launch {
            mutableStatus.value = try {
                withContext(Dispatchers.IO) {
                    downloadVerified(app, release) { percent ->
                        mutableStatus.value = Status(Phase.Downloading, release, percent)
                    }
                }
                Status(Phase.Ready, release)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Status(Phase.Failed, release)
            }
        }
    }

    fun installIntent(context: Context): Intent? {
        val release = status.value.takeIf { it.phase == Phase.Ready }?.release ?: return null
        val file = apk(context, release)
        if (!file.isFile) {
            mutableStatus.value = Status(Phase.Available, release)
            return null
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun latest(context: Context): VoiceKbRelease? = withContext(Dispatchers.IO) {
        val current = ReleaseVersion.parse(installedVersion(context)) ?: throw IOException("Invalid installed version")
        connection(LATEST, setOf("api.github.com")).use { response ->
            val metadata = response.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > 512 * 1024) throw IOException("Release metadata is too large")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            parseRelease(JSONObject(metadata), current)
        }
    }

    internal fun parseRelease(json: JSONObject, current: ReleaseVersion): VoiceKbRelease? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.getString("tag_name")
        val version = ReleaseVersion.parse(tag.removePrefix("v")) ?: return null
        if (tag != "v$version" || version <= current) return null
        val filename = "VoiceKB-$version-arm64.apk"
        val url = "$DOWNLOADS/$tag/$filename"
        val assets = json.getJSONArray("assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") != filename || asset.optString("state") != "uploaded") continue
            if (asset.optString("browser_download_url") != url) throw IOException("Unexpected release asset URL")
            val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            val size = asset.optLong("size")
            if (digest == null || !digest.matches(Regex("[0-9a-f]{64}")) || size !in 1..MAX_APK_SIZE)
                throw IOException("Missing or invalid release asset integrity data")
            return VoiceKbRelease(version, url, size, digest)
        }
        throw IOException("Release APK unavailable")
    }

    private fun apk(context: Context, release: VoiceKbRelease) =
        File(File(context.cacheDir, "updates"), "VoiceKB-${release.version}-arm64.apk")

    private fun verifiedCache(context: Context, release: VoiceKbRelease): Boolean {
        val file = apk(context, release)
        if (!file.isFile || file.length() != release.size) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(bytes)
                    if (count == -1) break
                    digest.update(bytes, 0, count)
                }
            }
            require(digest.hex() == release.digest)
            verifyApk(context, file, release.version)
        }.isSuccess
    }

    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }

    private fun downloadVerified(context: Context, release: VoiceKbRelease, progress: (Int) -> Unit) {
        if (verifiedCache(context, release)) return
        val destination = apk(context, release)
        val directory = checkNotNull(destination.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create updates directory")
        val partial = File(directory, "pending.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            connection(release.url, downloadHosts).use { response ->
                response.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var size = 0L
                        var last = -1
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            size += count
                            if (size > release.size) throw IOException("Release asset exceeds its declared size")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val percent = (100 * size / release.size).toInt()
                            if (percent != last) { progress(percent); last = percent }
                        }
                        if (size != release.size) throw IOException("Incomplete release asset")
                    }
                }
            }
            if (digest.hex() != release.digest)
                throw IOException("Release asset checksum mismatch")
            verifyApk(context, partial, release.version)
            if (!partial.renameTo(destination)) throw IOException("Cannot stage verified APK")
            directory.listFiles()?.filter { it != destination }?.forEach(File::delete)
        } finally {
            partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, file: File, version: ReleaseVersion) {
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val current = manager.getPackageInfo(context.packageName, flags)
        val next = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("Invalid APK")
        val signer = current.signingInfo?.apkContentsSigners?.singleOrNull()
        val history = next.signingInfo?.signingCertificateHistory
        if (next.packageName != current.packageName || next.versionName != version.toString() ||
            next.longVersionCode <= current.longVersionCode || signer == null || history?.contains(signer) != true)
            throw IOException("APK is not a signed upgrade of this installation")
    }

    private class Response(private val connection: HttpURLConnection) : AutoCloseable {
        val inputStream get() = connection.inputStream
        override fun close() = connection.disconnect()
    }

    private fun connection(initial: String, hosts: Set<String>): Response {
        var uri = URI(initial)
        repeat(5) {
            if (uri.scheme != "https" || uri.host !in hosts || uri.userInfo != null || uri.port !in listOf(-1, 443))
                throw IOException("Unexpected download host")
            val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "VoiceKB")
                if (uri.host == "api.github.com") setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                when (val code = connection.responseCode) {
                    200 -> return Response(connection)
                    301, 302, 303, 307, 308 -> uri = uri.resolve(connection.getHeaderField("Location")
                        ?: throw IOException("Missing redirect location"))
                    else -> throw IOException("GitHub returned HTTP $code")
                }
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
            connection.disconnect()
        }
        throw IOException("Too many download redirects")
    }
}
