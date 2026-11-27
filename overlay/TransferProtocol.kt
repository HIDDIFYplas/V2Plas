// DEST: ui/transfer/TransferProtocol.kt
package com.v2ray.ang.ui.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class TransferProfile(
    val guid: String,
    val name: String,
    val profile: ProfileItem,
)

internal data class TransferSession(
    val host: String,
    val port: Int,
    val sessionId: String,
    val token: String,
    val expiresAt: Long,
    val deviceName: String,
)

internal data class TransferResult(
    val received: Int,
    val duplicates: Int,
    val deviceName: String,
)

internal class TvTransferReceiver internal constructor(
    private val server: ServerSocket,
    val session: TransferSession,
    val qrText: String,
    private val expiresAtElapsedRealtime: Long,
) : AutoCloseable {
    @Volatile private var closed = false

    fun awaitTransfer(): TransferResult {
        var rejectedConnections = 0
        while (!closed && SystemClock.elapsedRealtime() < expiresAtElapsedRealtime) {
            try {
                server.soTimeout = 1_000
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                socket.use { client ->
                    client.soTimeout = 12_000
                    try {
                        val input = DataInputStream(client.getInputStream())
                        val output = DataOutputStream(client.getOutputStream())
                        val encrypted = TransferProtocol.readFrame(input)
                        val plaintext = TransferProtocol.decrypt(encrypted, session)
                        val payload = JSONObject(String(plaintext, Charsets.UTF_8))
                        val deviceName = payload.optString("device_name", "Android")
                            .take(64).ifBlank { "Android" }
                        val profiles = payload.optJSONArray("profiles")
                            ?: throw IllegalArgumentException("بسته انتقال نامعتبر است")
                        if (profiles.length() !in 1..TransferProtocol.MAX_PROFILES) {
                            throw IllegalArgumentException("تعداد کانفیگ‌ها خارج از محدوده مجاز است")
                        }
                        val incoming = ArrayList<ProfileItem>(profiles.length())
                        for (index in 0 until profiles.length()) {
                            val item = profiles.optJSONObject(index)
                                ?: throw IllegalArgumentException("ساختار کانفیگ نامعتبر است")
                            val raw = item.optString("profile_json")
                            if (raw.isBlank() || raw.length > TransferProtocol.MAX_PROFILE_JSON) {
                                throw IllegalArgumentException("اطلاعات کانفیگ معتبر نیست")
                            }
                            val profile = JsonUtil.fromJsonSafe(raw, ProfileItem::class.java)
                                ?: throw IllegalArgumentException("خواندن کانفیگ ناموفق بود")
                            if (profile.configType.isComplexType()) {
                                throw IllegalArgumentException("گروه‌ها و زنجیره‌ها در این نسخه قابل انتقال نیستند")
                            }
                            incoming.add(profile)
                        }

                        val result = TransferProtocol.importAtomically(incoming, deviceName)
                        val response = JSONObject()
                            .put("ok", true)
                            .put("received", result.received)
                            .put("duplicates", result.duplicates)
                            .put("device_name", session.deviceName)
                        TransferProtocol.writeFrame(output, TransferProtocol.encrypt(response.toString().toByteArray(Charsets.UTF_8), session))
                        return result.copy(deviceName = deviceName)
                    } catch (error: Exception) {
                        rejectedConnections++
                        runCatching {
                            val response = JSONObject()
                                .put("ok", false)
                                .put("message", error.message ?: "انتقال ناموفق بود")
                            TransferProtocol.writeFrame(
                                DataOutputStream(client.getOutputStream()),
                                TransferProtocol.encrypt(response.toString().toByteArray(Charsets.UTF_8), session)
                            )
                        }
                        if (rejectedConnections >= TransferProtocol.MAX_REJECTED_CONNECTIONS) throw error
                    }
                }
            } catch (error: Exception) {
                if (closed) break
                if (rejectedConnections >= TransferProtocol.MAX_REJECTED_CONNECTIONS) throw error
            }
        }
        throw IllegalStateException(
            if (closed) "نشست انتقال بسته شد" else "زمان QR به پایان رسید؛ یک QR جدید بسازید"
        )
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
    }
}

internal object TransferProtocol {
    const val QR_KIND = "v2plas-transfer"
    const val QR_TTL_MS = 120_000L
    const val MAX_PROFILES = 100
    internal const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    internal const val MAX_PROFILE_JSON = 128 * 1024
    internal const val MAX_REJECTED_CONNECTIONS = 5
    private const val HISTORY_PREFS = "v2plas_transfer_history_v1"
    private const val HISTORY_KEY = "entries"

    fun loadProfiles(): List<TransferProfile> {
        val subscriptionIds = linkedSetOf(AppConfig.DEFAULT_SUBSCRIPTION_ID)
        MmkvManager.decodeSubscriptions().forEach { subscriptionIds.add(it.guid) }
        val seen = HashSet<String>()
        val result = ArrayList<TransferProfile>()
        subscriptionIds.forEach { subscriptionId ->
            MmkvManager.decodeServerList(subscriptionId).forEach guidLoop@{ guid ->
                val profile = MmkvManager.decodeServerConfig(guid) ?: return@guidLoop
                if (profile.configType.isComplexType()) return@guidLoop
                val fingerprint = fingerprint(profile)
                if (seen.add(fingerprint)) {
                    result.add(TransferProfile(guid, profile.remarks.ifBlank { profile.server ?: "کانفیگ" }, profile))
                }
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    fun createReceiver(context: Context): TvTransferReceiver {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 4)
        val host = try {
            findLanIpv4(context)
        } catch (error: Exception) {
            runCatching { server.close() }
            throw error
        }
        val now = System.currentTimeMillis()
        val expiresAtElapsedRealtime = SystemClock.elapsedRealtime() + QR_TTL_MS
        val tokenBytes = ByteArray(32)
        SecureRandom().nextBytes(tokenBytes)
        val session = TransferSession(
            host = host,
            port = server.localPort,
            sessionId = UUID.randomUUID().toString(),
            token = Base64.encodeToString(tokenBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            expiresAt = now + QR_TTL_MS,
            deviceName = deviceName(),
        )
        val qr = JSONObject()
            .put("v", 1)
            .put("kind", QR_KIND)
            .put("host", session.host)
            .put("port", session.port)
            .put("session", session.sessionId)
            .put("token", session.token)
            .put("expires", session.expiresAt)
            .put("device", session.deviceName)
            .toString()
        return TvTransferReceiver(server, session, qr, expiresAtElapsedRealtime)
    }

    fun parseSession(qrText: String): TransferSession {
        require(qrText.length <= 2_048) { "QR نامعتبر است" }
        val json = JSONObject(qrText)
        require(json.optInt("v") == 1 && json.optString("kind") == QR_KIND) {
            "این QR مربوط به انتقال V2 Plas نیست"
        }
        val host = json.optString("host")
        require(host.matches(Regex("[0-9a-fA-F:.]{3,64}"))) { "آدرس تلویزیون معتبر نیست" }
        val address = runCatching { InetAddress.getByName(host) }.getOrNull()
        require(address is Inet4Address && (address.isSiteLocalAddress || address.isLinkLocalAddress)) {
            "این QR به یک آدرس شبکه محلی معتبر اشاره نمی‌کند"
        }
        val port = json.optInt("port")
        require(port in 1..65535) { "درگاه ارتباطی معتبر نیست" }
        val sessionId = json.optString("session")
        require(runCatching { UUID.fromString(sessionId) }.isSuccess) { "شناسه نشست معتبر نیست" }
        val token = json.optString("token")
        val decodedToken = runCatching { Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
        require(decodedToken?.size == 32) { "کلید نشست معتبر نیست" }
        // The phone clock is not authoritative. The TV enforces the one-time
        // session TTL using its own monotonic clock when accepting the transfer.
        val expires = json.optLong("expires")
        require(expires > 0L) { "زمان نشست QR معتبر نیست" }
        return TransferSession(
            host, port, sessionId, token, expires,
            json.optString("device", "Android TV").take(64)
        )
    }

    fun send(
        context: Context,
        session: TransferSession,
        profiles: List<TransferProfile>,
        onStage: (String, Float) -> Unit,
    ): TransferResult {
        require(profiles.isNotEmpty()) { "حداقل یک کانفیگ انتخاب کنید" }
        require(profiles.size <= MAX_PROFILES) { "حداکثر $MAX_PROFILES کانفیگ در هر نوبت قابل انتقال است" }

        onStage("آماده‌سازی کانفیگ‌ها", 0.15f)
        val payloadProfiles = JSONArray()
        profiles.forEach { item ->
            val json = JsonUtil.toJson(item.profile)
            require(json.length <= MAX_PROFILE_JSON) { "حجم یکی از کانفیگ‌ها بیش از حد مجاز است" }
            payloadProfiles.put(
                JSONObject()
                    .put("name", item.name.take(100))
                    .put("profile_json", json)
            )
        }
        val payload = JSONObject()
            .put("device_name", deviceName())
            .put("profiles", payloadProfiles)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_FRAME_BYTES - 64) { "حجم بسته انتقال بیش از حد مجاز است" }

        onStage("رمزگذاری امن اطلاعات", 0.40f)
        val encrypted = encrypt(payload, session)
        onStage("ارسال به ${session.deviceName}", 0.70f)
        Socket().use { socket ->
            bindSocketToLocalNetwork(context, socket, session.host)
            socket.connect(java.net.InetSocketAddress(session.host, session.port), 7_000)
            socket.soTimeout = 15_000
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            writeFrame(output, encrypted)
            val response = JSONObject(String(decrypt(readFrame(input), session), Charsets.UTF_8))
            if (!response.optBoolean("ok")) {
                throw IllegalStateException(response.optString("message", "تلویزیون انتقال را نپذیرفت"))
            }
            onStage("انتقال کامل شد", 1f)
            return TransferResult(
                received = response.optInt("received"),
                duplicates = response.optInt("duplicates"),
                deviceName = response.optString("device_name", session.deviceName),
            )
        }
    }

    fun recordHistory(context: Context, direction: String, device: String, count: Int, duplicates: Int, success: Boolean) {
        runCatching {
            val prefs = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            val old = JSONArray(prefs.getString(HISTORY_KEY, "[]") ?: "[]")
            val entries = JSONArray()
            entries.put(
                JSONObject()
                    .put("time", System.currentTimeMillis())
                    .put("direction", direction)
                    .put("device", device.take(64))
                    .put("count", count)
                    .put("duplicates", duplicates)
                    .put("success", success)
            )
            for (i in 0 until minOf(old.length(), 19)) entries.put(old.getJSONObject(i))
            prefs.edit().putString(HISTORY_KEY, entries.toString()).apply()
        }
    }

    fun readHistory(context: Context): List<String> {
        val prefs = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val entries = runCatching { JSONArray(prefs.getString(HISTORY_KEY, "[]") ?: "[]") }.getOrNull()
            ?: return emptyList()
        return (0 until minOf(entries.length(), 5)).mapNotNull { index ->
            val item = entries.optJSONObject(index) ?: return@mapNotNull null
            val direction = if (item.optString("direction") == "in") "دریافت" else "ارسال"
            val status = if (item.optBoolean("success")) "موفق" else "ناموفق"
            "$direction • ${item.optString("device")} • ${item.optInt("count")} مورد • $status"
        }
    }

    internal fun importAtomically(profiles: List<ProfileItem>, deviceName: String): TransferResult {
        val existing = HashSet<String>()
        val ids = linkedSetOf(AppConfig.DEFAULT_SUBSCRIPTION_ID)
        MmkvManager.decodeSubscriptions().forEach { ids.add(it.guid) }
        ids.forEach { id ->
            MmkvManager.decodeServerList(id).forEach { guid ->
                MmkvManager.decodeServerConfig(guid)?.let { existing.add(fingerprint(it)) }
            }
        }

        var duplicates = 0
        val toAdd = profiles.filter { profile ->
            if (!existing.add(fingerprint(profile))) {
                duplicates++
                false
            } else true
        }
        val inserted = ArrayList<String>()
        try {
            toAdd.forEach { profile ->
                val localProfile = profile.copy(
                    subscriptionId = AppConfig.DEFAULT_SUBSCRIPTION_ID,
                    addedTime = System.currentTimeMillis(),
                )
                inserted.add(MmkvManager.encodeServerConfig(UUID.randomUUID().toString(), localProfile))
            }
        } catch (error: Exception) {
            inserted.forEach { guid -> runCatching { MmkvManager.removeServer(guid) } }
            throw IllegalStateException("ذخیره گروهی انجام نشد؛ تغییرات ناقص بازگردانده شد", error)
        }
        return TransferResult(inserted.size, duplicates, deviceName)
    }

    private fun fingerprint(profile: ProfileItem): String {
        val canonical = JsonUtil.toJson(profile.duplicateIdentity())
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun localNetworks(context: Context): List<Pair<Network, LinkProperties>> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.allNetworks.mapNotNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            val isLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isLan) return@mapNotNull null
            val links = manager.getLinkProperties(network) ?: return@mapNotNull null
            network to links
        }.sortedBy { (network, _) ->
            val caps = manager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 0 else 1
        }
    }

    private fun findLanIpv4(context: Context): String {
        localNetworks(context).forEach { (_, links) ->
            links.linkAddresses.asSequence()
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }
                ?.let { return it.hostAddress ?: "" }
        }
        // Fallback for vendor Android builds that do not expose LinkProperties.
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { iface ->
                when {
                    iface.name.startsWith("wlan", true) || iface.name.startsWith("wifi", true) -> 0
                    iface.name.startsWith("eth", true) || iface.name.startsWith("en", true) -> 1
                    else -> 2
                }
            }
        interfaces.forEach { iface ->
            if (iface.name.startsWith("tun", true) || iface.name.startsWith("ppp", true) ||
                iface.name.startsWith("wg", true) || iface.name.startsWith("tap", true)
            ) return@forEach
            iface.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull {
                !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress
            }?.let { return it.hostAddress ?: "" }
        }
        throw IllegalStateException("اتصال Wi-Fi یا شبکه محلی پیدا نشد؛ گوشی و تلویزیون را به یک شبکه وصل کنید")
    }

    private fun bindSocketToLocalNetwork(context: Context, socket: Socket, host: String) {
        val target = runCatching { InetAddress.getByName(host) }.getOrNull()
            ?: throw IllegalStateException("آدرس تلویزیون معتبر نیست")
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = localNetworks(context)
        val matching = candidates.firstOrNull { (_, links) ->
            links.linkAddresses.any { link ->
                val local = link.address
                if (local !is Inet4Address || target !is Inet4Address) false
                else {
                    val prefix = link.prefixLength.coerceIn(0, 32)
                    val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                    val localInt = java.nio.ByteBuffer.wrap(local.address).int
                    val targetInt = java.nio.ByteBuffer.wrap(target.address).int
                    (localInt and mask) == (targetInt and mask)
                }
            }
        } ?: candidates.firstOrNull()
        if (matching != null) {
            // Bind explicitly to Wi-Fi/Ethernet so an enabled VPN does not
            // capture this local device-to-device transfer socket.
            matching.first.bindSocket(socket)
        }
    }

    private fun deviceName(): String = android.os.Build.MODEL.take(64).ifBlank { "Android" }

    internal fun encrypt(plain: ByteArray, session: TransferSession): ByteArray {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(session), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(session.sessionId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plain)
        return nonce + encrypted
    }

    internal fun decrypt(frame: ByteArray, session: TransferSession): ByteArray {
        require(frame.size in 28..MAX_FRAME_BYTES) { "بسته رمزگذاری‌شده نامعتبر است" }
        val nonce = frame.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(session), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(session.sessionId.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(frame.copyOfRange(12, frame.size))
    }

    private fun deriveKey(session: TransferSession): ByteArray {
        val ikm = Base64.decode(session.token, Base64.URL_SAFE or Base64.NO_WRAP)
        val salt = session.sessionId.toByteArray(Charsets.UTF_8)
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        expand.update("V2Plas local transfer v1".toByteArray(Charsets.UTF_8))
        expand.update(1.toByte())
        return expand.doFinal().copyOf(32)
    }

    internal fun readFrame(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 28..MAX_FRAME_BYTES) { "اندازه بسته انتقال نامعتبر است" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return bytes
    }

    internal fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
        require(bytes.size in 28..MAX_FRAME_BYTES) { "اندازه بسته انتقال نامعتبر است" }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
