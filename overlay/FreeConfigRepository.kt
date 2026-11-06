// DEST: ui/main/FreeConfigRepository.kt
package com.v2ray.ang.ui.main

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.sync.withLock

internal const val V2PLAS_FREE_SUBSCRIPTION_ID = "v2plas_free_remote_group"
internal const val V2PLAS_FREE_SELECTED_NAME_KEY = "v2plas_free_selected_name_v1"

internal data class FreeConfigItem(
    val id: Long,
    val name: String,
    val protocol: String,
    val configData: String,
)

internal data class FreeConfigAd(
    val id: Long,
    val title: String,
    val body: String,
    val imageUrl: String,
    val targetUrl: String,
    val placement: String,
    val priority: Int,
)

internal data class FreeConfigSettings(
    val freeEnabled: Boolean = true,
    val volumeLimitMb: Long = 0L,
    val timeLimitHours: Long = 0L,
    val adsEnabled: Boolean = true,
    val configRefreshMinutes: Long = 60L,
) {
    val volumeLimitBytes: Long
        get() = volumeLimitMb.coerceAtLeast(0L).let {
            if (it > Long.MAX_VALUE / (1024L * 1024L)) Long.MAX_VALUE
            else it * 1024L * 1024L
        }

    val timeLimitMillis: Long
        get() = timeLimitHours.coerceAtLeast(0L).let {
            if (it > Long.MAX_VALUE / 3_600_000L) Long.MAX_VALUE
            else it * 3_600_000L
        }
}

internal data class FreeConfigFetchResult(
    val enabled: Boolean,
    val configs: List<FreeConfigItem>,
    val ads: List<FreeConfigAd>,
    val settings: FreeConfigSettings,
    val fromCache: Boolean,
    val error: String? = null,
)

/**
 * Static feed client and isolated free-profile store.
 * The app only checks the small manifest once per six hours. It downloads the
 * full payload only when the manifest version changes. All UI refresh/connect
 * actions are cache-only and never perform network requests.
 */
internal object FreeConfigRepository {
    private const val CACHE_KEY = "v2plas_free_payload_cache_v3"
    private const val PREVIOUS_CACHE_KEY = "v2plas_free_payload_cache_v2"
    private const val LEGACY_CACHE_KEY = "v2plas_free_configs_cache_v1"
    private const val PREF_LAST_ATTEMPT = "v2plas_feed_last_attempt_v1"
    private const val PREF_LAST_SUCCESS = "v2plas_feed_last_success_v1"
    private const val PREF_ETAG = "v2plas_feed_manifest_etag_v1"
    private const val PREF_VERSION = "v2plas_feed_version_v1"
    private const val REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val MAX_MANIFEST_BYTES = 32 * 1024
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    private data class CachedPayload(
        val configs: List<FreeConfigItem>,
        val ads: List<FreeConfigAd>,
        val settings: FreeConfigSettings,
    )

    fun loadCached(): List<FreeConfigItem> = readCache().configs

    fun cachedResult(error: String? = null): FreeConfigFetchResult {
        val cached = readCache()
        return FreeConfigFetchResult(
            enabled = cached.settings.freeEnabled,
            configs = cached.configs,
            ads = cached.ads,
            settings = cached.settings,
            fromCache = true,
            error = error,
        )
    }

    /**
     * Perform at most one check per six-hour window. A failed attempt is also
     * rate-limited, preventing repeated launches/offline retries from consuming
     * unnecessary static CDN requests. A successful manifest check with an unchanged version
     * never downloads configs.json.
     */
    suspend fun refreshIfDue(nowMillis: Long = System.currentTimeMillis()): FreeConfigFetchResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            refreshMutex.withLock {
                val cached = readCache()
                val hasValidCachedSnapshot = hasValidCacheSnapshot()
                val baseUrl = V2PlasFeedEndpoint.BASE_URL.trim().trimEnd('/')
                if (baseUrl.isBlank()) return@withLock cachedResult("static_feed_url_not_configured")

                val lastAttempt = MmkvManager.decodeSettingsString(PREF_LAST_ATTEMPT)?.toLongOrNull() ?: 0L
                // A clock moved backwards is treated as still inside the cooldown.
                if (lastAttempt > 0L && nowMillis - lastAttempt < REFRESH_INTERVAL_MS) {
                    return@withLock cachedResult("refresh_not_due")
                }

                // Persist before I/O so parallel screens and app relaunches cannot
                // create repeated requests after a timeout or server error.
                MmkvManager.encodeSettings(PREF_LAST_ATTEMPT, nowMillis.toString())
                var manifestConnection: HttpURLConnection? = null
                var payloadConnection: HttpURLConnection? = null
                try {
                    val manifestUrl = URL("$baseUrl/manifest.json")
                    manifestConnection = (manifestUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8_000
                        readTimeout = 10_000
                        instanceFollowRedirects = false
                        setRequestProperty("Accept", "application/json")
                        if (hasValidCachedSnapshot) {
                            MmkvManager.decodeSettingsString(PREF_ETAG)?.takeIf { it.isNotBlank() }?.let {
                                setRequestProperty("If-None-Match", it)
                            }
                        }
                    }

                    val manifestStatus = manifestConnection.responseCode
                    if (manifestStatus == HttpURLConnection.HTTP_NOT_MODIFIED) {
                        if (!hasValidCachedSnapshot) throw IllegalStateException("not_modified_without_cache")
                        manifestConnection.getHeaderField("ETag")?.takeIf { it.isNotBlank() }?.let {
                            MmkvManager.encodeSettings(PREF_ETAG, it)
                        }
                        MmkvManager.encodeSettings(PREF_LAST_SUCCESS, nowMillis.toString())
                        return@withLock cachedResult()
                    }
                    if (manifestStatus != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("manifest_http_$manifestStatus")
                    }

                    val manifestBytes = readBounded(manifestConnection, MAX_MANIFEST_BYTES)
                    val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
                    if (manifest.optInt("format", 0) != 2) throw IllegalStateException("manifest_format_unsupported")
                    val version = manifest.optString("version", "").trim()
                    val payloadName = manifest.optString("payload", "configs.json").trim()
                    val expectedSha256 = manifest.optString("payload_sha256", "").trim().lowercase()
                    val expectedBytes = manifest.optLong("payload_bytes", -1L)
                    if (!version.matches(Regex("[a-fA-F0-9]{64}"))) {
                        throw IllegalStateException("manifest_version_invalid")
                    }
                    if (payloadName != "configs.json") throw IllegalStateException("manifest_payload_invalid")
                    if (!expectedSha256.matches(Regex("[a-f0-9]{64}"))) {
                        throw IllegalStateException("manifest_hash_missing_or_invalid")
                    }
                    if (expectedBytes !in 1..MAX_RESPONSE_BYTES.toLong()) {
                        throw IllegalStateException("manifest_payload_size_invalid")
                    }

                    val newEtag = manifestConnection.getHeaderField("ETag")
                    val cachedVersion = MmkvManager.decodeSettingsString(PREF_VERSION).orEmpty()
                    if (hasValidCachedSnapshot && version.equals(cachedVersion, ignoreCase = true)) {
                        newEtag?.takeIf { it.isNotBlank() }?.let { MmkvManager.encodeSettings(PREF_ETAG, it) }
                        MmkvManager.encodeSettings(PREF_LAST_SUCCESS, nowMillis.toString())
                        return@withLock cachedResult()
                    }

                    payloadConnection = (URL("$baseUrl/$payloadName").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8_000
                        readTimeout = 12_000
                        instanceFollowRedirects = false
                        setRequestProperty("Accept", "application/json")
                    }
                    val payloadStatus = payloadConnection.responseCode
                    if (payloadStatus != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("payload_http_$payloadStatus")
                    }
                    val payloadBytes = readBounded(payloadConnection, MAX_RESPONSE_BYTES)
                    if (payloadBytes.size.toLong() != expectedBytes) throw IllegalStateException("payload_size_mismatch")
                    val actualSha256 = sha256(payloadBytes)
                    if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                        throw IllegalStateException("payload_hash_mismatch")
                    }

                    val root = JSONObject(String(payloadBytes, Charsets.UTF_8))
                    if (root.optInt("feed_format", 0) != 2) throw IllegalStateException("payload_format_unsupported")
                    if (!root.optString("version", "").equals(version, ignoreCase = true)) {
                        throw IllegalStateException("payload_version_mismatch")
                    }
                    val endpointEnabled = root.optBoolean("enabled", false)
                    val settings = parseSettings(root.optJSONObject("settings"))
                    val enabled = endpointEnabled && settings.freeEnabled
                    val configs = parseConfigs(root.optJSONArray("configs"))
                    val ads = parseAds(root.optJSONArray("ads"))
                    val payload = CachedPayload(configs, ads, settings.copy(freeEnabled = enabled))

                    // Commit the verified snapshot first; metadata is updated only
                    // after the payload has passed size, digest, JSON and version checks.
                    MmkvManager.encodeSettings(CACHE_KEY, Gson().toJson(payload))
                    MmkvManager.encodeSettings(PREF_VERSION, version.lowercase())
                    newEtag?.takeIf { it.isNotBlank() }?.let { MmkvManager.encodeSettings(PREF_ETAG, it) }
                    MmkvManager.encodeSettings(PREF_LAST_SUCCESS, nowMillis.toString())
                    FreeConfigFetchResult(enabled, configs, ads, payload.settings, false)
                } catch (e: Exception) {
                    cachedResult(e.message ?: "feed_refresh_failed")
                } finally {
                    manifestConnection?.disconnect()
                    payloadConnection?.disconnect()
                }
            }
        }

    private fun readBounded(connection: HttpURLConnection, maxBytes: Int): ByteArray {
        val declaredLength = connection.getHeaderFieldLong("Content-Length", -1L)
        if (declaredLength > maxBytes) throw IllegalStateException("response_too_large")
        return connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IllegalStateException("response_too_large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun isFreeGuid(guid: String?): Boolean {
        if (guid.isNullOrBlank()) return false
        return try {
            MmkvManager.decodeServerList(V2PLAS_FREE_SUBSCRIPTION_ID).contains(guid)
        } catch (_: Exception) {
            false
        }
    }

    fun selectedDisplayName(guid: String?): String? {
        if (!isFreeGuid(guid)) return null
        return MmkvManager.decodeSettingsString(V2PLAS_FREE_SELECTED_NAME_KEY)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Replaces the single active free profile, leaving personal profiles untouched. */
    suspend fun selectConfig(item: FreeConfigItem): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ensureFreeSubscription()
            MmkvManager.removeServerViaSubid(V2PLAS_FREE_SUBSCRIPTION_ID)
            val imported = AngConfigManager.importBatchConfig(
                item.configData,
                V2PLAS_FREE_SUBSCRIPTION_ID,
                append = false,
            ).first
            if (imported <= 0) return@withContext null
            val guid = MmkvManager.decodeServerList(V2PLAS_FREE_SUBSCRIPTION_ID).firstOrNull()
                ?: return@withContext null
            MmkvManager.encodeSettings(V2PLAS_FREE_SELECTED_NAME_KEY, item.name)
            guid
        }

    fun clearFreeSelection() {
        MmkvManager.removeServerViaSubid(V2PLAS_FREE_SUBSCRIPTION_ID)
        MmkvManager.encodeSettings(V2PLAS_FREE_SELECTED_NAME_KEY, "")
    }

    fun readCurrentSettings(): FreeConfigSettings = readCache().settings

    private fun ensureFreeSubscription() {
        if (MmkvManager.decodeSubscription(V2PLAS_FREE_SUBSCRIPTION_ID) == null) {
            MmkvManager.encodeSubscription(
                V2PLAS_FREE_SUBSCRIPTION_ID,
                SubscriptionItem(
                    remarks = "V2Plas Free (hidden)",
                    url = "",
                    enabled = false,
                    autoUpdate = false,
                ),
            )
        }
    }

    private fun hasValidCacheSnapshot(): Boolean {
        val raw = MmkvManager.decodeSettingsString(CACHE_KEY).orEmpty()
        if (raw.isBlank()) return false
        return try {
            val json = JSONObject(raw)
            json.optJSONArray("configs") != null && json.optJSONObject("settings") != null
        } catch (_: Exception) { false }
    }

    private fun readCache(): CachedPayload {
        val raw = MmkvManager.decodeSettingsString(CACHE_KEY).orEmpty()
        if (raw.isNotBlank()) {
            try {
                val root = JSONObject(raw)
                return CachedPayload(
                    configs = parseConfigs(root.optJSONArray("configs")),
                    ads = parseAds(root.optJSONArray("ads")),
                    settings = parseSettings(root.optJSONObject("settings")),
                )
            } catch (_: Exception) {
                // Continue with the previous cache format for safe migration.
            }
        }
        val previous = MmkvManager.decodeSettingsString(PREVIOUS_CACHE_KEY).orEmpty()
        if (previous.isNotBlank()) {
            try {
                val root = JSONObject(previous)
                val migrated = CachedPayload(
                    configs = parseConfigs(root.optJSONArray("configs")),
                    ads = parseAds(root.optJSONArray("ads")),
                    settings = parseSettings(root.optJSONObject("settings")),
                )
                MmkvManager.encodeSettings(CACHE_KEY, Gson().toJson(migrated))
                return migrated
            } catch (_: Exception) {
                // Continue with the older config-only cache below.
            }
        }
        val legacy = MmkvManager.decodeSettingsString(LEGACY_CACHE_KEY).orEmpty()
        if (legacy.isNotBlank()) {
            // v1 cached Gson objects used Kotlin's `configData` property name;
            // the Worker response uses `config_data`. Support both during migration.
            try {
                val oldItems = Gson().fromJson(legacy, Array<FreeConfigItem>::class.java)
                    ?.filter { it.configData.isNotBlank() }
                    ?.toList()
                if (!oldItems.isNullOrEmpty()) {
                    val migrated = CachedPayload(oldItems, emptyList(), FreeConfigSettings())
                    MmkvManager.encodeSettings(CACHE_KEY, Gson().toJson(migrated))
                    return migrated
                }
            } catch (_: Exception) {
                // Try the raw Worker-style JSON array below.
            }
            try {
                val array = JSONArray(legacy)
                val oldConfigs = parseConfigs(array)
                if (oldConfigs.isNotEmpty()) {
                    val migrated = CachedPayload(oldConfigs, emptyList(), FreeConfigSettings())
                    MmkvManager.encodeSettings(CACHE_KEY, Gson().toJson(migrated))
                    return migrated
                }
            } catch (_: Exception) {
                // Invalid cache is ignored; a later successful API response replaces it.
            }
        }
        return CachedPayload(emptyList(), emptyList(), FreeConfigSettings())
    }

    private fun parseConfigs(array: JSONArray?): List<FreeConfigItem> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                // Worker JSON uses snake_case; the local Gson cache uses Kotlin property names.
                val data = item.optString("config_data", item.optString("configData", "")).trim()
                if (data.isBlank()) continue
                add(
                    FreeConfigItem(
                        id = item.optLong("id", index.toLong()),
                        name = item.optString("name", "کانفیگ ${index + 1}").trim().ifBlank { "کانفیگ ${index + 1}" },
                        protocol = item.optString("protocol", "auto").trim().ifBlank { "auto" },
                        configData = data,
                    ),
                )
            }
        }
    }

    private fun parseAds(array: JSONArray?): List<FreeConfigAd> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.optString("title", "تبلیغ").trim().ifBlank { "تبلیغ" }
                val body = item.optString("body", "").trim()
                val image = safeHttpsUrl(item.optString("image_url", item.optString("imageUrl", "")))
                val target = safeHttpUrl(item.optString("target_url", item.optString("targetUrl", "")))
                if (body.isBlank() && image.isBlank() && target.isBlank()) continue
                add(
                    FreeConfigAd(
                        id = item.optLong("id", index.toLong()),
                        title = title,
                        body = body,
                        imageUrl = image,
                        targetUrl = target,
                        placement = item.optString("placement", "free_entry"),
                        priority = item.optInt("priority", 0),
                    ),
                )
            }
        }.sortedByDescending { it.priority }
    }

    private fun parseSettings(json: JSONObject?): FreeConfigSettings {
        if (json == null) return FreeConfigSettings()
        return FreeConfigSettings(
            freeEnabled = json.optBoolean("free_enabled", json.optBoolean("freeEnabled", true)),
            volumeLimitMb = json.optLong("volume_limit_mb", json.optLong("volumeLimitMb", 0L)).coerceAtLeast(0L),
            timeLimitHours = json.optLong("time_limit_hours", json.optLong("timeLimitHours", 0L)).coerceAtLeast(0L),
            adsEnabled = json.optBoolean("ads_enabled", json.optBoolean("adsEnabled", true)),
            configRefreshMinutes = json.optLong("config_refresh_minutes", json.optLong("configRefreshMinutes", 60L)).coerceAtLeast(1L),
        )
    }

    private fun safeHttpsUrl(value: String): String {
        val text = value.trim()
        return try {
            val uri = URL(text)
            if (uri.protocol.equals("https", ignoreCase = true) && uri.host.isNotBlank()) text else ""
        } catch (_: Exception) { "" }
    }

    private fun safeHttpUrl(value: String): String {
        val text = value.trim()
        return try {
            val uri = URL(text)
            if ((uri.protocol.equals("https", true) || uri.protocol.equals("http", true)) && uri.host.isNotBlank()) text else ""
        } catch (_: Exception) { "" }
    }
}

