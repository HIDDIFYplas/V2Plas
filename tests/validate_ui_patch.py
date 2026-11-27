from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "overlay" / "V2PlasApp.kt"
SMART_FEATURES = ROOT / "overlay" / "SmartFeatures.kt"
TOP_BAR = ROOT / "overlay" / "MainTopBar.kt"
REPO = ROOT / "overlay" / "FreeConfigRepository.kt"
ANNOUNCEMENT = ROOT / "overlay" / "RemoteAnnouncement.kt"
ENDPOINT = ROOT / "overlay" / "FeedEndpoint.kt"
SERVICE_MONITOR = ROOT / "overlay" / "FreeQuotaServiceMonitor.kt"
PATCH = ROOT / "overlay" / "patch.py"
FLAG_ICON = ROOT / "overlay" / "FlagIcon.kt"
ABOUT = ROOT / "overlay" / "AboutActivity.kt"
WORKFLOW = ROOT / ".github" / "workflows" / "build-v2plas.yml"
WORKER = ROOT / "worker" / "V2Plas-worker-pages.js"
HEADERS = ROOT / "public" / "_headers"
ANNOUNCEMENT_JSON = ROOT / "announcement.json"

source = APP.read_text(encoding="utf-8")
repo = REPO.read_text(encoding="utf-8")
announcement = ANNOUNCEMENT.read_text(encoding="utf-8")
endpoint = ENDPOINT.read_text(encoding="utf-8")
service_monitor = SERVICE_MONITOR.read_text(encoding="utf-8")
patch = PATCH.read_text(encoding="utf-8")
flag_icon = FLAG_ICON.read_text(encoding="utf-8")
about = ABOUT.read_text(encoding="utf-8")
workflow = WORKFLOW.read_text(encoding="utf-8")
worker = WORKER.read_text(encoding="utf-8")
headers = HEADERS.read_text(encoding="utf-8")
announcement_config = ANNOUNCEMENT_JSON.read_text(encoding="utf-8")
transfer_activity = (ROOT / "overlay" / "TransferActivity.kt").read_text(encoding="utf-8")
transfer_protocol = (ROOT / "overlay" / "TransferProtocol.kt").read_text(encoding="utf-8")
main_import_menu = (ROOT / "overlay" / "MainImportMenu.kt").read_text(encoding="utf-8")

checks = {
    "home hotspot button uses compact white path-based Wi-Fi arcs and white VPN label": "val outerArc = Path()" in source and "val middleArc = Path()" in source and "val innerArc = Path()" in source and "text = \"VPN\"" in source and "color = Color.White" in source and "homeHotspotShareButtonScale" in source,
    "app startup shares the persisted feed rate-limit gate": "FreeConfigRepository.refreshIfDue()" in source,
    "free-list entry and manual refresh go through the rate-limited check": "manual = refreshKey > 0" in source and "FreeConfigRepository.refreshIfDue(manual = refreshKey > 0)" in source,
    "manual refresh follows original 30-second interval": "MANUAL_MIN_INTERVAL_MS = 30L * 1000L" in repo and "manual -> MANUAL_MIN_INTERVAL_MS" in repo,
    "failed attempts back off for minutes, not hours": "FAILURE_BACKOFF_MS = 5L * 60L * 1000L" in repo and "lastAttemptFailed -> FAILURE_BACKOFF_MS" in repo,
    "free connection attempts are cache-only": "val payload = FreeConfigRepository.cachedResult()" in source and "FreeConfigRepository.fetch()" not in source,
    "Worker API URL was removed from Android": "/api/public/configs" not in repo and "workers.dev" not in repo,
    "static feed endpoint is injected by the workflow": "__V2PLAS_FEED_BASE_URL__" in endpoint and "vars.V2PLAS_FEED_BASE_URL" in workflow,
    "feed endpoint is HTTPS-validated by build workflow": "V2PLAS_FEED_BASE_URL must be a valid HTTPS" in workflow,
    "feed attempts are persisted before direct network I/O": repo.index("PREF_LAST_ATTEMPT, nowMillis.toString()") < repo.index("manifestUrl.openConnection()"),
    "feed checks are rate-limited after success": "SUCCESS_INTERVAL_MS = 15L * 60L * 1000L" in repo and "PREF_LAST_ATTEMPT" in repo and "6L * 60L * 60L * 1000L" not in repo,
    "manifest conditional request uses ETag": "If-None-Match" in repo and "HTTP_NOT_MODIFIED" in repo,
    "unchanged manifest version skips full payload download": repo.index("version.equals(cachedVersion") < repo.index("URL(\"$baseUrl/$payloadName\")"),
    "payload size and SHA-256 are validated before cache replacement": "payload_size_mismatch" in repo and "payload_hash_mismatch" in repo and repo.index("payload_hash_mismatch") < repo.index("MmkvManager.encodeSettings(CACHE_KEY"),
    "payload version must match manifest version": "payload_version_mismatch" in repo,
    "feed format contract is checked": 'manifest.optInt("format", 0) != 2' in repo and 'root.optInt("feed_format", 0) != 2' in repo,
    "old cache survives failed/invalid downloads": "catch (e: Exception) {" in repo and "cachedResult(reason)" in repo,
    "v2 local cache migrates to the new validated cache": "PREVIOUS_CACHE_KEY = \"v2plas_free_payload_cache_v2\"" in repo and "MmkvManager.encodeSettings(CACHE_KEY, Gson().toJson(migrated))" in repo,
    "ETag is not trusted when there is no valid local snapshot": "if (hasValidCachedSnapshot)" in repo and "not_modified_without_cache" in repo,
    "network cache-busting query is not used": "?t=" not in repo,
    "announcement fetch is throttled for twelve hours": "12L * 60L * 60L * 1000L" in announcement and "PREF_LAST_FETCH_ATTEMPT" in announcement,
    "announcement response is cached locally": "PREF_CACHED_JSON" in announcement and "gson.toJson(parsed)" in announcement,
    "announcement supports conditional ETag": "If-None-Match" in announcement and "HTTP_NOT_MODIFIED" in announcement,
    "announcement sends ETag only when a local copy exists": "if (cached != null)" in announcement,
    "announcement does not add timestamp query strings": "?t=" not in announcement,
    "announcement uses the same static Pages base URL as the feed": "V2PlasFeedEndpoint.BASE_URL" in announcement and "raw.githubusercontent.com" not in announcement and "jsdelivr" not in announcement,
    "announcement checks only once every twelve hours": "FETCH_INTERVAL_MS" in announcement and "PREF_LAST_FETCH_ATTEMPT" in announcement,
    "remote announcement display interval is twelve hours": '"intervalHours": 12' in announcement_config,
    "free profiles are selected one at a time": "FreeConfigRepository.selectConfig(item)" in source,
    "free profile parsing runs off the main UI thread": "suspend fun selectConfig(item: FreeConfigItem)" in repo and "Dispatchers.IO" in repo,
    "free selection is launched asynchronously by the screen": "selectionScope.launch" in source,
    "quota timestamps and byte baselines use MMKV string storage": "decodeSettingsString(START_ELAPSED)?.toLongOrNull()" in source and "now.toString()" in source,
    "quota session storage serializes concurrent UI and daemon access": source.count("@Synchronized") >= 3,
    "bulk add-to-personal-servers action is absent": "افزودن کانفیگ‌ها به برنامه" not in source and "importIntoApp(configs)" not in source,
    "free profile storage uses its isolated subscription id": "v2plas_free_remote_group" in repo,
    "personal server screen hides the free-only group": 'filterNot { it.id ==' in patch and 'v2plas_free_remote_group' in patch,
    "free profile is excluded even from an all-servers list": 'row.profile.subscriptionId == "v2plas_free_remote_group"' in patch and 'val personalRows = remember(groupState.rows)' in patch,
    "free profile is selected on the main screen": "mainViewModel.updateSelectedGuid(guid)" in source,
    "free display name hides connection details": "FreeConfigRepository.selectedDisplayName(guid)" in source,
    "free-only connect gate requires an enabled service": "!payload.enabled || !settings.freeEnabled" in source,
    "free-only connect gate requires a configured ad when ads are enabled": "تبلیغ فعالی برای اتصال رایگان" in source,
    "ad dialog disables back and outside dismissal": "dismissOnBackPress = false" in source and "dismissOnClickOutside = false" in source,
    "ad display has a fixed 10-second countdown": "for (second in 10 downTo 1)" in source,
    "a separate 10-second connection delay follows the ad": source.count("for (second in 10 downTo 1)") >= 2 and "FreeConnectDelayDialog" in source,
    "new quota begins only after the ad and delay": source.index("FreeQuotaSessionStore.prepareNewConnection()") > source.index("showingConnectDelay = false"),
    "every free reconnection re-enters the ad gate": "bypassNextFreeStart" not in source,
    "free volume is combined upload plus download": "stats.rxSession + stats.txSession" in source,
    "free time is shown as an animated remaining-time countdown": "ConnectionTimer(stats.freeRemainingMs)" in source and "زمان باقی‌مانده" in source,
    "free session is stopped at time/volume limit": source.count("latestLimitCallback(message)") >= 2 and source.count("stats.quotaMessage = message") >= 2 and "FreeQuotaSessionStore.finish()" in source,
    "VPN core starts the independent quota monitor": "FreeQuotaServiceMonitor.start(config.subscriptionId)" in patch,
    "VPN core stops the monitor with service shutdown": "FreeQuotaServiceMonitor.stop()" in patch,
    "service monitor runs independently of Compose": "CoroutineScope(SupervisorJob() + Dispatchers.IO)" in service_monitor,
    "service monitor skips personal profiles": "if (subscriptionId != FREE_SUBSCRIPTION_ID)" in service_monitor,
    "service monitor reads cached admin limits": "FreeConfigRepository.readCurrentSettings()" in service_monitor,
    "service monitor resumes the same free session during core reload": "FreeQuotaSessionStore.startOrResume(Process.myUid())" in service_monitor,
    "service monitor enforces both free limits": "timeReached" in service_monitor and "volumeReached" in service_monitor,
    "service monitor requests actual VPN service stop": "val control = CoreServiceManager.serviceControl?.get()" in service_monitor and "control.stopService()" in service_monitor,
    "personal sessions retain elapsed-time display with separately animated units": "ConnectionTimer(stats.tick - stats.connectedSince)" in source and "AnimatedTimerUnit(seconds.toString().padStart(2, '0')," in source,
    "personal limit-setting refresh cannot reset personal session timer": "if (isFreeSession) limits.timeLimitMillis else 0L" in source and "if (isFreeSession) limits.volumeLimitBytes else 0L" in source,
    "personal server navigation is blocked while a free tunnel is active": "برای جلوگیری از تغییر ناخواستهٔ اتصال رایگان" in source,
    "exit IP details are hidden for free sessions": "if (isRunning && !isFreeSelected)" in source,
    "free sessions do not trigger external exit-IP lookup": "LaunchedEffect(isRunning, guid, isFreeSelected)" in source and "if (!isRunning || isFreeSelected)" in source,
    "service stop retries during a transient control-reference gap": "repeat(10)" in service_monitor and "delay(300L)" in service_monitor,
    "Worker snapshot format is versioned": "feed_format: 2" in worker and "format: 2" in worker,
    "Worker payload cap matches Android bounded reader": "payloadBytes > 2 * 1024 * 1024" in worker and "MAX_RESPONSE_BYTES = 2 * 1024 * 1024" in repo,
    "Worker snapshot version is content-derived": "const version = await sha256Hex(JSON.stringify(core))" in worker,
    "Worker manifest includes payload integrity metadata": "payload_sha256: payloadSha256" in worker and "payload_bytes: payloadBytes" in worker,
    "Worker avoids GitHub commits when feed content is unchanged": "settingsNow.public_feed_version === feed.version" in worker,
    "Worker creates only the static manifest and payload snapshot": 'path: "public/manifest.json"' in worker and 'path: "public/configs.json"' in worker,
    "Worker serializes concurrent publishes with a D1 lease": "public_feed_publish_lock" in worker and "publish_in_progress" in worker,
    "Pages static headers use ETag revalidation for manifest": "max-age=0, must-revalidate" in headers,
    "Pages payload and announcement revalidate to prevent stale content after a publish": headers.count("max-age=0, must-revalidate") >= 3,
    "public announcement snapshot exists": (ROOT / "public" / "announcement.json").is_file(),
    "build workflow remains pinned to reviewed v2rayNG revision": "aa4b05503a504a3f2e43818e6ee8ebf1caaa3df0" in workflow,
    "x86_64 APK naming is handled before x86": workflow.index('*_x86_64)') < workflow.index('*_x86)'),
    "no broken legacy architecture suffix parsing remains": 'arch="${base##*_}"' not in workflow,
    "About page keeps source repository button": 'V2PLAS_SOURCE_URL = "https://github.com/HIDDIFYplas/V2Plas"' in about,
    "About page keeps official Telegram button": 'V2PLAS_TELEGRAM_URL = "https://t.me/HIDDIFY_plas"' in about,
    "version label is 1.1.04 in Android builds": 'versionName = "1.1.04"' in workflow and '1.1.02' not in workflow,
    "release APK filename carries 1.1.04": 'out/V2-Plas-1.1.04-${arch}.apk' in workflow,
    "announcement button carries 1.1.04": 'دانلود نسخه 1.1.04' in announcement_config,
    "server title and import icons use redesigned top bar": (ROOT / "overlay" / "MainTopBar.kt").is_file() and 'title = "کانفیگ‌ها"' in (ROOT / "overlay" / "MainTopBar.kt").read_text(encoding="utf-8"),
    "search is next to title and protocol actions use the pinned enum contract": 'ToolbarActionButton(V2PlasToolbarGlyph.Search' in (ROOT / "overlay" / "MainTopBar.kt").read_text(encoding="utf-8") and 'com.v2ray.ang.enums.EConfigType' in (ROOT / "overlay" / "MainTopBar.kt").read_text(encoding="utf-8") and 'MainAction.ImportManually(type.value)' in (ROOT / "overlay" / "MainTopBar.kt").read_text(encoding="utf-8"),
    "server and home use the same bundled circular country flag component": 'V2PlasFlag(countryCodeFromText("${row.remarks} ${row.profile.server}")' in patch and 'V2PlasFlag(countryCodeFromText(country)' in source and 'modifier.clip(CircleShape)' in flag_icon and 'painterResource(id = flagResource)' in flag_icon and 'scaleX = 1.30f' not in patch,
    "QR and clipboard imports are direct working actions outside plus menu": 'onAction(MainAction.ImportQRcode)' in (ROOT / "overlay" / "MainTopBar.kt").read_text(encoding="utf-8") and 'onAction(MainAction.ImportClipboard)' in (ROOT / "overlay" / "MainTopBar.kt").read_text(encoding="utf-8"),
    "subscription card retains its upstream refresh and link actions": (ROOT / "overlay" / "MainSubscriptionInfo.kt").is_file() and 'onClick = onRefresh' in (ROOT / "overlay" / "MainSubscriptionInfo.kt").read_text(encoding="utf-8"),
    "only the selected profile endpoint IP can be revealed and it is fully blurred by default": 'originalIpVisible = originalIpVisible' in source and 'profile?.server?.takeIf { isIpLiteral(it) }' in source and 'onRevealOriginalIp = { originalIpVisible = true }' in source and '.blur((12f * (1f - reveal)).dp)' in source and 'alpha = 0.025f + 0.975f * reveal' in source,
    "output IP card remains a separate exit lookup display and labels IPv4/IPv6": 'IP خروجی · IPv6' in source and 'IP خروجی · IPv4' in source and 'fetchExitLocation()' in source and "exitIp?.contains(':')" in source,
    "main ping test is single-flight and waits for real connection-test event": 'pingTesting = true' in source and 'pingTesting = false' in source and 'MainStatus.ConnectionTest' in source,
    "ping labels and value share the five requested thresholds": all(token in source for token in ['delayMs < 80L', 'delayMs < 200L', 'delayMs < 300L', 'delayMs < 500L']) and source.count('style = pingGlowStyle') >= 2,
    "connection spinner is one-line without trailing ellipsis": 'isConnecting -> "درحال اتصال"' in source and 'softWrap = false' in source,
    "power glyph morphs only from actual running state": 'connected = isRunning' in source and 'targetValue = if (connected) 1f else 0f' in source,
    "download and upload arrows stay static while disconnected or idle": 'val transferActive = isConnected && bytesPerSecond > 0L' in source and 'if (active) {' in source and 'MovingTransferArrow(up = up, color = color)' in source and 'StaticTransferArrow(up = up, color = color)' in source and 'rememberInfiniteTransition(label = if (up) "uploadArrowActive" else "downloadArrowActive")' in source,
    "stars do not change their coordinates during animation": 'center = Offset(x * size.width, y * size.height)' in source and 'val drift =' not in source and 'val starY =' not in source,
    "bottom navigation uses spring touch feedback": 'collectIsPressedAsState' in source and 'navItemScale' in source and 'Spring.DampingRatioMediumBouncy' in source,
    "group tabs use glass wrapper": 'glass subscription group selector' in patch and 'Brush.linearGradient(listOf(Color(0xDD253D70)' in patch and 'import androidx.compose.foundation.layout.fillMaxWidth' in patch,
    "home server strip grows, has real spring feedback, and keeps IP in its own lower line": 'Modifier.fillMaxWidth().graphicsLayer { scaleX = cardScale; scaleY = cardScale }' in source and 'interactionSource = interactionSource' in source and 'Modifier.fillMaxWidth().padding(vertical = 3.dp)' in source and 'originalIp' in source,
    "all server card actions are in a single vertical rail with unchanged callbacks": 'ServerFavoriteButton(' in patch and 'actions.share(row.guid, row.profile)' in patch and 'actions.edit(row.guid, row.profile)' in patch and 'actions.remove(row.guid, row.remarks)' in patch and 'Arrangement.spacedBy(3.dp)' in patch,
    "favorite and server action icons have glass frames and spring feedback": 'serverFavoriteSpring' in patch and 'serverActionSpring' in patch and '.background(Brush.verticalGradient' in patch and '.border(1.dp' in patch,
    "share, edit and delete use new vector-drawn accessible glyphs": 'ServerActionGlyph.Share' in patch and 'ServerActionGlyph.Edit' in patch and 'ServerActionGlyph.Delete' in patch and 'drawCircle(c' in patch and 'drawPath(path, c)' in patch and 'contentDescription = description' in patch and 'painterResource(icon)' not in patch,
    "server card ping is inside a latency-colored frame with requested thresholds": 'server ping badge color-matched frame' in patch and 'row.testDelayMillis < 80L' in patch and 'row.testDelayMillis < 200L' in patch and 'row.testDelayMillis < 300L' in patch and 'row.testDelayMillis < 500L' in patch,
    "all country aliases remain available with a neutral unknown fallback": all(f' to "{code}"' in flag_icon for code in ["SE","DE","NL","FR","US","GB","TR","IR","AU","NZ","SA","HK","TW","TH","VN","MY","MX","ZA","AR","RS","GE","CY","EE","LV","LT","HR","SK","SI","BY","CZ"]) and 'else ->' not in flag_icon,
    "full ISO flag generator includes 249 countries plus Kosovo": '249 ISO countries + XK' in (ROOT / "scripts" / "generate_country_flags.py").read_text(encoding="utf-8") and len(re.findall(r'"[a-z]{2}"', (ROOT / "scripts" / "generate_country_flags.py").read_text(encoding="utf-8"))) == 250,
    "flag generation is part of the GitHub build before Gradle": 'Generate all circular country flag resources' in workflow and 'python3 plas/scripts/generate_country_flags.py' in workflow and workflow.index('Generate all circular country flag resources') < workflow.index('Build release APK'),
    "flag resources are bundled offline and no emoji flags are used": 'getIdentifier(' in flag_icon and 'Character.toChars' not in flag_icon and 'drawable-nodpi' in workflow,
    "power button touch response is spring animated": 'label = "powerButtonScale"' in source and 'Spring.DampingRatioLowBouncy' in source and 'pressed -> 0.90f' in source,
    "TV focus indication uses the modern node API and genuine focus interactions": "IndicationNodeFactory" in (ROOT / "overlay" / "V2PlasTvFocus.kt").read_text(encoding="utf-8") and "Modifier.Node" in (ROOT / "overlay" / "V2PlasTvFocus.kt").read_text(encoding="utf-8") and "FocusInteraction.Focus" in (ROOT / "overlay" / "V2PlasTvFocus.kt").read_text(encoding="utf-8") and "FocusInteraction.Unfocus" in (ROOT / "overlay" / "V2PlasTvFocus.kt").read_text(encoding="utf-8") and "rememberUpdatedInstance" not in (ROOT / "overlay" / "V2PlasTvFocus.kt").read_text(encoding="utf-8"),
    "TV selection halo is translucent and drawn inside existing bounds": "Color.White.copy(alpha = 0.045f * alpha)" in (ROOT / "overlay" / "V2PlasTvFocus.kt").read_text(encoding="utf-8") and "drawRoundRect" in (ROOT / "overlay" / "V2PlasTvFocus.kt").read_text(encoding="utf-8") and "Modifier.size" not in (ROOT / "overlay" / "V2PlasTvFocus.kt").read_text(encoding="utf-8"),
    "TV detection uses leanback/television features, not screen dimensions": "FEATURE_LEANBACK" in (ROOT / "overlay" / "V2PlasTvFocus.kt").read_text(encoding="utf-8") and "FEATURE_TELEVISION" in (ROOT / "overlay" / "V2PlasTvFocus.kt").read_text(encoding="utf-8") and "screenWidthDp" not in (ROOT / "overlay" / "V2PlasTvFocus.kt").read_text(encoding="utf-8"),
    "focus indication is supplied only on TV and normal Android keeps its original indication": "if (isTelevision) V2PlasTvFocusIndication else LocalIndication.current" in workflow and "if (isTelevision) V2PlasTvFocusIndication else null" in workflow,
    "TV focus halo uses project-compatible custom indication without unsupported Material3 ripple configuration APIs": "LocalIndication provides activeIndication" in workflow and "LocalV2PlasTvFocusIndication provides tvFocusIndication" in workflow and "RippleConfiguration" not in workflow and "LocalRippleThemeConfiguration" not in workflow,
    "custom ripple-free glass controls opt into TV focus feedback": "indication = LocalV2PlasTvFocusIndication.current" in source and "indication = LocalV2PlasTvFocusIndication.current" in (ROOT / "overlay" / "MainTopBar.kt").read_text(encoding="utf-8") and "LocalV2PlasTvFocusIndication.current" in patch,
    "Android TV is added as an optional Leanback launch target": "android.software.leanback" in (ROOT / "overlay" / "tv_support.py").read_text(encoding="utf-8") and "android.intent.category.LEANBACK_LAUNCHER" in (ROOT / "overlay" / "tv_support.py").read_text(encoding="utf-8"),
    "touchscreen remains optional so Android TV devices can install the app": '"android.hardware.touchscreen"' in (ROOT / "overlay" / "tv_support.py").read_text(encoding="utf-8") and '"required", "false"' in (ROOT / "overlay" / "tv_support.py").read_text(encoding="utf-8"),
    "TV launcher and old-launcher resources are branded V2 Plas": "@mipmap/ic_launcher" in (ROOT / "overlay" / "tv_support.py").read_text(encoding="utf-8") and "ic_launcher_round.xml" in (ROOT / "overlay" / "tv_support.py").read_text(encoding="utf-8") and "v2plas_tv_banner.xml" in (ROOT / "overlay" / "tv_support.py").read_text(encoding="utf-8"),
    "TV support runs after the V2 Plas app icon is generated": "python3 plas/overlay/tv_support.py" in workflow and workflow.index("python3 plas/overlay/tv_support.py") > workflow.index("ic_v2plas_foreground.xml"),
    "phone and TV share one transfer activity with feature-based TV detection": "FEATURE_LEANBACK" in transfer_activity and "FEATURE_TELEVISION" in transfer_activity and "TransferActivity" in patch,
    "TV displays a temporary QR while phone launches the existing QR scanner": "QRCodeDecoder.createQRCode(newReceiver.qrText" in transfer_activity and "QRCodeScannerHelper(this)" in transfer_activity,
    "QR session is short-lived and one-time with a 256-bit random token": "QR_TTL_MS = 120_000L" in transfer_protocol and "ByteArray(32)" in transfer_protocol and "UUID.randomUUID()" in transfer_protocol,
    "transfer payload uses authenticated AES-GCM encryption with session AAD": "AES/GCM/NoPadding" in transfer_protocol and "cipher.updateAAD(session.sessionId" in transfer_protocol,
    "transfer frame and profile sizes are bounded": "MAX_FRAME_BYTES = 2 * 1024 * 1024" in transfer_protocol and "MAX_PROFILE_JSON = 128 * 1024" in transfer_protocol,
    "received profiles are validated before storage and rolled back on failure": "importAtomically(incoming, deviceName)" in transfer_protocol and "MmkvManager.removeServer(guid)" in transfer_protocol,
    "duplicate detection uses the profile connection identity": "profile.duplicateIdentity()" in transfer_protocol,
    "transfer history stores only device/count/status metadata": "HISTORY_PREFS" in transfer_protocol and "profile_json" not in transfer_protocol[transfer_protocol.find("fun recordHistory"):transfer_protocol.find("fun readHistory")],
    "home header exposes the device-aware transfer action": "onTransfer = { context.startActivity(Intent(context, TransferActivity::class.java)) }" in source and "onTransfer: () -> Unit" in source,
    "single-profile share panel includes Send to TV": "ServerMenuAction.SendToTv" in main_import_menu and "EXTRA_SINGLE_GUID, guid" in main_import_menu,
    "transfer activity is registered without exporting it": "android:name=\".ui.transfer.TransferActivity\"" in patch and "android:exported=\"false\"" in patch,

    "free-list refresh uses original direct network mode": "FreeConfigRepository.refreshIfDue(manual = refreshKey > 0)" in source and "manifestUrl.openConnection()" in repo and "openConnection(feedProxy)" not in repo and "Proxy.Type.SOCKS" not in repo,
    "feed refresh does not require active tunnel proxy": "active_tunnel_proxy_unavailable" not in repo and "active_tunnel_proxy_auth_unsupported" not in repo,
    "manual feed refresh uses original anti-spam interval": "MANUAL_MIN_INTERVAL_MS = 30L * 1000L" in repo,
    "free config ping uses the existing native outbound-delay worker": "RealPingWorkerService" in source and "onlyTcp = false" in source and "RealPingEvent.Result" in source,
    "free config ping uses a separate temporary hidden group": "V2PLAS_FREE_PING_SUBSCRIPTION_ID" in repo and "preparePingBatch" in repo and "clearPingBatch" in repo,
    "free config ping displays only numeric delay or dash": '"$it ms"' in source and '?: "—"' in source and '"سالم"' not in source and '"کند"' not in source,
    "free config ping sorts successful measurements first": "finalResults[it.id]?.takeIf { value -> value > 0L } ?: Long.MAX_VALUE" in source,
    "temporary ping group is hidden from personal server navigation": "v2plas_free_ping_temporary_group" in patch,

}

smart = SMART_FEATURES.read_text(encoding="utf-8")
top_bar = TOP_BAR.read_text(encoding="utf-8")
hotspot_proxy_path = ROOT / "overlay" / "HotspotProxyShare.kt"
hotspot_proxy = hotspot_proxy_path.read_text(encoding="utf-8") if hotspot_proxy_path.is_file() else ""
checks.update({
    "hotspot-share button is on the home screen beside the TV transfer button": "onHotspotShare = onHotspotShare" in source and "homeHotspotShareButtonScale" in source and "onClick = onHotspotShare" in source and source.index("onTransfer: () -> Unit") < source.index("onHotspotShare: () -> Unit"),
    "hotspot dialog is hosted by the V2 Plas home root and reads actual VPN state": "var showHotspotProxyShare by rememberSaveable" in source and "isVpnConnected = uiState.isRunning" in source and "HotspotProxyShareDialog(" in source,
    "hotspot share action is no longer shown on the servers folder toolbar": "V2PlasToolbarGlyph.HotspotShare" not in top_bar and "onHotspotShare" not in top_bar and "onHotspotShare" not in patch,
    "hotspot bridge is delivered as a daemon-process service": "HotspotProxyShareService" in patch and 'android:process=":daemon"' in patch,
    "proxy bridge forwards to existing loopback SOCKS rather than a second tunnel": 'InetSocketAddress("127.0.0.1", socksPort)' in hotspot_proxy and "connectThroughLocalSocks" in hotspot_proxy,
    "proxy bridge does not write core proxy-sharing preferences or restart the VPN": "MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING" not in hotspot_proxy and "startService(Intent(context, CoreVpnService" not in hotspot_proxy and "CoreConfigManager.configure" not in hotspot_proxy,
    "proxy binds only to detected hotspot IP, not all interfaces": "socket.bind(InetSocketAddress(hotspot.address, 0), 24)" in hotspot_proxy and 'InetSocketAddress("0.0.0.0"' not in hotspot_proxy,
    "proxy rejects clients outside the detected hotspot subnet": "sameSubnet(client.inetAddress, hotspot)" in hotspot_proxy and "fun sameSubnet" in hotspot_proxy,
    "proxy handles HTTP CONNECT and sends destination names to SOCKS without local DNS lookup": 'if (method == "CONNECT")' in hotspot_proxy and "output.writeByte(3)" in hotspot_proxy and "ATYP" not in hotspot_proxy,
    "SOCKS readiness probe validates the local protocol handshake": "DataInputStream(probe.getInputStream())" in hotspot_proxy and "SOCKS" in hotspot_proxy and "credentialsAvailable" in hotspot_proxy,
    "temporary SOCKS and hotspot discovery misses are debounced": "consecutiveSocksFailures" in hotspot_proxy and "consecutiveHotspotMisses" in hotspot_proxy and "consecutiveSocksFailures >= 4" in hotspot_proxy and "consecutiveHotspotMisses >= 3" in hotspot_proxy,
    "proxy worker pool scales immediately for concurrent long-lived CONNECT requests": "SynchronousQueue()" in hotspot_proxy and "4, 32, 30L" in hotspot_proxy and "allowCoreThreadTimeOut(true)" in hotspot_proxy,
    "uncaught client request exceptions are isolated from the daemon process": "Client proxy request ended" in hotspot_proxy and "catch (error: Exception)" in hotspot_proxy,
    "service force-broadcasts current state when the dialog reopens": "ownedOverride = hotspotOwned, force = true" in hotspot_proxy and "key == lastBroadcastKey && !force" in hotspot_proxy,
    "proxy shutdown closes both client and upstream SOCKS sockets": "upstreamSockets.toList().forEach" in hotspot_proxy and "upstreamSockets.remove(upstream)" in hotspot_proxy,
    "Android 17 local-network permission is requested only for target SDK 37+": "context.applicationInfo.targetSdkVersion >= 37" in hotspot_proxy and "ACCESS_LOCAL_NETWORK" in hotspot_proxy,
    "manual hotspot shutdown prompt is skipped if the hotspot is already off": "uiState.owned && HotspotProxyNetwork.discoverHotspotAddress() != null" in hotspot_proxy,
    "hotspot settings fallback is available across OEMs": 'Intent("android.settings.TETHER_SETTINGS")' in hotspot_proxy and "Settings.ACTION_WIRELESS_SETTINGS" in hotspot_proxy,
    "hotspot discovery avoids assuming wlan0 is always the access point": "wlan0 is often the ordinary Wi-Fi client interface" in hotspot_proxy and "wlan[0-9]*" not in hotspot_proxy,
    "hotspot switch shows a pending state and cancels pending start on off": "checked = uiState.enabled || pendingStart || continueEnableAfterPermission != null" in hotspot_proxy and "pendingStart = false" in hotspot_proxy,
    "proxy bridge supports long-lived HTTPS and HTTP response streams": "remote.soTimeout = 0" in hotspot_proxy and "finished.await()" in hotspot_proxy,
    "HTTP proxy handles Expect: 100-continue without request-body deadlock": "clientExpectContinue" in hotspot_proxy and "HTTP/1.1 100 Continue" in hotspot_proxy,
    "SOCKS settings use the pinned upstream SettingsManager APIs": "SettingsManager.getSocksPort()" in hotspot_proxy and "SettingsManager.getSocksUsername()" in hotspot_proxy and "SettingsManager.getSocksPassword()" in hotspot_proxy,
    "hotspot feature uses runtime local-network permission on Android 17+": 'Build.VERSION.SDK_INT >= 37' in hotspot_proxy and 'android.permission.ACCESS_LOCAL_NETWORK' in hotspot_proxy,
    "turning feature off stops only its proxy bridge": "ACTION_STOP" in hotspot_proxy and "closeListener()" in hotspot_proxy and "shouldAskToTurnOffHotspot" in hotspot_proxy,
    "Smart Select tests only the selected folder and requires positive ping results": "val groupId = initial.selectedGroupId" in smart and "testAllRealPingForGroup(groupId)" in smart and "it.testDelayMillis > 0L" in smart,
    "Smart Select keeps trying later valid candidates after an earlier connection failure": "while (firstConnected == null)" in smart and "SwitchOutcome.CANDIDATE_REJECTED" in smart,
    "Smart Select serializes switches and shields stop/select/start from cancellation": "private val switchMutex = Mutex()" in smart and "withContext(NonCancellable)" in smart and "switchMutex.withLock" in smart,
    "manual stop is checked before same-profile switch fast path": smart.index("if (userStopRequested.get())") < smart.index("if (previousGuid == candidateGuid && before.isRunning)"),
    "manual stop cancels the Smart Select-owned ping batch": "V2PlasSmartSelect.requestUserStop()" in source and "mainViewModel.cancelAllPing()" in source[source.index("V2PlasSmartSelect.requestUserStop()"):source.index("V2PlasSmartSelect.requestUserStop()") + 350],
    "Smart Select restores the previous group/profile and verifies rollback before retrying": "restoreSelectedGroupAndGuid" in smart and "SwitchOutcome.RECOVERY_FAILED" in smart and "it.selectedGuid == previousGuid" in smart,
    "Smart Select switches only to strictly lower positive ping results": "it.testDelayMillis > 0L && it.testDelayMillis < bestDelay" in smart,
    "Speed test uses a 10-second per-direction window and caps each direction at 30 MB": "private const val DIRECTION_WINDOW_MS = 10_000L" in smart and "private const val MAX_TRANSFER_BYTES = 30L * 1024L * 1024L" in smart and "while (bytes < MAX_TRANSFER_BYTES" in smart and "setChunkedStreamingMode(64 * 1024)" in smart,
    "download timeout starts only after HTTP response and input stream are ready": smart.index("val responseCode = connection.responseCode", smart.index("private fun measureDownload")) < smart.index("connection.inputStream.use { input ->", smart.index("private fun measureDownload")) < smart.index("abort = scheduler.schedule", smart.index("private fun measureDownload")),
    "upload timeout starts only after request output stream is ready": smart.index("connection.outputStream.use { output ->", smart.index("private fun measureUpload")) < smart.index("abort = scheduler.schedule", smart.index("private fun measureUpload")),
    "download and upload distinguish deadline aborts from genuine socket errors": smart.count("if (timedOut.get() && bytes > 0L) break else throw error") >= 2 and "val timedOut = AtomicBoolean(false)" in smart,
    "upload throughput excludes server acknowledgement wait": "sendElapsedMs = (SystemClock.elapsedRealtime() - start)" in smart and "return bytes * 8.0 / sendElapsedMs.toDouble()" in smart,
    "upload rate waits for HTTP acknowledgement with a bounded hard timeout": "val responseCode = connection.responseCode" in smart[smart.index("private fun measureUpload"):smart.index("private data class StrongestServer")] and "if (responseCode !in 200..299)" in smart[smart.index("private fun measureUpload"):smart.index("private data class StrongestServer")] and "private const val UPLOAD_HARD_TIMEOUT_MS = 15_000L" in smart and "readTimeout = 5_000" in smart,
    "Speed test stores throughput only for the actually connected profile GUID": "V2PlasSpeedTestStore.put(" in source and "testedGuid" in source and "V2PlasSpeedTestStore.results" in patch,
    "Speed Test toolbar button is a dedicated gauge with visible needle animation": ".size(36.dp)" in top_bar and "needle.animateTo(1f" in top_bar and "tween(1_350" in top_bar,
    "Speed Test button is moved to the right of the unchanged glass action frame in RTL": top_bar.index("SpeedTestToolbarButton(onClick = onSpeedTest)") < top_bar.index("Row(", top_bar.index("actions = {")) and top_bar.index("SpeedTestToolbarButton(onClick = onSpeedTest)") < top_bar.index("ToolbarActionButton(V2PlasToolbarGlyph.Qr"),
    "Speed Test button keeps its existing size and glass action frame keeps its styling": ".size(36.dp)" in top_bar and "RoundedCornerShape(19.dp)" in top_bar and "Color(0xDD263F70)" in top_bar and "ToolbarActionButton(V2PlasToolbarGlyph.Qr" in top_bar,
    "Combined speed test remains manually invoked and measures only the connected profile": source.count("V2PlasSpeedTester.measure {") == 1 and "onSpeedTest = onSpeedTestClick" in source and "testAllRealPing" not in source[source.index("val onSpeedTestClick"):source.index("val freeLimitCallback")],
    "Strongest Configs includes Default and excludes only technical free groups": 'group.id != "v2plas_free_remote_group"' in smart and 'group.id != "v2plas_free_ping_temporary_group"' in smart and "groups.map { it.id }" in smart,
    "Strongest Configs waits for an existing ping and tests folders sequentially": "existingTestFinished" in smart and "testAllRealPingForGroup(groupId)" in smart and "reloadAllGroups(groups.map { it.id }).join()" in smart,
    "Strongest Configs sorts valid pings ascending and selects from the original folder": ".sortedWith(compareBy<StrongestServer> { it.server.testDelayMillis }" in smart and "onSelectServer(item.groupId, item.server.guid)" in smart,
    "TV power D-pad special handling is scoped to the power control only": "if (!isTelevision || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false" in source and "Key.DirectionLeft" in source and "Key.DirectionRight" in source and "Key.DirectionUp" not in source and "Key.DirectionDown" not in source,
    "Server speed badges use static arrow vectors with green download and turquoise upload": "drawPath(arrow, c" in patch and "Color(0xFF35E88F)" in patch and "Color(0xFF39D9E8)" in patch,
    "Duplicate left-side actions are removed while right-rail callbacks remain": "remove duplicate left-side edit/share/delete controls" in patch and "actions.share(row.guid, row.profile)" in patch and "actions.edit(row.guid, row.profile)" in patch and "actions.remove(row.guid, row.remarks)" in patch,
})

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL") + ": " + name)
if failed:
    raise SystemExit(f"{len(failed)} check(s) failed")
print(f"PASS: all {len(checks)} static V2 Plas checks")
print("NOT TESTED: Kotlin/Gradle compilation against the pinned upstream source, Android UI rendering, CDN deployment/headers, Cloudflare Worker deployment, actual quota-counter accuracy, and VPN connection on a physical device.")
