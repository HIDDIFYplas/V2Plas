// DEST: ui/compose/RemoteAnnouncement.kt
package com.v2ray.ang.ui.compose

import android.os.Build
import android.view.WindowManager
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Fixed links of the V2 Plas project. */
object V2PlasLinks {
    const val TELEGRAM_ID = "@HIDDIFY_plas"
    const val TELEGRAM_URL = "https://t.me/HIDDIFY_plas"
    const val SOURCE_URL = "https://github.com/HIDDIFYplas/V2Plas"
}

/**
 * Message shown when the app opens. It is read from announcement.json in the
 * public repository HIDDIFYplas/V2Plas, so it can be changed without an app update.
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
)

object AnnouncementRepository {
    const val PREF_LAST_SEEN_ID = "pref_v2plas_last_announcement_id"

    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/HIDDIFYplas/V2Plas/main/announcement.json",
        "https://cdn.jsdelivr.net/gh/HIDDIFYplas/V2Plas@main/announcement.json",
    )

    /** Returns the current announcement, or null when offline or the file is invalid. */
    suspend fun fetch(): Announcement? = withContext(Dispatchers.IO) {
        val stamp = System.currentTimeMillis() / 60000L
        for (base in SOURCES) {
            val body = try {
                HttpUtil.getUrlContent(UrlContentRequest(url = "$base?t=$stamp", timeout = 8000))
            } catch (e: Exception) {
                null
            }
            if (body.isNullOrBlank()) continue
            val parsed = JsonUtil.fromJsonSafe(body, Announcement::class.java)
            if (parsed != null) return@withContext parsed
        }
        null
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
            MmkvManager.encodeSettings(AnnouncementRepository.PREF_LAST_SEEN_ID, fetched.id)
        }
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
