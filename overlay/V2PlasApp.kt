// DEST: ui/main/V2PlasApp.kt
package com.v2ray.ang.ui.main

import android.content.Context
import android.content.ContextWrapper
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    fun reset() {
        connectedSince = 0L
        tick = 0L
        rxSession = 0L
        txSession = 0L
        rxSpeed = 0L
        txSpeed = 0L
        histDown.clear()
        histUp.clear()
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

private suspend fun sampleLoop(uid: Int, stats: LiveStats) {
    var lastRx = TrafficStats.getUidRxBytes(uid)
    var lastTx = TrafficStats.getUidTxBytes(uid)
    var lastT = SystemClock.elapsedRealtime()
    while (true) {
        delay(1000L)
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val dt = (now - lastT).coerceAtLeast(1L) / 1000f
        if (rx >= 0 && lastRx >= 0) {
            stats.rxSpeed = ((rx - lastRx) / dt).toLong().coerceAtLeast(0L)
            stats.rxSession = (rx - stats.baseRx).coerceAtLeast(0L)
        }
        if (tx >= 0 && lastTx >= 0) {
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
    }
}

/** Counts time and traffic while the VPN is on. It only samples while the app is on screen. */
@Composable
private fun TrackLiveStats(isRunning: Boolean, stats: LiveStats) {
    val context = LocalContext.current
    LaunchedEffect(isRunning) {
        if (!isRunning) {
            stats.reset()
            return@LaunchedEffect
        }
        val uid = Process.myUid()
        stats.baseRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
        stats.baseTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
        stats.connectedSince = SystemClock.elapsedRealtime()
        stats.tick = stats.connectedSince
        val owner = context.findLifecycleOwner()
        if (owner != null) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sampleLoop(uid, stats)
            }
        } else {
            sampleLoop(uid, stats)
        }
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
    var starsEnabled by remember { mutableStateOf(MmkvManager.decodeSettingsBool(STAR_ANIMATION_PREF, true)) }
    TrackLiveStats(uiState.isRunning, stats)

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
                if (tab == 0) {
                    HomeScreen(
                        mainViewModel = mainViewModel,
                        uiState = uiState,
                        stats = stats,
                        starsEnabled = starsEnabled,
                        onAction = onAction,
                        onNavigate = onNavigate,
                        onOpenServers = { tab = 1 },
                    )
                } else {
                    MainScreen(
                        mainViewModel = mainViewModel,
                        onAction = onAction,
                        onNavigate = onNavigate,
                    )
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                V2PlasBottomNav(
                    selected = tab,
                    onHome = { tab = 0 },
                    onServers = { tab = 1 },
                    onRouting = { onNavigate(MainDestination.Routing) },
                    onSettings = { onNavigate(MainDestination.Settings) },
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
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
    onOpenServers: () -> Unit,
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
    LaunchedEffect(isConnecting) {
        if (isConnecting) {
            delay(30_000L)
            isConnecting = false
        }
    }
    LaunchedEffect(isRunning, guid) {
        if (!isRunning) {
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
            if (isRunning) {
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
                    name = profile?.let { it.remarks.ifBlank { it.server ?: "" } },
                    detail = profile?.let {
                        listOfNotNull(
                            it.configType.name,
                            it.network?.takeIf { n -> n.isNotBlank() },
                            it.security?.takeIf { s -> s.isNotBlank() },
                        ).joinToString(" / ")
                    },
                    country = lastCountry ?: profile?.remarks ?: profile?.server,
                    ping = lastPing ?: ping,
                    onClick = onOpenServers,
                )

                Spacer(modifier = Modifier.height(18.dp))
                PowerButton(isRunning = isRunning, isConnecting = isConnecting) {
                    if (!isRunning && guid != null) isConnecting = true
                    else if (isRunning) isConnecting = false
                    onAction(MainAction.ToggleService)
                }
                Spacer(modifier = Modifier.height(6.dp))

                val view = LocalView.current
                Text(
                    text = when {
                        isConnecting -> "در حال اتصال..."
                        isRunning -> "متصل"
                        else -> "قطع"
                    },
                    color = when {
                        isConnecting -> Cyan
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
                                isConnecting -> Color(0x222CC9E8)
                                isRunning -> Color(0x2212C99A)
                                else -> Color(0x223D5A9B)
                            }
                        )
                        .border(
                            1.dp,
                            when {
                                isConnecting -> Cyan.copy(alpha = 0.48f)
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
                        text = formatDuration(stats.tick - stats.connectedSince),
                        color = TextDim,
                        fontSize = 18.sp,
                    )
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
                } else if (!isRunning) {
                    Text(
                        text = if (guid == null) "ابتدا از بخش سرورها یک کانفیگ اضافه کنید" else if (isConnecting) "درخواست اتصال ارسال شد" else "برای اتصال دکمه را بزنید",
                        color = TextDim,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                if (showCaption && !isConnecting) {
                    Text(
                        text = statusText,
                        color = TextDim,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (isRunning) {
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
                Text(text = flagEmoji(countryCodeFromText(country)) ?: "\uD83C\uDF10", fontSize = 24.sp)
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
                    ).joinToString(" / ").ifBlank { "برای انتخاب سرور بزنید" },
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
    val shape = RoundedCornerShape(20.dp)
    var m: Modifier = modifier
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = listOf(Color(0xB51A2B59), Color(0xC20D1738), Color(0xD009122B)),
                start = Offset.Zero,
                end = Offset(900f, 700f),
            )
        )
        .border(
            1.dp,
            Brush.linearGradient(listOf(Color(0x775B9BFF), Color(0x338B5CF6), Color(0x223DD6E8))),
            shape,
        )
    if (onClick != null) {
        m = m.clickable { onClick() }
    }
    Column(modifier = m.padding(14.dp), content = content)
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
                brush = Brush.sweepGradient(listOf(Accent, Violet, Cyan, Accent), center),
                radius = r * 0.78f,
                center = center,
                style = Stroke(width = 5.dp.toPx()),
            )
        }
        if (isRunning || isConnecting) {
            RunningRing(connecting = isConnecting)
        }
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF16215A), Color(0xFF0A1030))))
                .border(1.dp, Color(0x665B9BFF), CircleShape)
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
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
    onRouting: () -> Unit,
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
                label = "مسیریابی",
                selected = false,
                onClick = onRouting,
            ) { color ->
                Icon(
                    painter = painterResource(R.drawable.ic_routing_24dp),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(26.dp),
                )
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
    val color = if (selected) Accent else NavIdle
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon(color)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
