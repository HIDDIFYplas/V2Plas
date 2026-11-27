// DEST: ui/main/HotspotProxyShare.kt
package com.v2ray.ang.ui.main

import android.app.Service
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.v2ray.ang.handler.SettingsManager
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

private object HotspotProxyShareContract {
    const val ACTION_START = "com.v2plas.action.HOTSPOT_PROXY_START"
    const val ACTION_STOP = "com.v2plas.action.HOTSPOT_PROXY_STOP"
    const val ACTION_QUERY = "com.v2plas.action.HOTSPOT_PROXY_QUERY"
    const val ACTION_RECHECK = "com.v2plas.action.HOTSPOT_PROXY_RECHECK"
    const val ACTION_STATUS = "com.v2plas.action.HOTSPOT_PROXY_STATUS"
    const val EXTRA_HOTSPOT_OWNED = "hotspot_owned_by_feature"
    const val EXTRA_STATE = "state"
    const val EXTRA_IP = "ip"
    const val EXTRA_PORT = "port"
    const val EXTRA_OWNED = "owned"
    const val EXTRA_MESSAGE = "message"
    const val STATE_OFF = "OFF"
    const val STATE_WAITING_HOTSPOT = "WAITING_HOTSPOT"
    const val STATE_WAITING_VPN = "WAITING_VPN"
    const val STATE_ACTIVE = "ACTIVE"
    const val STATE_ERROR = "ERROR"
}

private data class HotspotAddress(
    val address: Inet4Address,
    val interfaceName: String,
    val prefixLength: Short,
) {
    val hostAddress: String get() = address.hostAddress ?: ""
}

/**
 * Best-effort, OEM-neutral SoftAP address discovery. It deliberately refuses to
 * bind to 0.0.0.0: the proxy must not be exposed on the phone's other LAN/Wi-Fi
 * interfaces when the tethering interface cannot be identified.
 */
private object HotspotProxyNetwork {
    private val excludedInterface = Regex("(?i)(^lo$|tun|vpn|rmnet|ccmni|pdp|wwan|dummy|sit|clat|rndis|usb)")
    // wlan0 is often the ordinary Wi-Fi client interface, so do not treat the
    // generic wlan prefix as proof of tethering. A wlan interface qualifies only
    // when its address is the likely SoftAP gateway (.1).
    private val likelyApInterface = Regex("(?i)(^ap[0-9]*$|softap|hotspot|tether|swlan|bridge|^br[0-9]*$)")

    fun discoverHotspotAddress(): HotspotAddress? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val candidates = ArrayList<Pair<Int, HotspotAddress>>()
            interfaces.forEach ifaceLoop@{ networkInterface ->
                val name = networkInterface.name ?: return@ifaceLoop
                if (excludedInterface.containsMatchIn(name)) return@ifaceLoop
                if (!networkInterface.isUp || networkInterface.isLoopback) return@ifaceLoop
                val ifaceAddresses = networkInterface.interfaceAddresses
                ifaceAddresses.forEach addressLoop@{ interfaceAddress ->
                    val address = interfaceAddress.address as? Inet4Address ?: return@addressLoop
                    val bytes = address.address.map { it.toInt() and 0xff }
                    if (!isPrivateIpv4(bytes)) return@addressLoop
                    // Prefer explicit SoftAP names and the conventional gateway
                    // address, but retain other private IPv4 candidates. Some
                    // OEMs use non-.1 gateways and generic wlan interfaces.
                    val lastOctetIsOne = bytes[3] == 1
                    val interfaceLooksLikeAp = likelyApInterface.containsMatchIn(name)
                    val score = (if (interfaceLooksLikeAp) 0 else 30) +
                        (if (lastOctetIsOne) 0 else 20) +
                        (if (name.matches(Regex("(?i)^wlan[0-9]+$"))) 5 else 0) +
                        (if (address.hostAddress == "192.168.43.1") -2 else 0)
                    candidates += score to HotspotAddress(address, name, interfaceAddress.networkPrefixLength)
                }
            }
            val ranked = candidates.sortedBy { it.first }
            val best = ranked.firstOrNull() ?: return null
            // If there is no conventional gateway/AP interface, use a private
            // interface only when it is the sole candidate. Never guess between
            // multiple private LAN interfaces (e.g. Wi-Fi client + SoftAP).
            val clearlyIdentified = best.first < 30
            if (clearlyIdentified) best.second
            else ranked.singleOrNull()?.second
        } catch (_: Exception) {
            null
        }
    }

    private fun isPrivateIpv4(bytes: List<Int>): Boolean {
        if (bytes.size != 4) return false
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168)
    }

    fun sameSubnet(client: InetAddress?, local: HotspotAddress): Boolean {
        val remote = client as? Inet4Address ?: return false
        val localBytes = local.address.address
        val remoteBytes = remote.address
        val prefixValue = local.prefixLength.toInt()
        val prefix = if (prefixValue in 8..30) prefixValue else 24
        for (bit in 0 until prefix) {
            val byteIndex = bit / 8
            val bitMask = 1 shl (7 - (bit % 8))
            if ((localBytes[byteIndex].toInt() and bitMask) != (remoteBytes[byteIndex].toInt() and bitMask)) {
                return false
            }
        }
        return true
    }
}

/**
 * The bridge accepts HTTP proxy requests on the detected hotspot interface and
 * translates each destination into a SOCKS5 CONNECT through v2rayNG's existing
 * loopback SOCKS inbound. It does not alter CoreConfigManager, the VPN interface,
 * PREF_PROXY_SHARING, or the active outbound configuration.
 */
class HotspotProxyShareService : Service() {
    private val monitorExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "V2Plas-HotspotProxy-Monitor").apply { isDaemon = true }
    }
    private var monitorFuture: ScheduledFuture<*>? = null
    @Volatile private var requested = false
    private var hotspotOwned = false
    private var currentState = HotspotProxyShareContract.STATE_OFF
    private var currentIp = ""
    private var currentPort = 0
    private var boundAddress: HotspotAddress? = null
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var lastBroadcastKey = ""
    // Short-lived discovery/probe misses are common while OEM tethering interfaces
    // are being reconfigured. Do not tear down every connected client on one miss.
    private var consecutiveSocksFailures = 0
    private var consecutiveHotspotMisses = 0
    private val clientSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    // Track upstream SOCKS sockets too. Closing only the hotspot-side client can
    // leave a CONNECT download blocked on the remote read side during shutdown.
    private val upstreamSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    // A SynchronousQueue makes the pool grow as soon as the core workers are
    // occupied by long-lived CONNECT tunnels. A large FIFO queue behind two
    // workers caused unrelated requests from other phones to wait indefinitely.
    private val workers = ThreadPoolExecutor(
        4, 32, 30L, TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable -> Thread(runnable, "V2Plas-HotspotProxy-Worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    private fun monitorOnce() {
        if (!requested) return
        try {
            val hotspot = HotspotProxyNetwork.discoverHotspotAddress()
            if (hotspot == null) {
                consecutiveHotspotMisses++
                if (consecutiveHotspotMisses >= 3) closeListener()
                val privateInterfaces = describePrivateInterfaces()
                val message = if (privateInterfaces.isEmpty()) {
                    "هات‌اسپات فعلاً شناسایی نشد؛ در حال بررسی مجدد هستیم."
                } else {
                    "رابط هات‌اسپات هنوز قطعی شناسایی نشده است: $privateInterfaces"
                }
                if (serverSocket != null) {
                    publish(HotspotProxyShareContract.STATE_ACTIVE, message = "تشخیص هات‌اسپات موقتاً ناپایدار است؛ اتصال‌های فعال حفظ می‌شوند. $message")
                } else {
                    publish(HotspotProxyShareContract.STATE_WAITING_HOTSPOT, message = message, force = true)
                }
                return
            }
            consecutiveHotspotMisses = 0

            if (!isLocalSocksReady()) {
                consecutiveSocksFailures++
                if (consecutiveSocksFailures >= 4) {
                    // Do not forward directly when the local SOCKS endpoint is
                    // unavailable. The listener is removed only after repeated
                    // failed probes, not after one transient scheduling/network miss.
                    closeListener()
                    publish(HotspotProxyShareContract.STATE_WAITING_VPN, message = "ورودی SOCKS تونل در چند بررسی متوالی پاسخ نداده است؛ Proxy برای جلوگیری از نشت ترافیک متوقف شد.", force = true)
                } else if (serverSocket != null) {
                    publish(HotspotProxyShareContract.STATE_ACTIVE, message = "بررسی مسیر VPN موقتاً ناموفق بود؛ در حال تلاش مجدد هستیم. ترافیک فقط از SOCKS محلی عبور می‌کند.")
                } else {
                    publish(HotspotProxyShareContract.STATE_WAITING_VPN, message = "در انتظار آماده‌شدن ورودی SOCKS محلی تونل VPN هستیم.")
                }
                return
            }
            consecutiveSocksFailures = 0
            if (serverSocket == null || currentIp != hotspot.hostAddress) {
                closeListener()
                startListener(hotspot)
            }
        } catch (error: Exception) {
            android.util.Log.w("V2PlasHotspotProxy", "Health monitor pass failed", error)
            consecutiveSocksFailures++
            if (consecutiveSocksFailures >= 4) {
                closeListener()
                publish(HotspotProxyShareContract.STATE_ERROR, message = "بررسی سلامت تونل چند بار ناموفق بود؛ اشتراک‌گذاری برای جلوگیری از نشت متوقف شد.", force = true)
            } else if (serverSocket != null) {
                publish(HotspotProxyShareContract.STATE_ACTIVE, message = "بررسی سلامت موقتاً با خطا مواجه شد؛ اتصال‌های فعال حفظ می‌شوند.")
            } else {
                publish(HotspotProxyShareContract.STATE_ERROR, message = error.message ?: "خطای موقت در بررسی اشتراک‌گذاری")
            }
        }
    }

    private fun beginMonitoring() {
        monitorFuture?.cancel(true)
        monitorFuture = monitorExecutor.scheduleWithFixedDelay({ monitorOnce() }, 0L, 3L, TimeUnit.SECONDS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            HotspotProxyShareContract.ACTION_START,
            HotspotProxyShareContract.ACTION_RECHECK -> {
                if (intent.action == HotspotProxyShareContract.ACTION_START) {
                    hotspotOwned = intent.getBooleanExtra(HotspotProxyShareContract.EXTRA_HOTSPOT_OWNED, false)
                }
                requested = true
                beginMonitoring()
                // The zero-delay scheduled pass is the explicit recheck. Force a
                // status broadcast even when the detected state did not change,
                // so the dialog always receives the latest diagnostic result.
            }
            HotspotProxyShareContract.ACTION_STOP -> {
                val wasOwned = hotspotOwned
                requested = false
                monitorFuture?.cancel(true)
                monitorFuture = null
                closeListener()
                consecutiveSocksFailures = 0
                consecutiveHotspotMisses = 0
                publish(HotspotProxyShareContract.STATE_OFF, message = "اشتراک‌گذاری خاموش شد.", ownedOverride = wasOwned)
                hotspotOwned = false
                stopSelf(startId)
            }
            HotspotProxyShareContract.ACTION_QUERY -> {
                publish(currentState, currentIp, currentPort, message = statusMessage(currentState), ownedOverride = hotspotOwned, force = true)
                if (!requested) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        requested = false
        monitorFuture?.cancel(true)
        monitorFuture = null
        monitorExecutor.shutdownNow()
        closeListener()
        workers.shutdownNow()
        super.onDestroy()
    }

    private fun statusMessage(state: String): String = when (state) {
        HotspotProxyShareContract.STATE_ACTIVE -> "Proxy آمادهٔ استفاده است."
        HotspotProxyShareContract.STATE_WAITING_HOTSPOT -> "در انتظار روشن‌شدن هات‌اسپات هستیم."
        HotspotProxyShareContract.STATE_WAITING_VPN -> "در انتظار اتصال VPN و آماده‌شدن SOCKS محلی هستیم."
        HotspotProxyShareContract.STATE_ERROR -> "راه‌اندازی Proxy با خطا مواجه شد."
        else -> "اشتراک‌گذاری خاموش است."
    }

    private fun publish(
        state: String,
        ip: String = currentIp,
        port: Int = currentPort,
        message: String = statusMessage(state),
        ownedOverride: Boolean = hotspotOwned,
        force: Boolean = false,
    ) {
        currentState = state
        currentIp = ip
        currentPort = port
        val key = "$state|$ip|$port|$message|$ownedOverride"
        if (key == lastBroadcastKey && !force) return
        lastBroadcastKey = key
        sendBroadcast(
            Intent(HotspotProxyShareContract.ACTION_STATUS).setPackage(packageName)
                .putExtra(HotspotProxyShareContract.EXTRA_STATE, state)
                .putExtra(HotspotProxyShareContract.EXTRA_IP, ip)
                .putExtra(HotspotProxyShareContract.EXTRA_PORT, port)
                .putExtra(HotspotProxyShareContract.EXTRA_OWNED, ownedOverride)
                .putExtra(HotspotProxyShareContract.EXTRA_MESSAGE, message)
        )
    }

    private fun startListener(hotspot: HotspotAddress) {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(hotspot.address, 0), 24)
        socket.soTimeout = 1_000
        serverSocket = socket
        boundAddress = hotspot
        currentIp = hotspot.hostAddress
        currentPort = socket.localPort
        publish(HotspotProxyShareContract.STATE_ACTIVE, currentIp, currentPort,
            "Proxy روی رابط ${hotspot.interfaceName} آماده است.")
        acceptThread = Thread({
            while (requested && serverSocket === socket && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    if (!HotspotProxyNetwork.sameSubnet(client.inetAddress, hotspot)) {
                        client.close()
                        continue
                    }
                    client.soTimeout = 60_000
                    client.tcpNoDelay = true
                    client.keepAlive = true
                    clientSockets.add(client)
                    try {
                        workers.execute {
                            try {
                                handleClient(client)
                            } catch (error: Exception) {
                                // A malformed/aborted client must never escape the worker
                                // and crash the :daemon process that also hosts the VPN.
                                android.util.Log.w("V2PlasHotspotProxy", "Client proxy request ended", error)
                            } finally {
                                clientSockets.remove(client)
                                try { client.close() } catch (_: Exception) { }
                            }
                        }
                    } catch (error: Exception) {
                        clientSockets.remove(client)
                        try { writeHttpError(client.getOutputStream(), 503, "Service Unavailable") } catch (_: Exception) { }
                        try { client.close() } catch (_: Exception) { }
                        android.util.Log.w("V2PlasHotspotProxy", "Proxy worker capacity reached", error)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Allows the loop to observe service/listener shutdown.
                } catch (_: SocketException) {
                    break
                } catch (error: Exception) {
                    if (!socket.isClosed) android.util.Log.w("V2PlasHotspotProxy", "Accept failed", error)
                }
            }
        }, "V2Plas-HotspotProxy-Accept").apply { isDaemon = true; start() }
    }

    private fun closeListener() {
        val old = serverSocket
        serverSocket = null
        boundAddress = null
        currentIp = ""
        currentPort = 0
        try { old?.close() } catch (_: Exception) { }
        acceptThread = null
        clientSockets.toList().forEach { try { it.close() } catch (_: Exception) { } }
        clientSockets.clear()
        upstreamSockets.toList().forEach { try { it.close() } catch (_: Exception) { } }
        upstreamSockets.clear()
    }

    private fun describePrivateInterfaces(): String {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback && !Regex("(?i)(^lo$|tun|vpn|rmnet|ccmni|pdp|wwan|dummy|sit|clat|rndis|usb)").containsMatchIn(it.name.orEmpty()) }
                .flatMap { iface ->
                    iface.interfaceAddresses.mapNotNull { entry ->
                        val address = entry.address as? Inet4Address ?: return@mapNotNull null
                        val bytes = address.address.map { it.toInt() and 0xff }
                        if (bytes.size == 4 && (bytes[0] == 10 ||
                                (bytes[0] == 172 && bytes[1] in 16..31) ||
                                (bytes[0] == 192 && bytes[1] == 168))) {
                            "${iface.name}:${address.hostAddress}"
                        } else null
                    }
                }
                .take(4)
                .joinToString()
        } catch (_: Exception) { "" }
    }

    private fun isLocalSocksReady(): Boolean {
        return try {
            val port = SettingsManager.getSocksPort()
            val user = SettingsManager.getSocksUsername()
            val pass = SettingsManager.getSocksPassword()
            val credentialsAvailable = !user.isNullOrEmpty() && !pass.isNullOrEmpty()
            Socket().use { probe ->
                probe.connect(InetSocketAddress("127.0.0.1", port), 800)
                probe.soTimeout = 1_000
                val input = DataInputStream(probe.getInputStream())
                val output = DataOutputStream(probe.getOutputStream())
                if (credentialsAvailable) {
                    output.write(byteArrayOf(5, 2, 0, 2))
                } else {
                    output.write(byteArrayOf(5, 1, 0))
                }
                output.flush()
                if (input.readUnsignedByte() != 5) return false
                when (input.readUnsignedByte()) {
                    0 -> true
                    2 -> {
                        if (!credentialsAvailable) return false
                        val username = user!!.toByteArray(StandardCharsets.UTF_8)
                        val password = pass!!.toByteArray(StandardCharsets.UTF_8)
                        if (username.isEmpty() || password.isEmpty() || username.size > 255 || password.size > 255) return false
                        output.writeByte(1); output.writeByte(username.size); output.write(username)
                        output.writeByte(password.size); output.write(password); output.flush()
                        input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0
                    }
                    else -> false
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun connectThroughLocalSocks(host: String, port: Int): Socket {
        val socksPort = SettingsManager.getSocksPort()
        val user = SettingsManager.getSocksUsername()
        val pass = SettingsManager.getSocksPassword()
        val authRequired = !user.isNullOrEmpty() && !pass.isNullOrEmpty()
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", socksPort), 2_500)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 15_000
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            output.write(byteArrayOf(0x05, 0x01, if (authRequired) 0x02 else 0x00))
            output.flush()
            val greeting = input.readUnsignedByte()
            val method = input.readUnsignedByte()
            if (greeting != 5 || method == 0xff) throw java.io.IOException("SOCKS5 authentication negotiation failed")
            if (method == 2) {
                val username = user?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()
                val password = pass?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()
                if (username.isEmpty() || password.isEmpty() || username.size > 255 || password.size > 255) {
                    throw java.io.IOException("SOCKS5 credentials are invalid")
                }
                output.writeByte(1); output.writeByte(username.size); output.write(username)
                output.writeByte(password.size); output.write(password); output.flush()
                if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) {
                    throw java.io.IOException("SOCKS5 authentication rejected")
                }
            } else if (method != 0) {
                throw java.io.IOException("Unsupported SOCKS5 authentication method")
            }
            output.writeByte(5); output.writeByte(1); output.writeByte(0)
            val numeric = if (host.contains(':') || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
                try { InetAddress.getByName(host) } catch (_: Exception) { null }
            } else null
            when (numeric) {
                is Inet4Address -> { output.writeByte(1); output.write(numeric.address) }
                is java.net.Inet6Address -> { output.writeByte(4); output.write(numeric.address) }
                else -> {
                    val domain = host.toByteArray(StandardCharsets.UTF_8)
                    if (domain.isEmpty() || domain.size > 255) throw java.io.IOException("Invalid destination host")
                    output.writeByte(3); output.writeByte(domain.size); output.write(domain)
                }
            }
            output.writeShort(port); output.flush()
            val version = input.readUnsignedByte()
            val reply = input.readUnsignedByte()
            input.readUnsignedByte()
            val addressType = input.readUnsignedByte()
            when (addressType) {
                1 -> input.readFully(ByteArray(4))
                3 -> input.readFully(ByteArray(input.readUnsignedByte()))
                4 -> input.readFully(ByteArray(16))
                else -> throw java.io.IOException("Invalid SOCKS5 response")
            }
            input.readUnsignedShort()
            if (version != 5 || reply != 0) throw java.io.IOException("SOCKS5 connect failed: $reply")
            socket.soTimeout = 0
            upstreamSockets.add(socket)
            return socket
        } catch (error: Exception) {
            try { socket.close() } catch (_: Exception) { }
            throw error
        }
    }

    private fun handleClient(client: Socket) {
        val clientInput = client.getInputStream()
        val clientOutput = client.getOutputStream()
        val headerBytes = readHeader(clientInput, 16 * 1024)
        val headerText = String(headerBytes, StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val request = lines.firstOrNull()?.split(' ', limit = 3)
            ?: throw java.io.IOException("Invalid HTTP proxy request")
        if (request.size != 3) throw java.io.IOException("Invalid HTTP request line")
        val method = request[0].uppercase()
        val target = request[1]
        if (method == "CONNECT") {
            val authority = parseAuthority(target, 443)
            val upstream = try { connectThroughLocalSocks(authority.first, authority.second) }
            catch (error: Exception) {
                writeHttpError(clientOutput, 502, "Bad Gateway")
                return
            }
            client.soTimeout = 0
            try {
                clientOutput.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: V2 Plas\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                clientOutput.flush()
                tunnelBothWays(client, upstream)
            } finally {
                upstreamSockets.remove(upstream)
                try { upstream.close() } catch (_: Exception) { }
            }
            return
        }

        val hostHeader = lines.drop(1).firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val uri = try { URI(target) } catch (_: Exception) { null }
        val host = uri?.host?.takeIf { it.isNotBlank() } ?: hostHeader?.let { parseAuthority(it, 80).first }
            ?: throw java.io.IOException("HTTP request has no valid Host")
        val port = uri?.port?.takeIf { it > 0 } ?: hostHeader?.let { parseAuthority(it, 80).second } ?: 80
        val path = if (uri != null && uri.isAbsolute) {
            (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        } else target
        val upstream = try { connectThroughLocalSocks(host, port) }
        catch (_: Exception) { writeHttpError(clientOutput, 502, "Bad Gateway"); return }
        try {
            upstream.use { remote ->
            val remoteOut = remote.getOutputStream()
            remoteOut.write("$method $path ${request[2]}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            var contentLength: Long? = null
            var isChunked = false
            var hasTransferEncoding = false
            var clientExpectContinue = false
            lines.drop(1).filter { it.isNotEmpty() }.forEach { line ->
                val name = line.substringBefore(':').trim()
                when {
                    name.equals("Proxy-Connection", true) || name.equals("Proxy-Authorization", true) -> Unit
                    name.equals("Connection", true) -> Unit
                    name.equals("Expect", true) -> {
                        if (line.substringAfter(':').trim().equals("100-continue", true)) {
                            clientExpectContinue = true
                        }
                    }
                    name.equals("Content-Length", true) -> {
                        val parsedLength = line.substringAfter(':').trim().toLongOrNull()
                            ?: throw java.io.IOException("Invalid Content-Length")
                        if (parsedLength < 0L || (contentLength != null && contentLength != parsedLength)) {
                            throw java.io.IOException("Conflicting Content-Length headers")
                        }
                        contentLength = parsedLength
                        remoteOut.write((line + "\r\n").toByteArray(StandardCharsets.ISO_8859_1))
                    }
                    name.equals("Transfer-Encoding", true) -> {
                        val encoding = line.substringAfter(':').trim()
                        if (!encoding.equals("chunked", true) || hasTransferEncoding) {
                            throw java.io.IOException("Unsupported Transfer-Encoding")
                        }
                        hasTransferEncoding = true
                        isChunked = true
                        remoteOut.write((line + "\r\n").toByteArray(StandardCharsets.ISO_8859_1))
                    }
                    else -> remoteOut.write((line + "\r\n").toByteArray(StandardCharsets.ISO_8859_1))
                }
            }
            remoteOut.write("Connection: close\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            remoteOut.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            remoteOut.flush()
            if (clientExpectContinue) {
                clientOutput.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                clientOutput.flush()
            }
            if (isChunked && contentLength != null) throw java.io.IOException("Ambiguous HTTP request framing")
            if (isChunked) copyChunkedBody(clientInput, remoteOut)
            else if ((contentLength ?: 0L) > 0L) copyExactly(clientInput, remoteOut, contentLength!!)
            remoteOut.flush()
            // The response stream may be long-lived (downloads/video); do not
            // impose a short idle timeout after the request has been forwarded.
            remote.soTimeout = 0
            remote.getInputStream().copyTo(clientOutput)
            clientOutput.flush()
            }
        } finally {
            upstreamSockets.remove(upstream)
        }
    }

    private fun readHeader(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var state = 0
        while (output.size() < maxBytes) {
            val next = input.read()
            if (next < 0) throw java.io.IOException("Client closed before HTTP headers")
            output.write(next)
            state = when {
                state == 0 && next == 13 -> 1
                state == 1 && next == 10 -> 2
                state == 2 && next == 13 -> 3
                state == 3 && next == 10 -> 4
                next == 13 -> 1
                else -> 0
            }
            if (state == 4) return output.toByteArray()
        }
        throw java.io.IOException("HTTP headers are too large")
    }

    private fun parseAuthority(authority: String, defaultPort: Int): Pair<String, Int> {
        val value = authority.trim()
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 1) throw java.io.IOException("Invalid IPv6 authority")
            val host = value.substring(1, end)
            val port = if (value.length > end + 1 && value[end + 1] == ':') value.substring(end + 2).toIntOrNull() else defaultPort
            return host to (port ?: defaultPort).coerceIn(1, 65535)
        }
        val colon = value.lastIndexOf(':')
        if (colon > 0 && value.indexOf(':') == colon) {
            val port = value.substring(colon + 1).toIntOrNull()
            if (port != null) return value.substring(0, colon) to port.coerceIn(1, 65535)
        }
        return value to defaultPort
    }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw java.io.IOException("Unexpected end of HTTP request body")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun copyChunkedBody(input: InputStream, output: OutputStream) {
        while (true) {
            val sizeLine = readAsciiLine(input, 1_024)
            output.write((sizeLine + "\r\n").toByteArray(StandardCharsets.ISO_8859_1))
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw java.io.IOException("Invalid chunk size")
            if (size == 0L) {
                while (true) {
                    val trailer = readAsciiLine(input, 8 * 1024)
                    output.write((trailer + "\r\n").toByteArray(StandardCharsets.ISO_8859_1))
                    if (trailer.isEmpty()) return
                }
            }
            copyExactly(input, output, size)
            val cr = input.read(); val lf = input.read()
            if (cr != 13 || lf != 10) throw java.io.IOException("Invalid chunk terminator")
            output.write(byteArrayOf(13, 10))
        }
    }

    private fun readAsciiLine(input: InputStream, max: Int): String {
        val bytes = ByteArrayOutputStream()
        var previous = -1
        while (bytes.size() <= max) {
            val current = input.read()
            if (current < 0) throw java.io.IOException("Unexpected end of chunked body")
            if (previous == 13 && current == 10) {
                val data = bytes.toByteArray()
                return String(data, 0, (data.size - 1).coerceAtLeast(0), StandardCharsets.ISO_8859_1)
            }
            bytes.write(current)
            previous = current
        }
        throw java.io.IOException("Chunk line is too long")
    }

    private fun writeHttpError(output: OutputStream, code: Int, reason: String) {
        val body = "V2 Plas hotspot proxy: $reason".toByteArray(StandardCharsets.UTF_8)
        output.write("HTTP/1.1 $code $reason\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun tunnelBothWays(client: Socket, upstream: Socket) {
        val finished = java.util.concurrent.CountDownLatch(2)
        val pumpA = Thread({
            try { client.getInputStream().copyTo(upstream.getOutputStream()) } catch (_: Exception) { }
            finally { try { upstream.shutdownOutput() } catch (_: Exception) { }; finished.countDown() }
        }, "V2Plas-Proxy-Up").apply { isDaemon = true; start() }
        val pumpB = Thread({
            try { upstream.getInputStream().copyTo(client.getOutputStream()) } catch (_: Exception) { }
            finally { try { client.shutdownOutput() } catch (_: Exception) { }; finished.countDown() }
        }, "V2Plas-Proxy-Down").apply { isDaemon = true; start() }
        try { finished.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { client.close() } catch (_: Exception) { }
        try { upstream.close() } catch (_: Exception) { }
    }
}

private data class HotspotProxyUiState(
    val state: String = HotspotProxyShareContract.STATE_OFF,
    val ip: String = "",
    val port: Int = 0,
    val owned: Boolean = false,
    val message: String = "اشتراک‌گذاری خاموش است.",
) {
    val enabled: Boolean get() = state != HotspotProxyShareContract.STATE_OFF
}

@Composable
fun HotspotProxyShareDialog(
    isVpnConnected: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var uiState by remember { mutableStateOf(HotspotProxyUiState()) }
    var uiError by remember { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    var pendingOwned by remember { mutableStateOf(false) }

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingStart) {
            pendingStart = false
            context.startService(
                Intent(context, HotspotProxyShareService::class.java)
                    .setAction(HotspotProxyShareContract.ACTION_START)
                    .putExtra(HotspotProxyShareContract.EXTRA_HOTSPOT_OWNED, pendingOwned)
            )
        }
    }

    var continueEnableAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) continueEnableAfterPermission?.invoke()
        else uiError = "برای دسترسی دستگاه‌های متصل به شبکه محلی، مجوز شبکه محلی لازم است."
        continueEnableAfterPermission = null
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != HotspotProxyShareContract.ACTION_STATUS) return
                uiState = HotspotProxyUiState(
                    state = intent.getStringExtra(HotspotProxyShareContract.EXTRA_STATE) ?: HotspotProxyShareContract.STATE_OFF,
                    ip = intent.getStringExtra(HotspotProxyShareContract.EXTRA_IP).orEmpty(),
                    port = intent.getIntExtra(HotspotProxyShareContract.EXTRA_PORT, 0),
                    owned = intent.getBooleanExtra(HotspotProxyShareContract.EXTRA_OWNED, false),
                    message = intent.getStringExtra(HotspotProxyShareContract.EXTRA_MESSAGE).orEmpty(),
                )
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(HotspotProxyShareContract.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        context.startService(Intent(context, HotspotProxyShareService::class.java).setAction(HotspotProxyShareContract.ACTION_QUERY))
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) { } }
    }

    fun openHotspotSettings() {
        val candidates = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        val target = candidates.firstOrNull { it.resolveActivity(context.packageManager) != null } ?: candidates.last()
        try { settingsLauncher.launch(target) }
        catch (_: Exception) {
            pendingStart = false
            pendingOwned = false
            uiError = "بازکردن تنظیمات هات‌اسپات ممکن نشد. لطفاً از تنظیمات گوشی، هات‌اسپات را باز کنید."
        }
    }

    fun enableShareAfterPermission() {
        val alreadyOn = HotspotProxyNetwork.discoverHotspotAddress() != null
        if (alreadyOn) {
            context.startService(
                Intent(context, HotspotProxyShareService::class.java)
                    .setAction(HotspotProxyShareContract.ACTION_START)
                    .putExtra(HotspotProxyShareContract.EXTRA_HOTSPOT_OWNED, false)
            )
        } else {
            pendingStart = true
            pendingOwned = true
            openHotspotSettings()
        }
    }

    fun enableShare() {
        uiError = null
        if (!isVpnConnected) {
            uiError = "ابتدا VPN گوشی میزبان را به یک کانفیگ متصل کنید."
            return
        }
        val enableAction = { enableShareAfterPermission() }
        if (Build.VERSION.SDK_INT >= 37 && context.applicationInfo.targetSdkVersion >= 37 &&
            context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED
        ) {
            continueEnableAfterPermission = enableAction
            localNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else {
            enableAction()
        }
    }

    fun disableShare() {
        // Cancel a pending settings round-trip so returning from Settings cannot
        // unexpectedly start the proxy after the user has switched it off.
        pendingStart = false
        pendingOwned = false
        continueEnableAfterPermission = null
        val shouldAskToTurnOffHotspot = uiState.owned && HotspotProxyNetwork.discoverHotspotAddress() != null
        context.startService(Intent(context, HotspotProxyShareService::class.java).setAction(HotspotProxyShareContract.ACTION_STOP))
        uiState = HotspotProxyUiState()
        uiError = null
        if (shouldAskToTurnOffHotspot) {
            uiError = "اشتراک‌گذاری خاموش شد؛ برای خاموش‌کردن هات‌اسپاتی که با این قابلیت روشن کرده‌اید، آن را در تنظیمات سیستم خاموش کنید."
            openHotspotSettings()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اشتراک‌گذاری VPN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("فعال‌سازی اشتراک‌گذاری", fontWeight = FontWeight.SemiBold)
                            Text("هات‌اسپات + HTTP Proxy", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = uiState.enabled || pendingStart || continueEnableAfterPermission != null,
                            onCheckedChange = { if (it) enableShare() else disableShare() },
                        )
                    }
                }
                Text(uiState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (uiState.state == HotspotProxyShareContract.STATE_ACTIVE && uiState.ip.isNotBlank() && uiState.port > 0) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("تنظیمات Proxy دستگاه دوم", fontWeight = FontWeight.SemiBold)
                            CopyValueRow("IP", uiState.ip) { clipboard.setText(AnnotatedString(uiState.ip)) }
                            CopyValueRow("Port", uiState.port.toString()) { clipboard.setText(AnnotatedString(uiState.port.toString())) }
                            Button(
                                onClick = { clipboard.setText(AnnotatedString("${uiState.ip}:${uiState.port}")) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("کپی IP و Port") }
                        }
                    }
                    Text(
                        "در دستگاه دوم به تنظیمات Wi‑Fi بروید، Proxy را روی Manual بگذارید و IP و Port بالا را وارد کنید. این روش HTTP و HTTPS را از طریق CONNECT عبور می‌دهد؛ UDP/QUIC را پوشش نمی‌دهد و برنامه‌هایی که Proxy وای‌فای را نادیده می‌گیرند ممکن است کار نکنند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (uiState.enabled) {
                    OutlinedButton(onClick = { context.startService(Intent(context, HotspotProxyShareService::class.java).setAction(HotspotProxyShareContract.ACTION_RECHECK)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("بررسی مجدد هات‌اسپات و VPN")
                    }
                }
                uiError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
    )
}

@Composable
private fun CopyValueRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onCopy) { Text("کپی") }
    }
}
