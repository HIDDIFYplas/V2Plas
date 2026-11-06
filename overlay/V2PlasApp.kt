// DEST: ui/main/V2PlasApp.kt
package com.v2ray.ang.ui.main

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ---------------------------------------------------------------------------
// V2 Plas main UI: Home (power button, timer, traffic) + bottom navigation.
// The server list, import, routing and settings screens are the original ones.
// ---------------------------------------------------------------------------

private val BgTop = Color(0xFF070B1F)
private val BgBottom = Color(0xFF0B1238)
private val Accent = Color(0xFF4C8DFF)
private val Violet = Color(0xFF8B5CF6)
private val VioletSoft = Color(0xFFB794FF)
private val Cyan = Color(0xFF3DD6E8)
private val TextMain = Color(0xFFE6E9FF)
private val TextDim = Color(0xFF9FB0FF)
private val NavIdle = Color(0xFF7F8CC8)
private val Green = Color(0xFF00C98D)
private const val STAR_ANIMATION_PREF = "v2plas_animated_stars"

private val menuDestinations = listOf(
    MainDestination.Routing,
    MainDestination.Subscriptions,
    MainDestination.PerAppProxy,
    MainDestination.UserAssets,
    MainDestination.Logcat,
    MainDestination.BackupRestore,
    MainDestination.About,
)

/** Live numbers for the Home screen. All values come from Android's own traffic counters. */
private class LiveStats {
    var connectedSince by mutableStateOf(0L)
    var tick by mutableStateOf(0L)
    var rxSession by mutableStateOf(0L)
    var txSession by mutableStateOf(0L)
    var rxSpeed by mutableStateOf(0L)
    var txSpeed by mutableStateOf(0L)
    val histDown = mutableStateListOf<Float>()
    val histUp = mutableStateListOf<Float>()
    var baseRx = 0L
    var baseTx = 0L
    var isFreeSession by mutableStateOf(false)
    var freeRemainingMs by mutableStateOf(0L)
    var freeUsedBytes by mutableStateOf(0L)
    var freeLimitBytes by mutableStateOf(0L)
    var quotaMessage by mutableStateOf<String?>(null)

    fun reset() {
        connectedSince = 0L
        tick = 0L
        rxSession = 0L
        txSession = 0L
        rxSpeed = 0L
        txSpeed = 0L
        histDown.clear()
        histUp.clear()
        isFreeSession = false
        freeRemainingMs = 0L
        freeUsedBytes = 0L
        freeLimitBytes = 0L
        quotaMessage = null
    }
}

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/** Persistent per-connection quota bookkeeping for the isolated free profile. */
internal object FreeQuotaSessionStore {
    private const val ACTIVE = "v2plas_free_session_active_v1"
    private const val START_ELAPSED = "v2plas_free_session_start_elapsed_v1"
    private const val BASE_RX = "v2plas_free_session_base_rx_v1"
    private const val BASE_TX = "v2plas_free_session_base_tx_v1"

    data class Session(val startElapsed: Long, val baseRx: Long, val baseTx: Long, val invalid: Boolean)

    @Synchronized
    fun prepareNewConnection() {
        // Called only after the ad and the additional 10-second delay have completed.
        MmkvManager.encodeSettings(ACTIVE, false)
    }

    @Synchronized
    fun finish() {
        MmkvManager.encodeSettings(ACTIVE, false)
    }

    @Synchronized
    fun startOrResume(uid: Int): Session {
        val now = SystemClock.elapsedRealtime()
        val active = MmkvManager.decodeSettingsBool(ACTIVE, false)
        // Store Long values as decimal strings to use the MMKV string API consistently.
        val savedStart = MmkvManager.decodeSettingsString(START_ELAPSED)?.toLongOrNull() ?: 0L
        val savedRx = MmkvManager.decodeSettingsString(BASE_RX)?.toLongOrNull() ?: -1L
        val savedTx = MmkvManager.decodeSettingsString(BASE_TX)?.toLongOrNull() ?: -1L
        if (active && savedStart > 0L) {
            // elapsedRealtime resets on reboot. A rebooted session is expired rather than renewed.
            if (now < savedStart || savedRx < 0L || savedTx < 0L) {
                return Session(now, -1L, -1L, true)
            }
            return Session(savedStart, savedRx, savedTx, false)
        }
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        MmkvManager.encodeSettings(ACTIVE, true)
        MmkvManager.encodeSettings(START_ELAPSED, now.toString())
        MmkvManager.encodeSettings(BASE_RX, rx.toString())
        MmkvManager.encodeSettings(BASE_TX, tx.toString())
        return Session(now, rx, tx, rx < 0L || tx < 0L)
    }
}

/** Samples continuously while the root UI is composed, including while another tab is visible. */
@Composable
private fun TrackLiveStats(
    isRunning: Boolean,
    isFreeSession: Boolean,
    limits: FreeConfigSettings,
    stats: LiveStats,
    onFreeLimitReached: (String) -> Unit,
) {
    val latestLimitCallback by rememberUpdatedState(onFreeLimitReached)
    LaunchedEffect(
        isRunning,
        isFreeSession,
        if (isFreeSession) limits.timeLimitMillis else 0L,
        if (isFreeSession) limits.volumeLimitBytes else 0L,
    ) {
        if (!isRunning) {
            stats.reset()
            return@LaunchedEffect
        }
        val uid = Process.myUid()
        val session = if (isFreeSession) {
            FreeQuotaSessionStore.startOrResume(uid)
        } else {
            FreeQuotaSessionStore.Session(
                startElapsed = SystemClock.elapsedRealtime(),
                baseRx = TrafficStats.getUidRxBytes(uid),
                baseTx = TrafficStats.getUidTxBytes(uid),
                invalid = false,
            )
        }
        stats.baseRx = session.baseRx
        stats.baseTx = session.baseTx
        stats.connectedSince = session.startElapsed
        stats.tick = session.startElapsed
        stats.isFreeSession = isFreeSession
        stats.freeLimitBytes = limits.volumeLimitBytes
        stats.freeUsedBytes = 0L
        stats.freeRemainingMs = if (limits.timeLimitMillis > 0L) limits.timeLimitMillis else 0L

        if (isFreeSession && session.invalid && limits.volumeLimitBytes > 0L) {
            val message = "آمار ترافیک در دسترس نیست؛ اتصال رایگان برای جلوگیری از عبور از سقف مصرف قطع شد."
            stats.quotaMessage = message
            latestLimitCallback(message)
            return@LaunchedEffect
        }

        var lastRx = TrafficStats.getUidRxBytes(uid)
        var lastTx = TrafficStats.getUidTxBytes(uid)
        var lastT = SystemClock.elapsedRealtime()
        var limitTriggered = false
        while (true) {
            delay(if (isFreeSession) 500L else 1000L)
            val now = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            val dt = (now - lastT).coerceAtLeast(1L) / 1000f
            if (rx >= 0L && lastRx >= 0L) {
                stats.rxSpeed = ((rx - lastRx) / dt).toLong().coerceAtLeast(0L)
                stats.rxSession = (rx - stats.baseRx).coerceAtLeast(0L)
            }
            if (tx >= 0L && lastTx >= 0L) {
                stats.txSpeed = ((tx - lastTx) / dt).toLong().coerceAtLeast(0L)
                stats.txSession = (tx - stats.baseTx).coerceAtLeast(0L)
            }
            lastRx = rx
            lastTx = tx
            lastT = now
            stats.tick = now
            stats.histDown.add(stats.rxSpeed.toFloat())
            if (stats.histDown.size > 40) stats.histDown.removeAt(0)
            stats.histUp.add(stats.txSpeed.toFloat())
            if (stats.histUp.size > 40) stats.histUp.removeAt(0)

            if (isFreeSession && !limitTriggered) {
                val elapsed = (now - session.startElapsed).coerceAtLeast(0L)
                stats.freeRemainingMs = if (limits.timeLimitMillis > 0L) {
                    (limits.timeLimitMillis - elapsed).coerceAtLeast(0L)
                } else 0L
                stats.freeUsedBytes = if (stats.rxSession > Long.MAX_VALUE - stats.txSession) {
                    Long.MAX_VALUE
                } else stats.rxSession + stats.txSession

                val timeReached = limits.timeLimitMillis > 0L && elapsed >= limits.timeLimitMillis
                val countersInvalid = limits.volumeLimitBytes > 0L &&
                    (rx < 0L || tx < 0L || lastRx < 0L || lastTx < 0L ||
                        rx < stats.baseRx || tx < stats.baseTx)
                val volumeReached = limits.volumeLimitBytes > 0L &&
                    stats.freeUsedBytes >= limits.volumeLimitBytes
                if (timeReached || countersInvalid || volumeReached) {
                    limitTriggered = true
                    val message = when {
                        timeReached -> "سهمیه زمانی کانفیگ رایگان تمام شد."
                        countersInvalid -> "شمارنده مصرف در دسترس نیست؛ اتصال رایگان قطع شد."
                        else -> "سهمیه حجم کانفیگ رایگان تمام شد."
                    }
                    stats.quotaMessage = message
                    latestLimitCallback(message)
                    break
                }
            }
        }
    }
}

private suspend fun loadAdImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext null
    var connection: HttpURLConnection? = null
    try {
        val parsed = URL(url)
        if (!parsed.protocol.equals("https", ignoreCase = true)) return@withContext null
        connection = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 7_000
            instanceFollowRedirects = false
        }
        if (connection.responseCode !in 200..299) return@withContext null
        val type = connection.contentType.orEmpty()
        if (!type.startsWith("image/", ignoreCase = true)) return@withContext null
        val declared = connection.contentLengthLong
        if (declared > 4L * 1024L * 1024L) return@withContext null
        val bytes = connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > 4 * 1024 * 1024) return@withContext null
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private fun formatBytes(b: Long): String {
    val v = b.toDouble()
    return when {
        b < 1024L -> "$b B"
        b < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", v / 1024.0)
        b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", v / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", v / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

private fun flagEmoji(code: String?): String? {
    val c = code?.trim()?.uppercase() ?: return null
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return null
    return c.map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
}

private fun countryCodeFromText(value: String?): String? {
    val text = value?.trim()?.lowercase(Locale.ROOT) ?: return null
    if (text.length == 2 && text.all { it in 'a'..'z' }) return text.uppercase(Locale.ROOT)
    val aliases = listOf(
        "iran" to "IR", "ایران" to "IR",
        "netherlands" to "NL", "holland" to "NL", "amsterdam" to "NL", "هلند" to "NL", "آمستردام" to "NL",
        "germany" to "DE", "deutschland" to "DE", "frankfurt" to "DE", "آلمان" to "DE", "فرانکفورت" to "DE",
        "turkey" to "TR", "türkiye" to "TR", "istanbul" to "TR", "ترکیه" to "TR", "استانبول" to "TR",
        "united states" to "US", "usa" to "US", "america" to "US", "new york" to "US", "آمریکا" to "US", "امریکا" to "US",
        "united kingdom" to "GB", "britain" to "GB", "england" to "GB", "london" to "GB", "انگلیس" to "GB", "بریتانیا" to "GB", "لندن" to "GB",
        "uae" to "AE", "dubai" to "AE", "emirates" to "AE", "امارات" to "AE", "دبی" to "AE",
        "canada" to "CA", "toronto" to "CA", "کانادا" to "CA", "تورنتو" to "CA",
        "france" to "FR", "paris" to "FR", "فرانسه" to "FR", "پاریس" to "FR",
        "singapore" to "SG", "سنگاپور" to "SG", "japan" to "JP", "tokyo" to "JP", "ژاپن" to "JP",
        "russia" to "RU", "روسیه" to "RU", "finland" to "FI", "helsinki" to "FI", "فنلاند" to "FI",
        "sweden" to "SE", "stockholm" to "SE", "سوئد" to "SE", "switzerland" to "CH", "zurich" to "CH", "سوئیس" to "CH",
        "austria" to "AT", "vienna" to "AT", "poland" to "PL", "warsaw" to "PL", "italy" to "IT", "rome" to "IT",
        "spain" to "ES", "madrid" to "ES", "ukraine" to "UA", "india" to "IN", "china" to "CN", "hong kong" to "HK",
        "brazil" to "BR", "australia" to "AU", "south korea" to "KR", "korea" to "KR"
    )
    // Only infer a flag from an explicit known country/city alias. Arbitrary
    // two-letter fragments in profile names (for example "HIDDIFY_plas")
    // must not produce a misleading country flag.
    return aliases.firstOrNull { (name, _) -> text.contains(name) }?.second
}

private data class ExitLocation(val ip: String, val country: String, val countryCode: String?, val city: String?)

/** A single lightweight HTTPS lookup while connected; failures never affect the VPN service. */
private suspend fun fetchExitLocation(): ExitLocation? = withContext(Dispatchers.IO) {
    val connection = (URL("https://ipwho.is/").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 3500
        readTimeout = 3500
        useCaches = false
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "V2Plas-Android")
    }
    try {
        if (connection.responseCode !in 200..299) return@withContext null
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        if (!json.optBoolean("success", false)) return@withContext null
        val ip = json.optString("ip").takeIf { it.isNotBlank() && it != "null" } ?: return@withContext null
        ExitLocation(
            ip = ip,
            country = json.optString("country").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
            countryCode = json.optString("country_code").takeIf { it.isNotBlank() && it != "null" },
            city = json.optString("city").takeIf { it.isNotBlank() && it != "null" }
        )
    } finally {
        connection.disconnect()
    }
}

// ---------------------------------------------------------------------------
// Root: Home / Servers + bottom navigation
// ---------------------------------------------------------------------------

@Composable
fun V2PlasApp(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var splashVisible by rememberSaveable { mutableStateOf(true) }
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val stats = remember { LiveStats() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var feedRevision by remember { mutableIntStateOf(0) }

    // One persisted rate-limit gate shared by app launch and the free-list screen.
    // Repeated launches cannot produce extra manifest or payload requests.
    LaunchedEffect(Unit) {
        FreeConfigRepository.refreshIfDue()
        feedRevision++
    }
    var starsEnabled by remember { mutableStateOf(MmkvManager.decodeSettingsBool(STAR_ANIMATION_PREF, true)) }
    val freeSettings = remember(feedRevision) { FreeConfigRepository.readCurrentSettings() }
    val isFreeSelected = remember(uiState.selectedGuid) { FreeConfigRepository.isFreeGuid(uiState.selectedGuid) }

    var connectFlowActive by remember { mutableStateOf(false) }
    var activeAd by remember { mutableStateOf<FreeConfigAd?>(null) }
    var adSecondsRemaining by remember { mutableIntStateOf(0) }
    var connectDelaySeconds by remember { mutableIntStateOf(0) }
    var showingConnectDelay by remember { mutableStateOf(false) }
    var freeStopRequested by remember { mutableStateOf(false) }
    var connectFlowError by remember { mutableStateOf<String?>(null) }
    var quotaMessage by remember { mutableStateOf<String?>(null) }

    val handleUiAction: (MainAction) -> Unit = { action ->
        if (action == MainAction.ToggleService && !uiState.isRunning && isFreeSelected) {
            if (!connectFlowActive) {
                connectFlowActive = true
                connectFlowError = null
                quotaMessage = null
                scope.launch {
                    try {
                        // Connection attempts are cache-only. Network synchronization
                        // is rate-limited (15 min / 5 min after failure) when the free list opens.
                        val payload = FreeConfigRepository.cachedResult()
                        val settings = payload.settings
                        if (!payload.enabled || !settings.freeEnabled) {
                            connectFlowError = "سرویس کانفیگ رایگان از پنل غیرفعال شده است."
                            return@launch
                        }
                        if (settings.adsEnabled) {
                            val ad = payload.ads.firstOrNull {
                                it.placement == "free_entry" || it.placement == "free_configs"
                            }
                            if (ad == null) {
                                connectFlowError = "تبلیغ فعالی برای اتصال رایگان در پنل ثبت نشده است؛ اتصال انجام نشد."
                                return@launch
                            }
                            activeAd = ad
                            for (second in 10 downTo 1) {
                                adSecondsRemaining = second
                                delay(1_000L)
                            }
                            activeAd = null
                            showingConnectDelay = true
                            for (second in 10 downTo 1) {
                                connectDelaySeconds = second
                                delay(1_000L)
                            }
                            showingConnectDelay = false
                        }
                        FreeQuotaSessionStore.prepareNewConnection()
                        freeStopRequested = false
                        onAction(action)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        connectFlowError = "آماده‌سازی اتصال رایگان ناموفق بود: ${e.message ?: "خطای ناشناخته"}"
                    } finally {
                        connectFlowActive = false
                        activeAd = null
                        showingConnectDelay = false
                    }
                }
            }
        } else {
            if (action == MainAction.ToggleService && uiState.isRunning && isFreeSelected) {
                freeStopRequested = true
                FreeQuotaSessionStore.finish()
            }
            onAction(action)
        }
    }

    val freeLimitCallback: (String) -> Unit = { message ->
        if (uiState.isRunning && !freeStopRequested) {
            freeStopRequested = true
            quotaMessage = message
            FreeQuotaSessionStore.finish()
            // Call the original action once; the stop guard prevents a race with
            // a manual disconnect arriving in the same sampling interval.
            onAction(MainAction.ToggleService)
        }
    }
    TrackLiveStats(uiState.isRunning, isFreeSelected, freeSettings, stats, freeLimitCallback)

    DisposableEffect(context) {
        val owner = context.findLifecycleOwner()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                starsEnabled = MmkvManager.decodeSettingsBool(STAR_ANIMATION_PREF, true)
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        delay(900L)
        splashVisible = false
    }

    BackHandler(enabled = tab != 0) { tab = 0 }
    BackHandler(enabled = connectFlowActive) { /* The full-screen ad/wait period is not dismissible. */ }

    if (splashVisible) {
        V2PlasSplashScreen()
    } else {
        Column(modifier = Modifier.fillMaxSize().background(BgTop)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .consumeWindowInsets(WindowInsets.navigationBars)
            ) {
                when (tab) {
                    0 -> HomeScreen(
                        mainViewModel = mainViewModel,
                        uiState = uiState,
                        stats = stats,
                        starsEnabled = starsEnabled,
                        isFreeSelected = isFreeSelected,
                        freeSettings = freeSettings,
                        connectFlowActive = connectFlowActive,
                        quotaMessage = quotaMessage,
                        onDismissQuotaMessage = { quotaMessage = null },
                        onAction = handleUiAction,
                        onNavigate = onNavigate,
                        onOpenServers = { tab = 1 },
                        onOpenFreeConfigs = { tab = 2 },
                    )
                    1 -> MainScreen(
                        mainViewModel = mainViewModel,
                        onAction = onAction,
                        onNavigate = onNavigate,
                    )
                    else -> FreeConfigsPreviewScreen(
                        isRunning = uiState.isRunning,
                        onSelectConfig = { _, guid ->
                            mainViewModel.updateSelectedGuid(guid)
                            quotaMessage = null
                            tab = 0
                        },
                    )
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                V2PlasBottomNav(
                    selected = tab,
                    onHome = { tab = 0 },
                    onServers = {
                        if (uiState.isRunning && isFreeSelected) {
                            quotaMessage = "برای جلوگیری از تغییر ناخواستهٔ اتصال رایگان، ابتدا VPN را قطع کنید."
                        } else {
                            tab = 1
                        }
                    },
                    onFreeConfigs = { tab = 2 },
                    onSettings = { onNavigate(MainDestination.Settings) },
                )
            }
        }
    }

    activeAd?.let { ad ->
        FreeAdDialog(ad = ad, secondsRemaining = adSecondsRemaining)
    }
    if (showingConnectDelay) {
        FreeConnectDelayDialog(secondsRemaining = connectDelaySeconds)
    }
    connectFlowError?.let { message ->
        FreeConnectErrorDialog(message = message, onDismiss = { connectFlowError = null })
    }
}

@Composable
private fun FreeAdDialog(ad: FreeConfigAd, secondsRemaining: Int) {
    val uriHandler = LocalUriHandler.current
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = ad.imageUrl) {
        value = loadAdImage(ad.imageUrl)
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF080D25), Color(0xFF101A42))))
                    .padding(horizontal = 24.dp, vertical = 30.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("تبلیغ", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(18.dp))
                Text(ad.title, color = TextMain, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                if (image != null) {
                    Spacer(Modifier.height(20.dp))
                    Image(
                        bitmap = image!!,
                        contentDescription = ad.title,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(18.dp)),
                    )
                }
                if (ad.body.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(ad.body, color = TextDim, fontSize = 15.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
                }
                if (ad.targetUrl.isNotBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Accent.copy(alpha = 0.22f))
                            .border(1.dp, Accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                            .clickable {
                                try { uriHandler.openUri(ad.targetUrl) } catch (_: Exception) { }
                            }
                            .padding(horizontal = 22.dp, vertical = 12.dp),
                    ) {
                        Text("مشاهده تبلیغ", color = TextMain, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("تبلیغ در حال نمایش است؛ امکان بستن آن وجود ندارد.", color = TextDim, fontSize = 12.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text("پایان نمایش خودکار تا $secondsRemaining ثانیه", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FreeConnectDelayDialog(secondsRemaining: Int) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xF5090F2B)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("آماده‌سازی اتصال رایگان", color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Text("$secondsRemaining", color = Cyan, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold)
                Text("پس از پایان شمارش معکوس، VPN متصل می‌شود.", color = TextDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FreeConnectErrorDialog(message: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("اتصال رایگان انجام نشد", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Text(message, color = TextDim, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Accent.copy(alpha = 0.24f))
                        .clickable(onClick = onDismiss).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("متوجه شدم", color = TextMain, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun FreeConfigsPreviewScreen(
    isRunning: Boolean,
    onSelectConfig: (FreeConfigItem, String) -> Unit,
) {
    var configs by remember { mutableStateOf(FreeConfigRepository.loadCached()) }
    var serviceEnabled by remember { mutableStateOf(FreeConfigRepository.readCurrentSettings().freeEnabled) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("فهرست ذخیره‌شده در حال بارگذاری است...") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectingConfigId by remember { mutableStateOf<Long?>(null) }
    val selectionScope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        loading = true
        // Entry and manual refresh both ask the repository, which rate-limits the
        // manifest check (30 s minimum between manual checks).
        val result = FreeConfigRepository.refreshIfDue(manual = refreshKey > 0)
        serviceEnabled = result.enabled
        configs = result.configs
        status = when {
            !result.enabled -> "سرویس کانفیگ رایگان فعلاً از پنل غیرفعال است."
            result.error == "static_feed_url_not_configured" -> "فهرست ذخیره‌شده نمایش داده می‌شود؛ نشانی CDN هنوز در تنظیمات ساخت ثبت نشده است."
            result.error == "refresh_not_due" && result.configs.isNotEmpty() -> "آخرین فهرست معتبر نمایش داده شد؛ بررسی بعدی چند دقیقهٔ دیگر انجام می‌شود."
            result.error == "refresh_not_due" -> "هنوز فهرستی دریافت نشده؛ بررسی بعدی چند دقیقهٔ دیگر انجام می‌شود."
            result.error != null && result.configs.isNotEmpty() -> "دریافت نسخهٔ جدید انجام نشد؛ آخرین فهرست معتبر حفظ شده است. (${result.error})"
            result.error != null -> "بررسی فهرست انجام نشد و هنوز دادهٔ ذخیره‌شده‌ای وجود ندارد. (${result.error})"
            result.configs.isEmpty() -> "هنوز کانفیگی در پنل مدیریت ثبت نشده است."
            result.fromCache -> "آخرین فهرست ذخیره‌شده نمایش داده شد."
            else -> "فهرست جدید با موفقیت دریافت شد. برای انتخاب، یکی از موارد را لمس کنید."
        }
        loading = false
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(modifier = Modifier.fillMaxSize()) {
            SpaceBackground(modifier = Modifier.fillMaxSize(), animated = true)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "کانفیگ رایگان",
                    color = TextMain,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "برای انتخاب کانفیگ، روی نام آن بزنید",
                    color = TextDim,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(18.dp))
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("وضعیت سرویس", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = if (isRunning) "برای تغییر کانفیگ، ابتدا اتصال فعلی را قطع کنید." else status,
                                color = TextDim,
                                fontSize = 12.sp,
                                lineHeight = 19.sp,
                            )
                        }
                        Text(
                            text = if (loading) "..." else if (serviceEnabled) "فعال" else "غیرفعال",
                            color = if (serviceEnabled) Green else TextDim,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(Accent.copy(alpha = 0.18f))
                            .border(1.dp, Accent.copy(alpha = 0.42f), RoundedCornerShape(13.dp))
                            .clickable(enabled = !loading) { refreshKey++ }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (loading) "در حال بارگذاری..." else "بارگذاری مجدد فهرست",
                            color = TextMain,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                if (configs.isNotEmpty() && serviceEnabled) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "کانفیگ‌های موجود (${configs.size})",
                            color = TextMain,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        configs.forEachIndexed { index, item ->
                            if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x222C5798))
                                    .border(1.dp, Color(0x335B9BFF), RoundedCornerShape(12.dp))
                                    .clickable(enabled = !loading && !isRunning && selectingConfigId == null) {
                                        selectingConfigId = item.id
                                        status = "در حال آماده‌سازی کانفیگ..."
                                        selectionScope.launch {
                                            try {
                                                val guid = FreeConfigRepository.selectConfig(item)
                                                if (guid.isNullOrBlank()) {
                                                    status = "این کانفیگ قابل‌خواندن نیست؛ قالب آن را در پنل بررسی کنید."
                                                } else {
                                                    onSelectConfig(item, guid)
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (e: Exception) {
                                                status = "انتخاب کانفیگ ناموفق بود: ${e.message ?: "خطای ناشناخته"}"
                                            } finally {
                                                selectingConfigId = null
                                            }
                                        }
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = if (isRunning) "ابتدا اتصال را قطع کنید" else "لمس برای انتخاب و رفتن به صفحه اصلی",
                                        color = TextDim,
                                        fontSize = 10.sp,
                                    )
                                }
                                Text(
                                    text = when {
                                        isRunning -> "قفل"
                                        selectingConfigId == item.id -> "در حال انتخاب..."
                                        else -> "انتخاب"
                                    },
                                    color = Cyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                } else if (!loading && serviceEnabled) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "در حال حاضر کانفیگی برای نمایش وجود ندارد.",
                            color = TextDim,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "کانفیگ رایگان در فهرست کانفیگ‌های شخصی نمایش داده نمی‌شود و محدودیت‌های آن روی کانفیگ‌های شخصی اثر ندارد.",
                    color = TextDim.copy(alpha = 0.82f),
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun V2PlasSplashScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SpaceBackground(modifier = Modifier.fillMaxSize(), animated = false)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_v2plas_foreground),
                contentDescription = "V2 Plas",
                modifier = Modifier.size(132.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "V2 Plas",
                color = TextMain,
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = "Freedom in Your Pocket",
                color = TextDim,
                fontSize = 13.sp,
                letterSpacing = 1.1.sp
            )
            Spacer(modifier = Modifier.height(22.dp))
            Box(Modifier.width(76.dp).height(3.dp).clip(CircleShape).background(
                Brush.horizontalGradient(listOf(Accent, Violet, Cyan))
            ))
        }
    }
}

// ---------------------------------------------------------------------------
// Home screen
// ---------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    mainViewModel: MainViewModel,
    uiState: MainUiState,
    stats: LiveStats,
    starsEnabled: Boolean,
    isFreeSelected: Boolean,
    freeSettings: FreeConfigSettings,
    connectFlowActive: Boolean,
    quotaMessage: String?,
    onDismissQuotaMessage: () -> Unit,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
    onOpenServers: () -> Unit,
    onOpenFreeConfigs: () -> Unit,
) {
    val isRunning = uiState.isRunning
    val guid = uiState.selectedGuid
    val profile = remember(guid, uiState.status) {
        guid?.let { MmkvManager.decodeServerConfig(it) }
    }
    val ping = remember(guid, uiState.status) {
        guid?.let { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis }
    }
    var lastCountry by remember(guid) { mutableStateOf<String?>(null) }
    var lastPing by remember(guid) { mutableStateOf(ping?.takeIf { it > 0L }) }
    var isConnecting by rememberSaveable { mutableStateOf(false) }
    var exitIp by remember(guid) { mutableStateOf<String?>(null) }
    var exitCountry by remember(guid) { mutableStateOf<String?>(null) }
    var exitCountryCode by remember(guid) { mutableStateOf<String?>(null) }
    var exitCity by remember(guid) { mutableStateOf<String?>(null) }
    var exitLookupLoading by remember(guid) { mutableStateOf(false) }

    LaunchedEffect(uiState.status) {
        val st = uiState.status
        if (st is MainStatus.ConnectionTest) {
            if (st.result.delayMillis >= 0L) lastCountry = st.result.country
            lastPing = st.result.delayMillis.takeIf { it != 0L }
        }
    }
    LaunchedEffect(isRunning) {
        if (isRunning) isConnecting = false
    }
    LaunchedEffect(isConnecting, connectFlowActive) {
        // Do not consume the connection-indicator timeout while the ad and 10-second
        // pre-connect delay are still running.
        if (isConnecting && !connectFlowActive) {
            delay(30_000L)
            isConnecting = false
        }
    }
    LaunchedEffect(isRunning, guid, isFreeSelected) {
        // Do not perform an external IP/location lookup for the free profile:
        // its connection card intentionally contains no technical exit details.
        if (!isRunning || isFreeSelected) {
            exitIp = null
            exitCountry = null
            exitCountryCode = null
            exitCity = null
            exitLookupLoading = false
        } else {
            exitLookupLoading = true
            val info = try {
                fetchExitLocation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (isRunning && !isFreeSelected) {
                exitIp = info?.ip
                exitCountry = info?.country
                exitCountryCode = info?.countryCode
                exitCity = info?.city
            }
            exitLookupLoading = false
        }
    }
    val statusText = remember(uiState.status) { mainViewModel.formatStatus(uiState.status) }
    val showCaption = uiState.status !is MainStatus.Disconnected && uiState.status !is MainStatus.Connected

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier = Modifier.fillMaxSize()) {
            SpaceBackground(modifier = Modifier.fillMaxSize(), animated = starsEnabled)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HomeHeader(onNavigate = onNavigate)
                Spacer(modifier = Modifier.height(10.dp))

                ServerCard(
                    name = if (isFreeSelected) {
                        FreeConfigRepository.selectedDisplayName(guid) ?: "کانفیگ رایگان"
                    } else profile?.let { it.remarks.ifBlank { it.server ?: "" } },
                    // Free profiles deliberately expose no endpoint/protocol/network details.
                    detail = if (isFreeSelected) null else profile?.let {
                        listOfNotNull(
                            it.configType.name,
                            it.network?.takeIf { n -> n.isNotBlank() },
                            it.security?.takeIf { s -> s.isNotBlank() },
                        ).joinToString(" / ")
                    },
                    country = if (isFreeSelected) null else lastCountry ?: profile?.remarks ?: profile?.server,
                    ping = if (isFreeSelected) null else lastPing ?: ping,
                    onClick = if (isFreeSelected) onOpenFreeConfigs else onOpenServers,
                )

                Spacer(modifier = Modifier.height(18.dp))
                PowerButton(isRunning = isRunning, isConnecting = isConnecting || connectFlowActive) {
                    if (!isRunning && guid != null) isConnecting = true
                    else if (isRunning) isConnecting = false
                    onAction(MainAction.ToggleService)
                }
                Spacer(modifier = Modifier.height(6.dp))

                val view = LocalView.current
                Text(
                    text = when {
                        connectFlowActive -> "در حال نمایش تبلیغ / آماده‌سازی اتصال..."
                        isConnecting -> "در حال اتصال..."
                        isRunning -> "متصل"
                        else -> "قطع"
                    },
                    color = when {
                        connectFlowActive || isConnecting -> Cyan
                        isRunning -> TextMain
                        else -> TextDim
                    },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            when {
                                connectFlowActive || isConnecting -> Color(0x222CC9E8)
                                isRunning -> Color(0x2212C99A)
                                else -> Color(0x223D5A9B)
                            }
                        )
                        .border(
                            1.dp,
                            when {
                                connectFlowActive || isConnecting -> Cyan.copy(alpha = 0.48f)
                                isRunning -> Green.copy(alpha = 0.42f)
                                else -> Accent.copy(alpha = 0.28f)
                            },
                            RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            if (isRunning) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onAction(MainAction.TestCurrentServer)
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                )
                if (isRunning && stats.connectedSince > 0L) {
                    Text(
                        text = if (isFreeSelected && stats.isFreeSession) {
                            if (freeSettings.timeLimitMillis > 0L) formatDuration(stats.freeRemainingMs)
                            else "بدون محدودیت زمانی"
                        } else formatDuration(stats.tick - stats.connectedSince),
                        color = TextDim,
                        fontSize = 18.sp,
                    )
                    if (isFreeSelected && stats.isFreeSession) {
                        Text("زمان باقی‌مانده", color = TextDim, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(5.dp))
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text("سهمیه کانفیگ رایگان", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "مصرف مجموع دانلود و آپلود: ${formatBytes(stats.freeUsedBytes)}",
                                color = TextDim,
                                fontSize = 13.sp,
                            )
                            Text(
                                text = if (freeSettings.volumeLimitBytes > 0L) {
                                    "از ${formatBytes(freeSettings.volumeLimitBytes)}"
                                } else "سقف حجم: بدون محدودیت",
                                color = TextDim,
                                fontSize = 12.sp,
                            )
                            if (freeSettings.timeLimitMillis > 0L) {
                                Text(
                                    text = "زمان مجاز: ${freeSettings.timeLimitHours} ساعت",
                                    color = TextDim,
                                    fontSize = 12.sp,
                                )
                            } else {
                                Text("سقف زمان: بدون محدودیت", color = TextDim, fontSize = 12.sp)
                            }
                        }
                    } else {
                        Text(
                            text = when {
                                lastPing != null && lastPing!! > 0L -> "پینگ: ${lastPing} ms  •  برای تست مجدد لمس کنید"
                                lastPing != null && lastPing!! < 0L -> "پینگ ناموفق  •  برای تلاش دوباره لمس کنید"
                                else -> "برای اندازه‌گیری پینگ لمس کنید"
                            },
                            color = if (lastPing != null && lastPing!! > 0L) Green else TextDim,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onAction(MainAction.TestCurrentServer)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                } else if (!isRunning) {
                    Text(
                        text = if (guid == null) "ابتدا از بخش سرورها یک کانفیگ اضافه کنید" else if (isConnecting) "درخواست اتصال ارسال شد" else "برای اتصال دکمه را بزنید",
                        color = TextDim,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                if (showCaption && !isConnecting && !connectFlowActive) {
                    Text(
                        text = statusText,
                        color = TextDim,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (isRunning && !isFreeSelected) {
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("IP خروجی", color = TextDim, fontSize = 11.sp)
                                Text(
                                    text = exitIp ?: if (exitLookupLoading) "در حال دریافت..." else "در دسترس نیست",
                                    color = TextMain,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("موقعیت خروجی", color = TextDim, fontSize = 11.sp)
                                Text(
                                    text = (flagEmoji(exitCountryCode)?.plus(" ").orEmpty() + listOfNotNull(exitCity, exitCountry).joinToString("، ")).ifBlank {
                                        if (exitLookupLoading) "در حال بررسی..." else "نامشخص"
                                    },
                                    color = TextMain,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                quotaMessage?.let { message ->
                    Spacer(modifier = Modifier.height(10.dp))
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = message,
                                color = TextMain,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "بستن",
                                color = Cyan,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { onDismissQuotaMessage() }.padding(6.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TrafficCard(
                        modifier = Modifier.weight(1f),
                        up = false,
                        label = "دانلود",
                        total = formatBytes(stats.rxSession),
                        speed = formatBytes(stats.rxSpeed) + "/s",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    TrafficCard(
                        modifier = Modifier.weight(1f),
                        up = true,
                        label = "آپلود",
                        total = formatBytes(stats.txSession),
                        speed = formatBytes(stats.txSpeed) + "/s",
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SpeedWave(
                        down = stats.histDown,
                        up = stats.histUp,
                        active = isRunning,
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HomeHeader(onNavigate: (MainDestination) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF203D86), Color(0xFF301E69))))
                .border(1.dp, Color(0x775B9BFF), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_v2plas_foreground),
                contentDescription = null,
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = "V2 Plas",
                color = TextMain,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.2.sp,
            )
            Text(
                text = "اتصال امن و پایدار",
                color = TextDim.copy(alpha = 0.82f),
                fontSize = 10.sp,
                letterSpacing = 0.2.sp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Box {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Color(0x442A4E9B), Color(0x443F286F))))
                    .border(1.dp, Color(0x445B9BFF), RoundedCornerShape(14.dp))
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val sw = 2.5.dp.toPx()
                    for (i in 0..2) {
                        val y = size.height * (0.25f + 0.25f * i)
                        drawLine(
                            color = TextMain,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = sw,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menuDestinations.forEach { dest ->
                    DropdownMenuItem(
                        text = { Text(stringResource(dest.labelRes)) },
                        onClick = {
                            menuOpen = false
                            onNavigate(dest)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    name: String?,
    detail: String?,
    country: String?,
    ping: Long?,
    onClick: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0x331B2A6B))
                    .border(1.dp, Color(0x445B9BFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = flagEmoji(countryCodeFromText(country)) ?: "\uD83C\uDF10",
                    fontSize = 36.sp,
                    lineHeight = 38.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer(scaleX = 1.12f, scaleY = 1.12f),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name?.takeIf { it.isNotBlank() } ?: "سروری انتخاب نشده",
                    color = TextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = listOfNotNull(
                        country?.takeIf { countryCodeFromText(it) != null },
                        detail?.takeIf { it.isNotBlank() }
                    ).joinToString(" / ").ifBlank {
                        if (name.isNullOrBlank()) "برای انتخاب سرور بزنید" else "سرور انتخاب‌شده"
                    },
                    color = TextDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            if (ping != null && ping != 0L) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x221B2A6B))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (ping > 0) "$ping ms" else "خطا",
                        color = if (ping > 0 && ping <= 400) Green else Color(0xFFFFB454),
                        fontSize = 12.sp,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = "\u203A", color = TextDim, fontSize = 22.sp)
        }
    }
}

@Composable
private fun TrafficCard(
    modifier: Modifier,
    up: Boolean,
    label: String,
    total: String,
    speed: String,
) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (up) Color(0x332D1D70) else Color(0x33204486))
                    .border(1.dp, (if (up) VioletSoft else Cyan).copy(alpha = 0.28f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ArrowGlyph(up = up, color = if (up) VioletSoft else Cyan)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = total,
                    color = TextMain,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(Color(0x223D5B9C))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        ) {
            Text(text = speed, color = if (up) VioletSoft else Cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    var cardModifier: Modifier = modifier
        .shadow(elevation = 16.dp, shape = shape, clip = false)
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = listOf(
                    Color(0xD9284778),
                    Color(0xC8172852),
                    Color(0xD00A1533),
                ),
                start = Offset.Zero,
                end = Offset(850f, 720f),
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(
                    Color(0xAA9CCBFF),
                    Color(0x665B83D8),
                    Color(0x668B5CF6),
                    Color(0x553DD6E8),
                )
            ),
            shape = shape,
        )
    if (onClick != null) {
        cardModifier = cardModifier.clickable { onClick() }
    }
    Box(modifier = cardModifier) {
        // Subtle specular reflection gives the glass surface depth without assets.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x25FFFFFF), Color(0x08FFFFFF), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(0.7.dp, Color(0x18FFFFFF), shape)
        )
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun SpaceBackground(modifier: Modifier = Modifier, animated: Boolean = true) {
    val stars = remember {
        val r = Random(7)
        List(48) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    val phase = if (animated) {
        val transition = rememberInfiniteTransition(label = "star-drift")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 18000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "star-phase"
        )
        value
    } else 0f
    Canvas(modifier = modifier) {
        drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF060A1B), Color(0xFF0B1238), Color(0xFF0A102D))))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x443B75D8), Color(0x1A3B75D8), Color.Transparent),
                center = Offset(size.width * 0.12f, size.height * 0.24f),
                radius = size.width * 0.72f,
            ),
            radius = size.width * 0.72f,
            center = Offset(size.width * 0.12f, size.height * 0.24f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x333D2388), Color(0x183D2388), Color.Transparent),
                center = Offset(size.width * 0.90f, size.height * 0.38f),
                radius = size.width * 0.64f,
            ),
            radius = size.width * 0.64f,
            center = Offset(size.width * 0.90f, size.height * 0.38f),
        )
        val glowCenter = Offset(size.width * 0.52f, size.height * 0.96f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Violet.copy(alpha = 0.28f), Color.Transparent),
                center = glowCenter,
                radius = size.width * 0.92f,
            ),
            radius = size.width * 0.92f,
            center = glowCenter,
        )
        // A restrained horizon glow gives the background depth without heavy assets.
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, Color(0x665B7FFF), Color(0x443DD6E8), Color.Transparent)
            ),
            start = Offset(0f, size.height * 0.70f),
            end = Offset(size.width, size.height * 0.70f),
            strokeWidth = 1.1.dp.toPx(),
        )
        // Layered low-poly mountain silhouettes; drawn in Canvas to avoid bitmap assets.
        fun mountain(points: List<Offset>, color: Color) {
            val path = Path().apply {
                moveTo(points.first().x * size.width, points.first().y * size.height)
                points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, color)
        }
        mountain(listOf(Offset(0f, .82f), Offset(.16f, .69f), Offset(.28f, .79f), Offset(.43f, .64f), Offset(.60f, .80f), Offset(.78f, .68f), Offset(1f, .82f)), Color(0xFF101B4B))
        mountain(listOf(Offset(0f, .88f), Offset(.13f, .77f), Offset(.29f, .84f), Offset(.49f, .72f), Offset(.67f, .87f), Offset(.83f, .75f), Offset(1f, .88f)), Color(0xFF11143F))
        mountain(listOf(Offset(0f, .94f), Offset(.19f, .84f), Offset(.34f, .91f), Offset(.52f, .82f), Offset(.71f, .92f), Offset(.89f, .83f), Offset(1f, .94f)), Color(0xFF17143F))
        stars.forEachIndexed { index, (x, y, s) ->
            val drift = if (animated) kotlin.math.sin((phase * 6.28318f + y * 9f).toDouble()).toFloat() * 0.0022f else 0f
            val twinkle = if (animated) 0.78f + 0.22f * kotlin.math.sin((phase * 6.28318f * 1.4f + index).toDouble()).toFloat() else 1f
            val starY = if (animated) (y + phase * 0.014f) % 0.76f else y * 0.76f
            drawCircle(
                color = Color.White.copy(alpha = (0.12f + 0.30f * s) * twinkle),
                radius = (0.55f + 1.05f * s) * density,
                center = Offset(((x + drift).coerceIn(0f, 1f)) * size.width, starY * size.height),
            )
        }
    }
}

@Composable
private fun PowerButton(isRunning: Boolean, isConnecting: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val buttonScale by animateFloatAsState(
        targetValue = when {
            isConnecting -> 0.975f
            isRunning -> 1.015f
            else -> 1f
        },
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "powerButtonScale",
    )
    Box(modifier = Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val glow = when {
                isConnecting -> Cyan.copy(alpha = 0.52f)
                isRunning -> Accent.copy(alpha = 0.45f)
                else -> Color(0xFF2A3160).copy(alpha = 0.25f)
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow, Color.Transparent),
                    center = center,
                    radius = r,
                ),
                radius = r,
                center = center,
            )
            drawCircle(
                color = Color(0x337FAEFF),
                radius = r * 0.805f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                brush = Brush.sweepGradient(listOf(Accent, Violet, Cyan, Accent), center),
                radius = r * 0.78f,
                center = center,
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        if (isRunning || isConnecting) {
            RunningRing(connecting = isConnecting)
        }
        Box(
            modifier = Modifier
                .size(132.dp)
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                }
                .shadow(20.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF263D78), Color(0xFF111D49), Color(0xFF090F2B))
                    )
                )
                .border(1.dp, Color(0x779CCBFF), CircleShape)
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.88f)
                    .border(1.dp, Color(0x22FFFFFF), CircleShape)
            )
            PowerGlyph(color = when {
                isConnecting -> Cyan
                isRunning -> Accent
                else -> NavIdle
            })
        }
    }
}

@Composable
private fun RunningRing(connecting: Boolean) {
    val transition = rememberInfiniteTransition(label = "ring")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (connecting) 1500 else 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = if (connecting) 1.035f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (connecting) 850 else 2200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Canvas(modifier = Modifier.size(210.dp)) {
        val r = size.minDimension / 2f * 0.78f * pulse
        rotate(degrees = angle) {
            drawCircle(
                brush = Brush.sweepGradient(listOf(Accent, Violet, Cyan, Accent), center = center),
                radius = r,
                center = center,
                style = Stroke(width = if (connecting) 7.dp.toPx() else 5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun PowerGlyph(color: Color) {
    Canvas(modifier = Modifier.size(56.dp)) {
        val stroke = 5.dp.toPx()
        val r = size.minDimension * 0.34f
        val shift = 3.dp.toPx()
        drawArc(
            color = color,
            startAngle = -50f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r + shift),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(center.x, center.y - r - 2.dp.toPx()),
            end = Offset(center.x, center.y - 2.dp.toPx()),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ArrowGlyph(up: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(26.dp)) {
        val sw = 2.6.dp.toPx()
        val cx = size.width / 2f
        val h = size.height
        fun y(t: Float): Float = if (up) h - t else t
        val a = size.width * 0.24f
        drawLine(
            color = color,
            start = Offset(cx, y(h * 0.12f)),
            end = Offset(cx, y(h * 0.72f)),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(cx - a, y(h * 0.72f - a)),
            end = Offset(cx, y(h * 0.72f)),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(cx + a, y(h * 0.72f - a)),
            end = Offset(cx, y(h * 0.72f)),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(cx - a * 1.3f, y(h * 0.92f)),
            end = Offset(cx + a * 1.3f, y(h * 0.92f)),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun SpeedWave(
    down: List<Float>,
    up: List<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val maxV = maxOf(1f, down.maxOrNull() ?: 0f, up.maxOrNull() ?: 0f)

        fun buildPath(data: List<Float>): Path {
            val p = Path()
            val n = data.size
            if (n < 2) {
                p.moveTo(0f, h * 0.5f)
                p.lineTo(w, h * 0.5f)
                return p
            }
            val step = w / (n - 1)
            fun yOf(v: Float): Float = h * 0.85f - (v / maxV) * h * 0.7f
            p.moveTo(0f, yOf(data[0]))
            for (i in 1 until n) {
                val x0 = (i - 1) * step
                val x1 = i * step
                val cx = (x0 + x1) / 2f
                p.cubicTo(cx, yOf(data[i - 1]), cx, yOf(data[i]), x1, yOf(data[i]))
            }
            return p
        }

        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val alpha = if (active) 1f else 0.35f
        drawPath(
            path = buildPath(up),
            brush = Brush.horizontalGradient(
                listOf(Violet.copy(alpha = 0.3f * alpha), VioletSoft.copy(alpha = alpha))
            ),
            style = stroke,
        )
        drawPath(
            path = buildPath(down),
            brush = Brush.horizontalGradient(
                listOf(Accent.copy(alpha = 0.3f * alpha), Cyan.copy(alpha = alpha))
            ),
            style = stroke,
        )
    }
}

// ---------------------------------------------------------------------------
// Bottom navigation
// ---------------------------------------------------------------------------

@Composable
private fun V2PlasBottomNav(
    selected: Int,
    onHome: () -> Unit,
    onServers: () -> Unit,
    onFreeConfigs: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A1030), Color(0xFF070B1F))))
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x335B9BFF))
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            NavItem(
                modifier = Modifier.weight(1f),
                label = "خانه",
                selected = selected == 0,
                onClick = onHome,
            ) { color ->
                Canvas(modifier = Modifier.size(26.dp)) {
                    val w = size.width
                    val h = size.height
                    val p = Path()
                    p.moveTo(w * 0.5f, h * 0.12f)
                    p.lineTo(w * 0.92f, h * 0.5f)
                    p.lineTo(w * 0.8f, h * 0.5f)
                    p.lineTo(w * 0.8f, h * 0.88f)
                    p.lineTo(w * 0.6f, h * 0.88f)
                    p.lineTo(w * 0.6f, h * 0.62f)
                    p.lineTo(w * 0.4f, h * 0.62f)
                    p.lineTo(w * 0.4f, h * 0.88f)
                    p.lineTo(w * 0.2f, h * 0.88f)
                    p.lineTo(w * 0.2f, h * 0.5f)
                    p.lineTo(w * 0.08f, h * 0.5f)
                    p.close()
                    drawPath(path = p, color = color)
                }
            }
            NavItem(
                modifier = Modifier.weight(1f),
                label = "سرورها",
                selected = selected == 1,
                onClick = onServers,
            ) { color ->
                Canvas(modifier = Modifier.size(26.dp)) {
                    val w = size.width
                    val h = size.height
                    for (i in 0..2) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(w * 0.1f, h * (0.12f + 0.3f * i)),
                            size = Size(w * 0.8f, h * 0.2f),
                            cornerRadius = CornerRadius(6f, 6f),
                        )
                    }
                }
            }
            NavItem(
                modifier = Modifier.weight(1f),
                label = "کانفیگ رایگان",
                selected = selected == 2,
                onClick = onFreeConfigs,
            ) { color ->
                Canvas(modifier = Modifier.size(26.dp)) {
                    val w = size.width
                    val h = size.height
                    val stroke = 2.dp.toPx()
                    for (i in 0..2) {
                        val y = h * (0.16f + 0.29f * i)
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(w * 0.12f, y),
                            size = Size(w * 0.76f, h * 0.19f),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            style = Stroke(width = stroke),
                        )
                        drawCircle(
                            color = color,
                            radius = 1.2.dp.toPx(),
                            center = Offset(w * 0.24f, y + h * 0.095f),
                        )
                    }
                }
            }
            NavItem(
                modifier = Modifier.weight(1f),
                label = "تنظیمات",
                selected = false,
                onClick = onSettings,
            ) { color ->
                Icon(
                    painter = painterResource(R.drawable.ic_settings_24dp),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val color by animateColorAsState(
        targetValue = if (selected) Color(0xFF75B5FF) else NavIdle,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "navItemColor",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.10f else 1f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "navItemScale",
    )
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
                .then(
                    if (selected) Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x1C5B9BFF))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                    else Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                ),
            contentAlignment = Alignment.Center,
        ) {
            icon(color)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
