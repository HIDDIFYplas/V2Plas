// DEST: ui/transfer/TransferActivity.kt
package com.v2ray.ang.ui.transfer

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.helper.QRCodeScannerHelper
import com.v2ray.ang.ui.compose.LocalV2PlasTvFocusIndication
import com.v2ray.ang.ui.compose.V2PlasTvFocusIndication
import com.v2ray.ang.util.QRCodeDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransferActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SINGLE_GUID = "com.v2plas.transfer.SINGLE_GUID"
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    private val isTv: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    }
    private lateinit var scanner: QRCodeScannerHelper
    private var receiver: TvTransferReceiver? = null

    private var qrBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    private var qrExpiry by mutableStateOf(0L)
    private var profiles by mutableStateOf<List<TransferProfile>>(emptyList())
    private var selectedGuids by mutableStateOf<Set<String>>(emptySet())
    private var session by mutableStateOf<TransferSession?>(null)
    private var status by mutableStateOf("برای انتقال، تلویزیون و گوشی را به یک شبکه محلی وصل کنید.")
    private var error by mutableStateOf<String?>(null)
    private var progress by mutableFloatStateOf(0f)
    private var progressLabel by mutableStateOf("")
    private var isBusy by mutableStateOf(false)
    private var resultLabel by mutableStateOf<String?>(null)
    private var history by mutableStateOf<List<String>>(emptyList())
    private var remainingSeconds by mutableIntStateOf(120)
    private val initialGuid by lazy { intent.getStringExtra(EXTRA_SINGLE_GUID) }

    private val localNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (isTv) startTvReceiver() else startPhoneScan()
        } else {
            error = "برای ارتباط مستقیم در شبکه محلی، مجوز شبکه محلی لازم است."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = QRCodeScannerHelper(this)
        if (isTv) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        history = TransferProtocol.readHistory(this)
        setContent {
            androidx.compose.material3.MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalV2PlasTvFocusIndication provides if (isTv) V2PlasTvFocusIndication else null
                ) {
                    TransferScreen(
                        isTv = isTv,
                        qrBitmap = qrBitmap,
                        remainingSeconds = remainingSeconds,
                        profiles = profiles,
                        selectedGuids = selectedGuids,
                        status = status,
                        error = error,
                        progress = progress,
                        progressLabel = progressLabel,
                        isBusy = isBusy,
                        resultLabel = resultLabel,
                        history = history,
                        onScan = { ensureLocalNetworkPermission { startPhoneScan() } },
                        onStartReceiver = { ensureLocalNetworkPermission { startTvReceiver() } },
                        onToggleProfile = { guid ->
                            selectedGuids = if (guid in selectedGuids) selectedGuids - guid else selectedGuids + guid
                        },
                        onSend = ::sendSelected,
                        onBack = { finish() },
                    )
                }
            }
        }
        if (isTv) {
            window.decorView.post { ensureLocalNetworkPermission { startTvReceiver() } }
        } else {
            loadPhoneProfiles()
            window.decorView.post { ensureLocalNetworkPermission { startPhoneScan() } }
        }
    }

    private fun ensureLocalNetworkPermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetworkPermission.launch(LOCAL_NETWORK_PERMISSION)
        } else {
            onGranted()
        }
    }

    private fun loadPhoneProfiles() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { TransferProtocol.loadProfiles() }
            profiles = loaded
            val single = initialGuid
            if (single != null && loaded.any { it.guid == single }) {
                selectedGuids = setOf(single)
            }
            status = if (loaded.isEmpty()) {
                "کانفیگ قابل انتقالی پیدا نشد."
            } else if (single != null) {
                "یک کانفیگ برای اشتراک‌گذاری انتخاب شده است. ابتدا QR تلویزیون را اسکن کنید."
            } else {
                "ابتدا QR نمایش‌داده‌شده روی تلویزیون را اسکن کنید."
            }
        }
    }

    private fun startPhoneScan() {
        error = null
        resultLabel = null
        scanner.launch { raw ->
            if (raw.isNullOrBlank()) {
                error = "اسکن QR انجام نشد."
                return@launch
            }
            try {
                session = TransferProtocol.parseSession(raw)
                status = "تلویزیون شناسایی شد: ${session?.deviceName}. کانفیگ‌ها را انتخاب کنید."
                if (profiles.isEmpty()) loadPhoneProfiles()
            } catch (e: Exception) {
                error = e.message ?: "QR معتبر نیست."
            }
        }
    }

    private fun startTvReceiver() {
        receiver?.close()
        receiver = null
        qrBitmap = null
        error = null
        resultLabel = null
        status = "در حال آماده‌سازی QR امن تلویزیون..."
        lifecycleScope.launch {
            var activeReceiver: TvTransferReceiver? = null
            try {
                val newReceiver = withContext(Dispatchers.IO) { TransferProtocol.createReceiver(this@TransferActivity) }
                activeReceiver = newReceiver
                receiver = newReceiver
                qrExpiry = newReceiver.session.expiresAt
                qrBitmap = withContext(Dispatchers.IO) {
                    QRCodeDecoder.createQRCode(newReceiver.qrText, 800)
                }
                if (qrBitmap == null) throw IllegalStateException("ساخت QR ناموفق بود.")
                status = "QR آماده است. با گوشی آن را اسکن کنید."
                launch {
                    while (System.currentTimeMillis() < newReceiver.session.expiresAt && receiver === newReceiver) {
                        remainingSeconds = ((newReceiver.session.expiresAt - System.currentTimeMillis()) / 1000L)
                            .toInt().coerceAtLeast(0)
                        delay(1_000L)
                    }
                }
                val result = withContext(Dispatchers.IO) { newReceiver.awaitTransfer() }
                TransferProtocol.recordHistory(this@TransferActivity, "in", result.deviceName, result.received, result.duplicates, true)
                resultLabel = "${result.received} کانفیگ جدید دریافت شد؛ ${result.duplicates} مورد تکراری بود."
                status = "انتقال با موفقیت انجام شد."
                history = TransferProtocol.readHistory(this@TransferActivity)
                qrBitmap = null
            } catch (e: Exception) {
                error = e.message ?: "دریافت انتقال ناموفق بود."
                qrBitmap = null
                status = "برای تلاش دوباره، QR جدید بسازید."
            } finally {
                activeReceiver?.close()
                if (receiver === activeReceiver) receiver = null
            }
        }
    }

    private fun sendSelected() {
        val activeSession = session ?: run {
            error = "ابتدا QR تلویزیون را اسکن کنید."
            return
        }
        val selected = profiles.filter { it.guid in selectedGuids }
        if (selected.isEmpty()) {
            error = "حداقل یک کانفیگ انتخاب کنید."
            return
        }
        isBusy = true
        error = null
        resultLabel = null
        progress = 0f
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    TransferProtocol.send(this@TransferActivity, activeSession, selected) { label, value ->
                        runOnUiThread {
                            progressLabel = label
                            progress = value
                        }
                    }
                }
                TransferProtocol.recordHistory(this@TransferActivity, "out", result.deviceName, selected.size, result.duplicates, true)
                resultLabel = "ارسال ${selected.size} کانفیگ به ${result.deviceName} انجام شد."
                status = "${result.received} کانفیگ جدید دریافت شد؛ ${result.duplicates} مورد تکراری بود."
                history = TransferProtocol.readHistory(this@TransferActivity)
                session = null
            } catch (e: Exception) {
                TransferProtocol.recordHistory(this@TransferActivity, "out", activeSession.deviceName, selected.size, 0, false)
                error = e.message ?: "ارسال ناموفق بود."
                status = "برای تلاش مجدد، QR جدید تلویزیون را اسکن کنید."
                history = TransferProtocol.readHistory(this@TransferActivity)
            } finally {
                isBusy = false
            }
        }
    }

    override fun onDestroy() {
        receiver?.close()
        receiver = null
        super.onDestroy()
    }
}

@Composable
private fun TransferScreen(
    isTv: Boolean,
    qrBitmap: android.graphics.Bitmap?,
    remainingSeconds: Int,
    profiles: List<TransferProfile>,
    selectedGuids: Set<String>,
    status: String,
    error: String?,
    progress: Float,
    progressLabel: String,
    isBusy: Boolean,
    resultLabel: String?,
    history: List<String>,
    onScan: () -> Unit,
    onStartReceiver: () -> Unit,
    onToggleProfile: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF070B1F), Color(0xFF101A42), Color(0xFF080D25))))
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("انتقال کانفیگ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (isTv) "دریافت امن روی Android TV" else "ارسال امن از گوشی به تلویزیون", color = Color(0xFF9FB0FF), fontSize = 12.sp)
                }
                TransferAction("بازگشت", onBack, compact = true)
            }
            Spacer(Modifier.height(16.dp))
            TransferPanel {
                if (isTv) {
                    Text("تلویزیون آماده دریافت است", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("گوشی و تلویزیون باید به یک Wi-Fi یا شبکه محلی متصل باشند.", color = Color(0xFFB7C5EF), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "کد QR موقت انتقال V2 Plas",
                            modifier = Modifier
                                .size(250.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White)
                                .padding(10.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("اعتبار QR: ${remainingSeconds} ثانیه", color = Color(0xFFB7C5EF), fontSize = 13.sp)
                    } else {
                        CircularProgressIndicator(color = Color(0xFF65D7FF))
                        Spacer(Modifier.height(12.dp))
                        Text("در حال آماده‌سازی ارتباط محلی...", color = Color(0xFFB7C5EF), fontSize = 13.sp)
                        TransferAction("ساخت QR جدید", onStartReceiver)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(status, color = Color(0xFFB7C5EF), fontSize = 13.sp, textAlign = TextAlign.Center)
                } else {
                    Text("اتصال به تلویزیون", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = Color(0xFFB7C5EF), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    TransferAction(
                        title = if (sessionLabel(status)) "اسکن دوباره QR" else "اسکن QR تلویزیون",
                        onClick = onScan,
                        enabled = !isBusy,
                    )
                    if (sessionLabel(status)) {
                        Spacer(Modifier.height(14.dp))
                        Text("کانفیگ‌های قابل انتقال", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("${selectedGuids.size} مورد انتخاب شده", color = Color(0xFF8FA9F3), fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(250.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(profiles, key = { it.guid }) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0x332C477F))
                                        .border(1.dp, Color(0x334D75C8), RoundedCornerShape(12.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = LocalV2PlasTvFocusIndication.current,
                                            onClick = { onToggleProfile(item.guid) },
                                        )
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    androidx.compose.material3.Checkbox(
                                        checked = item.guid in selectedGuids,
                                        onCheckedChange = { onToggleProfile(item.guid) },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(item.profile.configType.name, color = Color(0xFF9FB0FF), fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        TransferAction(
                            title = "ارسال ${selectedGuids.size} کانفیگ",
                            onClick = onSend,
                            enabled = selectedGuids.isNotEmpty() && !isBusy,
                        )
                    }
                    if (isBusy) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF5ED7F5))
                        Text(progressLabel, color = Color(0xFFB7C5EF), fontSize = 12.sp)
                    }
                }
            }
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                TransferNotice(error, Color(0xFFFF7B87))
            }
            if (resultLabel != null) {
                Spacer(Modifier.height(10.dp))
                TransferNotice(resultLabel, Color(0xFF65E6B3))
            }
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                TransferPanel {
                    Text("آخرین انتقال‌ها", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    history.forEach { item ->
                        Text(item, color = Color(0xFFB7C5EF), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("V2 Plas • انتقال محلی رمزگذاری‌شده", color = Color(0xFF7488C8), fontSize = 10.sp)
        }
    }
}

@Composable
private fun TransferPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xDD1C2F61), Color(0xCC111B3D), Color(0xDD211B4F))))
            .border(1.dp, Brush.linearGradient(listOf(Color(0x889CCBFF), Color(0x445B83D8), Color(0x668B5CF6))), RoundedCornerShape(24.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun TransferAction(
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth(if (compact) 0.28f else 1f)
            .height(if (compact) 38.dp else 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Brush.horizontalGradient(listOf(Color(0xFF2679D8), Color(0xFF7652D8))) else Brush.horizontalGradient(listOf(Color(0xFF34415E), Color(0xFF34415E))))
            .border(1.dp, Color(0x775DABFF), RoundedCornerShape(14.dp))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalV2PlasTvFocusIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = Color.White, fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TransferNotice(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        color = color,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
    )
}

private fun sessionLabel(status: String): Boolean =
    status.contains("تلویزیون شناسایی شد") || status.contains("کانفیگ‌ها را انتخاب کنید")
