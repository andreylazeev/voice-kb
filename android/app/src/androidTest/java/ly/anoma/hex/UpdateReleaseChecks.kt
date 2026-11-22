package ly.anoma.hex

import ly.anoma.hex.updates.AppUpdates
import ly.anoma.hex.updates.ReleaseVersion
import org.json.JSONArray
import org.json.JSONObject

internal object UpdateReleaseChecks {
    fun verify() {
        val current = checkNotNull(ReleaseVersion.parse("0.1.17"))
        val filename = "VoiceKB-0.2.0-arm64.apk"
        val url = "https://github.com/andreylazeev/voice-kb/releases/download/v0.2.0/$filename"
        val asset = JSONObject().put("name", filename).put("state", "uploaded")
            .put("browser_download_url", url).put("size", 1024).put("digest", "sha256:" + "a".repeat(64))
        val json = JSONObject().put("tag_name", "v0.2.0").put("assets", JSONArray().put(asset))
        check(AppUpdates.parseRelease(json, current)?.url == url)
        check(AppUpdates.parseRelease(json, checkNotNull(ReleaseVersion.parse("0.2.0"))) == null)
        check(AppUpdates.parseRelease(json.put("prerelease", true), current) == null)
        json.put("prerelease", false)
        asset.put("browser_download_url", "https://example.com/$filename")
        check(runCatching { AppUpdates.parseRelease(json, current) }.isFailure)
        asset.put("browser_download_url", url).put("digest", "a".repeat(64))
        check(runCatching { AppUpdates.parseRelease(json, current) }.isFailure)
    }
}
