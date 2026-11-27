// DEST: ui/compose/RemoteAnnouncement.kt
package com.v2ray.ang.ui.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.main.V2PlasFeedEndpoint
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.HttpURLConnection
import com.google.gson.Gson
import java.io.ByteArrayOutputStream

/** Fixed links of the V2 Plas project. */
object V2PlasLinks {
    const val TELEGRAM_ID = "@HIDDIFY_plas"
    const val TELEGRAM_URL = "https://t.me/HIDDIFY_plas"
    const val SOURCE_URL = "https://github.com/HIDDIFYplas/V2Plas"
}

/**
 * Message shown when the app opens. It is read from announcement.json in the
 * static Cloudflare Pages feed, so it can be changed without an app update.
 */
data class Announcement(
    val enabled: Boolean = false,
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val buttonText: String = "",
    val buttonUrl: String = "",
    val autoCloseSeconds: Int = 0,
    val showOnce: Boolean = false,
    /** Minimum number of hours between displays; 0 preserves display-on-open behavior. */
    val intervalHours: Int = 0,
    /** Optional HTTPS image URL. Blank means text-only announcement. */
    val imageUrl: String = "",
)

object AnnouncementRepository {
    const val PREF_LAST_SEEN_ID = "pref_v2plas_last_announcement_id"
    const val PREF_LAST_SHOWN_AT = "pref_v2plas_announcement_last_shown_at"
    private const val PREF_LAST_FETCH_ATTEMPT = "pref_v2plas_announcement_last_fetch_attempt_v1"
    private const val PREF_LAST_FETCH_SUCCESS = "pref_v2plas_announcement_last_fetch_success_v1"
    private const val PREF_CACHED_JSON = "pref_v2plas_announcement_cache_v1"
    private const val PREF_ETAG_PREFIX = "pref_v2plas_announcement_etag_"
    private const val FETCH_INTERVAL_MS = 12L * 60L * 60L * 1000L
    private const val MAX_ANNOUNCEMENT_BYTES = 64 * 1024
    private val gson = Gson()


    /**
     * Return the locally cached announcement unless the twelve-hour refresh
     * window has elapsed. A failed check is also throttled, so repeated app
     * launches while offline do not repeatedly hit the static Pages endpoint.
     */
    suspend fun fetch(): Announcement? = withContext(Dispatchers.IO) {
        val cachedJson = MmkvManager.decodeSettingsString(PREF_CACHED_JSON).orEmpty()
        val cached = try {
            cachedJson.takeIf { it.isNotBlank() }?.let { gson.fromJson(it, Announcement::class.java) }
        } catch (_: Exception) { null }

        val now = System.currentTimeMillis()
        val baseUrl = V2PlasFeedEndpoint.BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext cached
        val lastAttempt = MmkvManager.decodeSettingsString(PREF_LAST_FETCH_ATTEMPT)?.toLongOrNull() ?: 0L
        if (lastAttempt > 0L && now - lastAttempt < FETCH_INTERVAL_MS) return@withContext cached

        // Store before I/O to prevent duplicate launch requests during failures.
        MmkvManager.encodeSettings(PREF_LAST_FETCH_ATTEMPT, now.toString())
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$baseUrl/announcement.json").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 8_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (cached != null) {
                    MmkvManager.decodeSettingsString(PREF_ETAG_PREFIX + "0")?.takeIf { it.isNotBlank() }?.let {
                        setRequestProperty("If-None-Match", it)
                    }
                }
            }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() }?.let {
                    MmkvManager.encodeSettings(PREF_ETAG_PREFIX + "0", it)
                }
                MmkvManager.encodeSettings(PREF_LAST_FETCH_SUCCESS, now.toString())
                return@withContext cached
            }
            if (status != HttpURLConnection.HTTP_OK) throw IllegalStateException("announcement_http_$status")
            val declared = connection.getHeaderFieldLong("Content-Length", -1L)
            if (declared > MAX_ANNOUNCEMENT_BYTES) throw IllegalStateException("announcement_too_large")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_ANNOUNCEMENT_BYTES) throw IllegalStateException("announcement_too_large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val parsed = gson.fromJson(String(bytes, Charsets.UTF_8), Announcement::class.java)
                ?: throw IllegalStateException("announcement_json_invalid")
            connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() }?.let {
                MmkvManager.encodeSettings(PREF_ETAG_PREFIX + "0", it)
            }
            MmkvManager.encodeSettings(PREF_CACHED_JSON, gson.toJson(parsed))
            MmkvManager.encodeSettings(PREF_LAST_FETCH_SUCCESS, now.toString())
            return@withContext parsed
        } catch (_: Exception) {
            // Keep the last valid announcement; the 12-hour attempt gate avoids
            // retry storms and this is only one static Pages request per window.
        } finally {
            connection?.disconnect()
        }
        cached
    }
}

private fun isSafeLink(url: String): Boolean {
    val u = url.trim().lowercase()
    return u.startsWith("https://") || u.startsWith("http://") || u.startsWith("tg://")
}

/** Put this once inside the main screen. It shows the glass message dialog when there is one. */
@Composable
fun RemoteAnnouncementHost() {
    val context = LocalContext.current
    var announcement by remember { mutableStateOf<Announcement?>(null) }

    LaunchedEffect(Unit) {
        val fetched = AnnouncementRepository.fetch() ?: return@LaunchedEffect
        if (!fetched.enabled) return@LaunchedEffect
        if (fetched.title.isBlank() && fetched.message.isBlank()) return@LaunchedEffect
        if (fetched.showOnce && fetched.id.isNotBlank()) {
            val seen = MmkvManager.decodeSettingsString(AnnouncementRepository.PREF_LAST_SEEN_ID)
            if (seen == fetched.id) return@LaunchedEffect
        }
        val intervalHours = fetched.intervalHours.coerceIn(0, 24 * 365)
        if (intervalHours > 0) {
            // MMKV may return null or an empty value on first launch; treat it as never shown.
            val lastShownAtText = MmkvManager.decodeSettingsString(AnnouncementRepository.PREF_LAST_SHOWN_AT)
            val lastShownAt = lastShownAtText?.trim()?.toLongOrNull() ?: 0L
            val intervalMillis = intervalHours.toLong() * 60L * 60L * 1000L
            if (lastShownAt > 0L && System.currentTimeMillis() - lastShownAt < intervalMillis) {
                return@LaunchedEffect
            }
        }
        if (fetched.showOnce && fetched.id.isNotBlank()) {
            MmkvManager.encodeSettings(AnnouncementRepository.PREF_LAST_SEEN_ID, fetched.id)
        }
        MmkvManager.encodeSettings(
            AnnouncementRepository.PREF_LAST_SHOWN_AT,
            System.currentTimeMillis().toString()
        )
        announcement = fetched
    }

    val current = announcement
    if (current != null) {
        AnnouncementDialog(
            announcement = current,
            onDismiss = { announcement = null },
            onOpenLink = { url ->
                Utils.openUri(context, url)
                announcement = null
            },
        )
    }
}

@Composable
private fun AnnouncementImage(imageUrl: String) {
    val imageBitmap = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, imageUrl) {
        value = if (imageUrl.startsWith("https://", ignoreCase = true)) {
            withContext(Dispatchers.IO) {
                try {
                    val connection = URL(imageUrl).openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = 7000
                    connection.getInputStream().use { stream ->
                        // Avoid downloading unbounded image payloads.
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var totalBytes = 0
                        var count: Int
                        var tooLarge = false
                        while (stream.read(buffer).also { count = it } != -1) {
                            totalBytes += count
                            if (totalBytes > 4 * 1024 * 1024) {
                                tooLarge = true
                                break
                            }
                            output.write(buffer, 0, count)
                        }
                        if (tooLarge) null
                        else {
                            val bytes = output.toByteArray()
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
        } else null
    }
    imageBitmap.value?.let { bitmap ->
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(16.dp))
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun AnnouncementDialog(
    announcement: Announcement,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val total = announcement.autoCloseSeconds.coerceIn(0, 120)
    var remaining by remember(announcement) { mutableStateOf(total) }

    LaunchedEffect(announcement) {
        if (total > 0) {
            while (remaining > 0) {
                delay(1000L)
                remaining -= 1
            }
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Real background blur on Android 12+ (older versions just get the translucent glass card).
        val view = LocalView.current
        SideEffect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val window = (view.parent as? DialogWindowProvider)?.window
                    if (window != null) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        val params = window.attributes
                        params.blurBehindRadius = 40
                        window.attributes = params
                    }
                } catch (e: Throwable) {
                    // blur is optional
                }
            }
        }

        val shape = RoundedCornerShape(28.dp)
        val buttonUrl = announcement.buttonUrl.trim()
        val showButton = buttonUrl.isNotEmpty() && isSafeLink(buttonUrl)
        val buttonText = announcement.buttonText.ifBlank { "عضویت در کانال تلگرام" }

        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xB31B2A6B), Color(0xD90A1028))
                    )
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color(0x995B9BFF), Color(0x4D9B7BFF), Color(0x333DD6E8))
                    ),
                    shape
                )
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (announcement.title.isNotBlank()) {
                Text(
                    text = announcement.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (announcement.imageUrl.isNotBlank()) {
                AnnouncementImage(announcement.imageUrl.trim())
            }
            if (announcement.message.isNotBlank()) {
                Text(
                    text = announcement.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFD6DCFF),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (showButton) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(25.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF3D6BFF), Color(0xFF8B5CF6))
                            )
                        )
                        .clickable { onOpenLink(buttonUrl) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_telegram_24dp),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = buttonText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Text(
                text = "بستن",
                color = Color(0xFF9FB0FF),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )

            if (total > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                val fraction = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x33FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .background(Color(0xFF5B9BFF))
                    )
                }
            }
        }
    }
}
