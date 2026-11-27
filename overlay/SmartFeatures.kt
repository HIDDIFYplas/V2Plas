// DEST: ui/main/SmartFeatures.kt
package com.v2ray.ang.ui.main

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.dto.entities.ServersCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Runtime controller for the Home power button's manual/automatic mode. */
internal object V2PlasSmartSelect {
    private val switchMutex = Mutex()
    private val switchInProgress = AtomicBoolean(false)
    private val userStopRequested = AtomicBoolean(false)

    fun requestUserStop() {
        userStopRequested.set(true)
    }

    fun isSwitchInProgress(): Boolean = switchInProgress.get()

    private enum class SwitchOutcome {
        CONNECTED,
        CANDIDATE_REJECTED,
        RECOVERY_FAILED,
        FOLDER_CHANGED,
        CANCELLED_BY_USER,
    }

    suspend fun run(
        viewModel: MainViewModel,
        onServiceToggle: () -> Unit,
        onMessage: (String) -> Unit,
        onProgress: (String) -> Unit,
    ) {
        userStopRequested.set(false)
        val initial = viewModel.uiState.value
        if (initial.isTesting) {
            onMessage("یک تست پینگ دیگر در حال اجراست. ابتدا اجازه بدهید تمام شود.")
            return
        }
        val groupId = initial.selectedGroupId
        val groupFlow = viewModel.serverGroupState(groupId)
        if (groupFlow.value.servers.isEmpty()) {
            onMessage("پوشه انتخاب‌شده کانفیگی برای بررسی ندارد.")
            return
        }

        onProgress("در حال بررسی کانفیگ‌های پوشه انتخاب‌شده")
        viewModel.testAllRealPingForGroup(groupId)

        // Never accept a result left over from an earlier run.
        val resetObserved = withTimeoutOrNull(8_000L) {
            groupFlow.first { state ->
                state.servers.isNotEmpty() && state.servers.all { it.testDelayMillis == 0L }
            }
        }
        if (resetObserved == null) {
            onMessage("آماده‌سازی تست پینگ کامل نشد؛ اتصال قبلی حفظ شد.")
            return
        }

        val attempted = mutableSetOf<String>()
        val rejected = mutableSetOf<String>()
        var firstConnected: ServersCache? = null

        // Test results arrive independently. Try every valid result in ascending ping order
        // until one really connects; a failed first result must not terminate discovery.
        while (firstConnected == null) {
            if (viewModel.uiState.value.selectedGroupId != groupId) {
                onMessage("پوشه انتخاب‌شده تغییر کرد؛ انتخاب خودکار متوقف شد.")
                return
            }
            val candidate = groupFlow.value.servers
                .asSequence()
                .filter { it.testDelayMillis > 0L && it.guid !in attempted }
                .minByOrNull { it.testDelayMillis }

            if (candidate == null) {
                val nextState = withTimeoutOrNull(75_000L) {
                    combine(groupFlow, viewModel.uiState) { group, ui -> group.servers to ui }
                        .first { (servers, ui) ->
                            ui.selectedGroupId != groupId || !ui.isTesting ||
                                servers.any { it.testDelayMillis > 0L && it.guid !in attempted }
                        }
                }
                if (nextState == null) break
                if (nextState.second.selectedGroupId != groupId) {
                    onMessage("پوشه انتخاب‌شده تغییر کرد؛ انتخاب خودکار متوقف شد.")
                    return
                }
                if (!nextState.second.isTesting && nextState.first.none {
                        it.testDelayMillis > 0L && it.guid !in attempted
                    }) break
                continue
            }

            attempted += candidate.guid
            onProgress("در حال اتصال به ${candidate.profile.remarks}")
            when (switchTo(viewModel, candidate.guid, groupId, onServiceToggle)) {
                SwitchOutcome.CONNECTED -> firstConnected = candidate
                SwitchOutcome.CANDIDATE_REJECTED -> {
                    rejected += candidate.guid
                    onProgress("اتصال به این گزینه موفق نشد؛ گزینه بعدی بررسی می‌شود")
                }
                SwitchOutcome.RECOVERY_FAILED -> {
                    onMessage("اتصال جدید و بازیابی اتصال قبلی ناموفق بود؛ انتخاب خودکار متوقف شد. وضعیت VPN را بررسی کن.")
                    return
                }
                SwitchOutcome.FOLDER_CHANGED -> {
                    onMessage("پوشه انتخاب‌شده تغییر کرد؛ انتخاب خودکار متوقف شد.")
                    return
                }
                SwitchOutcome.CANCELLED_BY_USER -> return
            }
        }

        val connected = firstConnected
        if (connected == null) {
            onMessage("هیچ کانفیگِ دارای پینگ معتبر با موفقیت متصل نشد؛ انتخاب قبلی حفظ شد.")
            return
        }

        var activeGuid = connected.guid
        var bestDelay = connected.testDelayMillis
        onProgress("متصل؛ بررسی گزینه‌های بهتر ادامه دارد")
        var recoveryFailed = false
        var folderChanged = false

        // Only a strictly lower successful measurement can replace the active profile.
        try {
            combine(groupFlow, viewModel.uiState) { group, ui -> group.servers to ui }
                .takeWhile { (_, ui) ->
                    ui.isTesting && ui.selectedGroupId == groupId && !recoveryFailed && !folderChanged
                }
                .collect { (servers, ui) ->
                    if (!ui.isRunning) return@collect
                    val latest = servers.asSequence()
                        .filter {
                            it.testDelayMillis > 0L && it.testDelayMillis < bestDelay &&
                                it.guid != activeGuid && it.guid !in rejected
                        }
                        .minByOrNull { it.testDelayMillis }
                        ?: return@collect

                    when (switchTo(viewModel, latest.guid, groupId, onServiceToggle)) {
                        SwitchOutcome.CONNECTED -> {
                            activeGuid = latest.guid
                            bestDelay = latest.testDelayMillis
                            onProgress("بهترین کانفیگ به‌روزرسانی شد: ${latest.testDelayMillis} ms")
                        }
                        SwitchOutcome.CANDIDATE_REJECTED -> {
                            rejected += latest.guid
                            onProgress("گزینه سریع‌تر متصل نشد؛ اتصال فعلی حفظ شد")
                        }
                        SwitchOutcome.RECOVERY_FAILED -> recoveryFailed = true
                        SwitchOutcome.FOLDER_CHANGED -> folderChanged = true
                        SwitchOutcome.CANCELLED_BY_USER -> folderChanged = true
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A monitoring failure must never tear down the currently working tunnel.
        }

        if (recoveryFailed) {
            onMessage("بازیابی اتصال قبلی پس از سوئیچ ناموفق بود؛ انتخاب خودکار متوقف شد. وضعیت VPN را بررسی کن.")
            return
        }
        if (folderChanged || viewModel.uiState.value.selectedGroupId != groupId) return

        // The native worker completes asynchronously; refresh only this folder before the
        // final comparison so stale rows from a prior folder/test cannot win.
        viewModel.reloadAllGroups(listOf(groupId)).join()
        if (viewModel.uiState.value.selectedGroupId != groupId) return
        val finalBest = groupFlow.value.servers
            .filter { it.testDelayMillis > 0L && it.guid !in rejected }
            .minByOrNull { it.testDelayMillis }
        if (finalBest != null && finalBest.guid != activeGuid && finalBest.testDelayMillis < bestDelay) {
            when (switchTo(viewModel, finalBest.guid, groupId, onServiceToggle)) {
                SwitchOutcome.CONNECTED -> {
                    activeGuid = finalBest.guid
                    onProgress("بهترین کانفیگ انتخاب شد: ${finalBest.testDelayMillis} ms")
                }
                SwitchOutcome.CANDIDATE_REJECTED -> onMessage("اتصال به گزینه نهایی موفق نشد؛ کانفیگ فعال حفظ شد.")
                SwitchOutcome.RECOVERY_FAILED -> onMessage("اتصال و بازیابی ناموفق بود؛ وضعیت VPN را بررسی کن.")
                SwitchOutcome.FOLDER_CHANGED, SwitchOutcome.CANCELLED_BY_USER -> Unit
            }
        }
    }

    /**
     * A serialized stop/select/start transaction with rollback. The upstream service API does
     * not expose a seamless hot-swap; this routine never pretends that it does. If the candidate
     * fails, it restores the original group/GUID and restarts the prior tunnel before retrying.
     */
    private suspend fun switchTo(
        viewModel: MainViewModel,
        candidateGuid: String,
        expectedGroupId: String,
        onServiceToggle: () -> Unit,
    ): SwitchOutcome {
        switchInProgress.set(true)
        return try {
            withContext(NonCancellable) {
                switchMutex.withLock {
                    val before = viewModel.uiState.value
                    if (before.selectedGroupId != expectedGroupId) return@withLock SwitchOutcome.FOLDER_CHANGED
                    val previousGroupId = before.selectedGroupId
                    val previousGuid = before.selectedGuid

                    // A deliberate power-button press takes priority over the same-profile fast
                    // path too. Otherwise tapping Stop while the first candidate is already the
                    // active profile could be ignored by the early return below.
                    if (userStopRequested.get()) {
                        if (before.isRunning) {
                            val stopAccepted = try {
                                onServiceToggle()
                                withTimeoutOrNull(18_000L) { viewModel.uiState.first { !it.isRunning } } != null
                            } catch (_: Exception) { false }
                            if (!stopAccepted) return@withLock SwitchOutcome.RECOVERY_FAILED
                        }
                        if (!previousGuid.isNullOrBlank()) {
                            viewModel.restoreSelectedGroupAndGuid(previousGroupId, previousGuid)
                        }
                        return@withLock SwitchOutcome.CANCELLED_BY_USER
                    }

                    if (previousGuid == candidateGuid && before.isRunning) return@withLock SwitchOutcome.CONNECTED

                    if (before.isRunning) {
                        try {
                            onServiceToggle()
                        } catch (_: Exception) {
                            return@withLock SwitchOutcome.RECOVERY_FAILED
                        }
                        val stopped = withTimeoutOrNull(18_000L) { viewModel.uiState.first { !it.isRunning } }
                        if (stopped == null) return@withLock SwitchOutcome.RECOVERY_FAILED
                    }

                    if (userStopRequested.get()) {
                        if (!previousGuid.isNullOrBlank()) {
                            viewModel.restoreSelectedGroupAndGuid(previousGroupId, previousGuid)
                        }
                        return@withLock SwitchOutcome.CANCELLED_BY_USER
                    }

                    if (viewModel.uiState.value.selectedGroupId != expectedGroupId) {
                        if (before.isRunning && !previousGuid.isNullOrBlank()) {
                            viewModel.restoreSelectedGroupAndGuid(previousGroupId, previousGuid)
                            val restored = try {
                                onServiceToggle()
                                withTimeoutOrNull(30_000L) {
                                    viewModel.uiState.first {
                                        it.isRunning && it.selectedGroupId == previousGroupId && it.selectedGuid == previousGuid
                                    }
                                } != null
                            } catch (_: Exception) { false }
                            return@withLock if (restored) SwitchOutcome.FOLDER_CHANGED else SwitchOutcome.RECOVERY_FAILED
                        }
                        return@withLock SwitchOutcome.FOLDER_CHANGED
                    }

                    try {
                        viewModel.updateSelectedGuid(candidateGuid)
                        onServiceToggle()
                        val started = withTimeoutOrNull(35_000L) {
                            viewModel.uiState.first {
                                it.isRunning && it.selectedGroupId == expectedGroupId && it.selectedGuid == candidateGuid
                            }
                        }
                        if (started != null) {
                            if (!userStopRequested.get()) return@withLock SwitchOutcome.CONNECTED
                            // Finish a user-requested disconnect after the candidate reaches running.
                            onServiceToggle()
                            val stopped = withTimeoutOrNull(18_000L) { viewModel.uiState.first { !it.isRunning } }
                            if (stopped == null) return@withLock SwitchOutcome.RECOVERY_FAILED
                            if (!previousGuid.isNullOrBlank()) {
                                viewModel.restoreSelectedGroupAndGuid(previousGroupId, previousGuid)
                            }
                            return@withLock SwitchOutcome.CANCELLED_BY_USER
                        }
                    } catch (_: Exception) {
                        // Continue to the guarded rollback below.
                    }

                    // Stop a partially-started candidate before restoring the previous selection.
                    if (viewModel.uiState.value.isRunning) {
                        try {
                            onServiceToggle()
                        } catch (_: Exception) {
                            return@withLock SwitchOutcome.RECOVERY_FAILED
                        }
                        val stopped = withTimeoutOrNull(18_000L) { viewModel.uiState.first { !it.isRunning } }
                        if (stopped == null) return@withLock SwitchOutcome.RECOVERY_FAILED
                    }

                    if (userStopRequested.get()) {
                        if (!previousGuid.isNullOrBlank()) {
                            viewModel.restoreSelectedGroupAndGuid(previousGroupId, previousGuid)
                        }
                        return@withLock SwitchOutcome.CANCELLED_BY_USER
                    }

                    if (before.isRunning && !previousGuid.isNullOrBlank()) {
                        viewModel.restoreSelectedGroupAndGuid(previousGroupId, previousGuid)
                        val restored = try {
                            onServiceToggle()
                            withTimeoutOrNull(30_000L) {
                                viewModel.uiState.first {
                                    it.isRunning && it.selectedGroupId == previousGroupId && it.selectedGuid == previousGuid
                                }
                            } != null
                        } catch (_: Exception) { false }
                        if (restored) SwitchOutcome.CANDIDATE_REJECTED else SwitchOutcome.RECOVERY_FAILED
                    } else {
                        if (viewModel.uiState.value.selectedGroupId == previousGroupId && !previousGuid.isNullOrBlank()) {
                            viewModel.updateSelectedGuid(previousGuid)
                        }
                        SwitchOutcome.CANDIDATE_REJECTED
                    }
                }
            }
        } finally {
            switchInProgress.set(false)
        }
    }
}

internal data class V2PlasSpeedResult(
    val downloadMbps: Double,
    val uploadMbps: Double,
    val finishedAtElapsedMs: Long,
    val elapsedMs: Long = 0L,
)

/** In-memory results keyed by profile GUID. No profile/config data is written by this feature. */
internal object V2PlasSpeedTestStore {
    private val mutableResults = MutableStateFlow<Map<String, V2PlasSpeedResult>>(emptyMap())
    val results: StateFlow<Map<String, V2PlasSpeedResult>> = mutableResults.asStateFlow()

    fun put(guid: String, result: V2PlasSpeedResult) {
        mutableResults.value = mutableResults.value + (guid to result)
    }
}

/**
 * Brief throughput measurement over the currently active Android VPN route. The byte count is
 * not capped by the client; each direction is stopped by a monotonic time deadline. This method
 * intentionally does not start/switch VPN profiles while a measurement is running.
 */
internal object V2PlasSpeedTester {
    private const val DIRECTION_WINDOW_MS = 10_000L
    private const val UPLOAD_HARD_TIMEOUT_MS = 15_000L
    private const val MAX_TRANSFER_BYTES = 30L * 1024L * 1024L

    /** Measures only over the VPN route already in use; never starts or switches a profile. */
    suspend fun measure(onStage: (String) -> Unit): Triple<Double, Double, Long> {
        val totalStart = SystemClock.elapsedRealtime()
        onStage("در حال آزمایش دانلود")
        val download = withContext(Dispatchers.IO) { measureDownload() }
        onStage("در حال آزمایش آپلود")
        val upload = withContext(Dispatchers.IO) { measureUpload() }
        val elapsed = SystemClock.elapsedRealtime() - totalStart
        return Triple(download, upload, elapsed)
    }

    private fun measureDownload(): Double {
        val connection = (URL("https://speed.cloudflare.com/__down?bytes=$MAX_TRANSFER_BYTES").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-store")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val timedOut = AtomicBoolean(false)
        var abort: ScheduledFuture<*>? = null
        var bytes = 0L
        var measuredMs = 0L
        try {
            // Establish TLS/HTTP and obtain the response stream BEFORE starting the
            // ten-second throughput window. Slow handshakes must not consume the test window.
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw java.io.IOException("سرور تست دانلود پاسخ معتبر نداد (HTTP $responseCode)")
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                val start = SystemClock.elapsedRealtime()
                val deadline = start + DIRECTION_WINDOW_MS
                abort = scheduler.schedule({
                    timedOut.set(true)
                    connection.disconnect()
                }, DIRECTION_WINDOW_MS, TimeUnit.MILLISECONDS)
                while (bytes < MAX_TRANSFER_BYTES && SystemClock.elapsedRealtime() < deadline) {
                    val wanted = minOf(buffer.size.toLong(), MAX_TRANSFER_BYTES - bytes).toInt()
                    val count = try {
                        input.read(buffer, 0, wanted)
                    } catch (error: java.io.IOException) {
                        if (timedOut.get() && bytes > 0L) break else throw error
                    }
                    if (count <= 0) break
                    bytes += count
                }
                measuredMs = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
            }
        } finally {
            abort?.cancel(true)
            scheduler.shutdownNow()
            connection.disconnect()
        }
        if (bytes <= 0L || measuredMs <= 0L) throw java.io.IOException("داده‌ای برای اندازه‌گیری دانلود دریافت نشد")
        return bytes * 8.0 / measuredMs.toDouble() / 1_000.0
    }

    private fun measureUpload(): Double {
        val connection = (URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 5_000
            doOutput = true
            useCaches = false
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Cache-Control", "no-store")
        }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val timedOut = AtomicBoolean(false)
        var abort: ScheduledFuture<*>? = null
        val payload = ByteArray(64 * 1024) { index -> ((index * 31 + 17) and 0xFF).toByte() }
        var bytes = 0L
        var sendElapsedMs = 0L
        try {
            // Acquire the request stream first. DNS/TLS/connect and stream setup are not
            // counted as upload throughput and cannot prematurely close the socket.
            connection.outputStream.use { output ->
                val start = SystemClock.elapsedRealtime()
                val deadline = start + DIRECTION_WINDOW_MS
                // Ten seconds to send, then up to five seconds for the HTTP acknowledgement.
                abort = scheduler.schedule({
                    timedOut.set(true)
                    connection.disconnect()
                }, UPLOAD_HARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                while (bytes < MAX_TRANSFER_BYTES && SystemClock.elapsedRealtime() < deadline) {
                    val count = minOf(payload.size.toLong(), MAX_TRANSFER_BYTES - bytes).toInt()
                    try {
                        output.write(payload, 0, count)
                        bytes += count
                    } catch (error: java.io.IOException) {
                        if (timedOut.get() && bytes > 0L) break else throw error
                    }
                }
                output.flush()
                sendElapsedMs = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val suffix = if (timedOut.get()) " (مهلت پاسخ سرور تمام شد)" else ""
                throw java.io.IOException("سرور تست آپلود پاسخ معتبر نداد (HTTP $responseCode)$suffix")
            }
        } finally {
            abort?.cancel(true)
            scheduler.shutdownNow()
            connection.disconnect()
        }
        if (bytes <= 0L || sendElapsedMs <= 0L) throw java.io.IOException("داده‌ای برای اندازه‌گیری آپلود ارسال نشد")
        return bytes * 8.0 / sendElapsedMs.toDouble() / 1_000.0
    }

}

private data class StrongestServer(
    val groupId: String,
    val groupName: String,
    val server: ServersCache,
)

@Composable
internal fun StrongestConfigsFolder(
    mainViewModel: MainViewModel,
    onSelectServer: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 80.dp),
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = remember(uiState.groups) {
        uiState.groups.filter { group ->
            group.id != "v2plas_free_remote_group" &&
                group.id != "v2plas_free_ping_temporary_group"
        }
    }
    val groupFlows = remember(groups) { groups.map { it.id to mainViewModel.serverGroupState(it.id) } }
    val groupStates = groupFlows.map { (id, flow) -> id to flow.collectAsStateWithLifecycle().value }
    val allServers = remember(groupStates, groups) {
        groupStates.flatMap { (groupId, state) ->
            val groupName = groups.firstOrNull { it.id == groupId }?.remarks.orEmpty()
            state.servers.filter { it.testDelayMillis > 0L }.map { StrongestServer(groupId, groupName, it) }
        }.sortedWith(compareBy<StrongestServer> { it.server.testDelayMillis }.thenBy { it.server.profile.remarks.lowercase() })
    }
    var isRefreshing by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(groups) {
        if (groups.isEmpty()) return@LaunchedEffect
        isRefreshing = true
        lastError = null
        try {
            // Do not cancel a ping job started elsewhere in the app. Wait for it to finish,
            // then run this feature's own folder-by-folder sequence.
            if (mainViewModel.uiState.value.isTesting) {
                val existingTestFinished = withTimeoutOrNull(90_000L) {
                    mainViewModel.uiState.first { !it.isTesting }
                }
                if (existingTestFinished == null) {
                    lastError = "تست دیگری در حال اجراست و هنوز تمام نشده؛ نتیجه قبلی حفظ شد."
                    return@LaunchedEffect
                }
            }
            mainViewModel.reloadAllGroups(groups.map { it.id }).join()
            // Test one group at a time because the upstream ping worker owns one bulk request.
            // Explicit group IDs prevent results from being attributed to the currently selected tab.
            for ((groupId, flow) in groupFlows) {
                val loaded = flow.value
                if (loaded.servers.isEmpty()) continue
                mainViewModel.testAllRealPingForGroup(groupId)
                val completed = withTimeoutOrNull(90_000L) {
                    mainViewModel.uiState.first { !it.isTesting }
                }
                if (completed == null) {
                    lastError = "مهلت تست یکی از پوشه‌ها تمام شد؛ نتایج معتبر قبلی حفظ شدند."
                    break
                }
                // The upstream completion callback refreshes all group caches asynchronously.
                // Await a deterministic refresh before moving to the next folder, preventing
                // stale values from a previous folder from being rendered as current results.
                mainViewModel.reloadAllGroups(groups.map { it.id }).join()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastError = error.message ?: "خطا در بررسی پوشه‌ها"
        } finally {
            isRefreshing = false
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("قوی‌ترین کانفیگ‌ها", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            if (isRefreshing) CircularProgressIndicator(Modifier.size(19.dp), color = Color(0xFF62A8FF), strokeWidth = 2.dp)
        }
        Text("مرتب‌شده از کمترین پینگ معتبر در تمام پوشه‌ها", color = Color(0xFF9FB0D8), fontSize = 12.sp)
        if (lastError != null) Text(lastError!!, color = Color(0xFFFF8791), fontSize = 12.sp)
        if (allServers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (isRefreshing) "در حال بررسی کانفیگ‌ها…" else "هنوز نتیجه پینگ معتبر ثبت نشده است.",
                    color = Color(0xFFB7C5E8),
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(allServers, key = { "${it.groupId}:${it.server.guid}" }) { item ->
                    val pingColor = when {
                        item.server.testDelayMillis < 80L -> Color(0xFF35F59A)
                        item.server.testDelayMillis < 200L -> Color(0xFF9BE7A3)
                        item.server.testDelayMillis < 300L -> Color(0xFFFFD84D)
                        item.server.testDelayMillis < 500L -> Color(0xFFFF9B45)
                        else -> Color(0xFFFF4D5E)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color(0xFF111D42))
                            .border(1.dp, Color(0x443D66B7), RoundedCornerShape(15.dp))
                            .clickable { onSelectServer(item.groupId, item.server.guid) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("${item.server.testDelayMillis} ms", color = pingColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Column(Modifier.weight(1f)) {
                            Text(item.server.profile.remarks.ifBlank { "کانفیگ بدون نام" }, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(item.groupName, color = Color(0xFF9FB0D8), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

internal fun formatV2PlasMbps(value: Double): String = when {
    !value.isFinite() || value < 0.0 -> "—"
    value >= 1000.0 -> String.format(java.util.Locale.US, "%.2f Gbps", value / 1000.0)
    value >= 100.0 -> String.format(java.util.Locale.US, "%.0f Mbps", value)
    else -> String.format(java.util.Locale.US, "%.1f Mbps", value)
}
