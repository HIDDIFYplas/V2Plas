#!/usr/bin/env python3
"""Apply V2 Plas UI changes to the pinned v2rayNG checkout.

This patch is deliberately marker-based and idempotent. It refuses unknown source
layouts instead of making broad, unsafe edits to the VPN engine.
Usage: python3 patches/apply_v2plas.py V2rayNG --kit-root v2plas-kit
"""
from __future__ import annotations
import argparse
import shutil
import sys
from pathlib import Path

INTRO_MARKER = '    @Composable\n    override fun ScreenContent() {\n        BackHandler'
INTRO_INSERT = '''    @Composable
    override fun ScreenContent() {
        val v2PlasPrefs = remember { this@MainActivity.getSharedPreferences("v2plas_ui", android.content.Context.MODE_PRIVATE) }
        var showV2PlasSplash by rememberSaveable { mutableStateOf(true) }
        var showV2PlasIntro by rememberSaveable {
            mutableStateOf(!v2PlasPrefs.getBoolean("intro_done", false))
        }
        var pendingV2PlasNotice by remember { mutableStateOf<V2PlasAnnouncement?>(null) }
        var showV2PlasNotice by remember { mutableStateOf(false) }

        // Lightweight branded splash; its timeout never waits for network or VPN operations.
        LaunchedEffect(Unit) {
            delay(850L)
            showV2PlasSplash = false
        }
        // Remote requests are optional, bounded by timeouts and never block the VPN service.
        LaunchedEffect(Unit) {
            pendingV2PlasNotice = withContext(Dispatchers.IO) {
                V2PlasAnnouncementRepository.fetch(this@MainActivity)
            }
        }
        LaunchedEffect(showV2PlasSplash, showV2PlasIntro, pendingV2PlasNotice) {
            if (!showV2PlasSplash && !showV2PlasIntro && pendingV2PlasNotice != null) showV2PlasNotice = true
        }
        if (showV2PlasSplash) {
            V2PlasSplashScreen()
        }
        if (!showV2PlasSplash && showV2PlasIntro) {
            V2PlasIntroDialog(onFinish = {
                v2PlasPrefs.edit().putBoolean("intro_done", true).apply()
                showV2PlasIntro = false
            })
        }
        val currentV2PlasNotice = pendingV2PlasNotice
        if (!showV2PlasSplash && !showV2PlasIntro && showV2PlasNotice && currentV2PlasNotice != null) {
            V2PlasAnnouncementDialog(
                announcement = currentV2PlasNotice,
                onDismiss = {
                    V2PlasAnnouncementRepository.markShown(this@MainActivity, currentV2PlasNotice)
                    showV2PlasNotice = false
                    pendingV2PlasNotice = null
                }
            )
        }
        BackHandler'''

IMPORTS = [
    'import androidx.compose.runtime.LaunchedEffect',
    'import androidx.compose.runtime.getValue',
    'import androidx.compose.runtime.mutableStateOf',
    'import androidx.compose.runtime.remember',
    'import androidx.compose.runtime.setValue',
    'import androidx.compose.runtime.saveable.rememberSaveable',
    'import kotlinx.coroutines.Dispatchers',
    'import kotlinx.coroutines.delay',
    'import kotlinx.coroutines.withContext',
]

ABOUT_START = '@Composable\nfun AboutScreen('
ABOUT_REPLACEMENT = '''@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    onTranslatorsClick: () -> Unit
) {
    val context = LocalContext.current
    var showOssDialog by remember { mutableStateOf(false) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = "V2 Plas",
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_telegram_24dp),
                title = "کانال تلگرام V2 Plas",
                onClick = { Utils.openUri(context, "https://t.me/HIDDIFY_plas") }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_source_code_24dp),
                title = "کد منبع V2 Plas",
                onClick = { Utils.openUri(context, "https://github.com/HIDDIFYplas/V2Plas") }
            )
            // Keep third-party/open-source license notices required by included dependencies.
            SettingsMenuItem(
                icon = painterResource(R.drawable.license_24px),
                title = "مجوزها و حقوق نرم‌افزار",
                onClick = { showOssDialog = true }
            )
            NavigationBarsSpacer()
        }
    }
    if (showOssDialog) {
        AlertDialog(
            onDismissRequest = { showOssDialog = false },
            title = { Text("مجوزها و حقوق نرم‌افزار") },
            text = {
                AndroidView(
                    factory = { ctx -> WebView(ctx).apply { loadUrl("file:///android_asset/open_source_licenses.html") } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { showOssDialog = false }) { Text(stringResource(R.string.action_ok)) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(bottom = 60.dp)
        )
    }
}
'''

TOP_AND_BOTTOM_OLD = '''            topBar = {
                MainTopBar(
                    isLoading = isLoading,
                    showSearch = showSearch,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { query: String ->
                        searchQuery = query
                        onAction(MainAction.Search(query))
                    },
                    onSearchClose = {
                        searchQuery = ""
                        onAction(MainAction.Search(""))
                        showSearch = false
                    },
                    onSearchToggle = { show: Boolean -> showSearch = show },
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onAction = onAction,
                    onMoreMenuAction = { action ->
                        when (action) {
                            MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                            MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                            MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                            MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                            MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                            MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                            MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                            MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                            MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                            MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                        }
                    }
                )
            },
            bottomBar = {
                MainBottomBar(
                    displayText = displayText,
                    isRunning = isRunning,
                    isDarkTheme = isDarkTheme,
                    onAction = onAction
                )
            },'''

TOP_AND_BOTTOM_NEW = '''            topBar = {
                if (v2PlasTab <= 1) {
                    V2PlasAppTopBar(
                        title = when {
                            v2PlasTab == 0 -> "V2 Plas"
                            v2PlasShowServerDetails -> "جزئیات سرور"
                            else -> "سرورها"
                        },
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onImportClick = { onAction(MainAction.ImportClipboard) }
                    )
                } else {
                    MainTopBar(
                        isLoading = isLoading,
                        showSearch = showSearch,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { query: String ->
                            searchQuery = query
                            onAction(MainAction.Search(query))
                        },
                        onSearchClose = {
                            searchQuery = ""
                            onAction(MainAction.Search(""))
                            showSearch = false
                        },
                        onSearchToggle = { show: Boolean -> showSearch = show },
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onAction = onAction,
                        onMoreMenuAction = { action ->
                            when (action) {
                                MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                                MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                                MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                                MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                                MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                                MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                                MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                                MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                                MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                                MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                            }
                        }
                    )
                }
            },
            bottomBar = {
                V2PlasBottomNavigation(
                    statusText = displayText,
                    isRunning = isRunning,
                    activeTab = v2PlasTab,
                    onToggleService = { onAction(MainAction.ToggleService) },
                    onTabSelected = { tab ->
                        when (tab) {
                            0 -> { v2PlasTab = 0; v2PlasShowServerDetails = false }
                            1 -> { v2PlasTab = 1; v2PlasShowServerDetails = false }
                            2 -> { v2PlasTab = 0; v2PlasShowServerDetails = false; onNavigate(MainDestination.Routing) }
                            else -> { v2PlasTab = 0; v2PlasShowServerDetails = false; onNavigate(MainDestination.Settings) }
                        }
                    }
                )
            },''' 

HOME_BODY_OLD = '''            val layoutDirection = LocalLayoutDirection.current
            if (groups.isNotEmpty()) {'''
# The original block has nested lambdas and braces. Replace the full outer block,
# not just its first two lines, or the old server list would remain after the new UI.
HOME_BODY_END = "\n            }\n        }\n    }\n}"
HOME_BODY_NEW = '''            val layoutDirection = LocalLayoutDirection.current
            val selectedV2PlasServer = selectedGroupServers.firstOrNull { it.guid == selectedGuid }
            when {
                v2PlasTab == 0 -> V2PlasHomeScreen(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    statusText = displayText,
                    isRunning = isRunning,
                    selectedServerName = selectedV2PlasServer?.profile?.remarks
                        ?.takeIf { it.isNotBlank() } ?: "هنوز کانفیگی انتخاب نشده",
                    selectedPing = selectedV2PlasServer?.testDelayMillis
                        ?.takeIf { it > 0L }?.let { "$it ms" } ?: "هنوز سنجیده نشده",
                    serverCount = selectedGroupServers.size,
                    onConnect = { onAction(MainAction.ToggleService) },
                    onServersClick = { v2PlasTab = 1; v2PlasShowServerDetails = false },
                    onImportClipboard = { onAction(MainAction.ImportClipboard) },
                    onImportQr = { onAction(MainAction.ImportQRcode) }
                )
                v2PlasTab == 1 && v2PlasShowServerDetails -> V2PlasServerDetailScreen(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    server = selectedV2PlasServer,
                    isRunning = isRunning,
                    onBack = { v2PlasShowServerDetails = false },
                    onConnect = { onAction(MainAction.ToggleService) },
                    onEdit = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) }
                )
                v2PlasTab == 1 -> V2PlasServersScreen(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    servers = selectedGroupServers,
                    groups = groups,
                    selectedGroupId = uiState.selectedGroupId,
                    selectedGuid = selectedGuid,
                    onGroupSelected = { onAction(MainAction.SelectGroup(it)) },
                    onSelectServer = { onAction(MainAction.SelectServer(it)) },
                    onShowDetails = { guid ->
                        onAction(MainAction.SelectServer(guid))
                        v2PlasShowServerDetails = true
                    },
                    onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                    onImportClipboard = { onAction(MainAction.ImportClipboard) },
                    onImportQr = { onAction(MainAction.ImportQRcode) },
                    onTestAll = { onAction(MainAction.TestRealAllServers) }
                )
                groups.isNotEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                ) {
                    if (groups.size > 1) {
                        GroupTabBar(
                            groups = groups,
                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                            mainViewModel = mainViewModel,
                            onTabClick = { targetIndex ->
                                scope.launch {
                                    pagerState.navigateToPageOptimized(
                                        targetPage = targetIndex,
                                        animateAdjacentPage = true
                                    )
                                }
                            }
                        )
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = true,
                        beyondViewportPageCount = 1,
                        key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                    ) { page ->
                        val group = groups.getOrNull(page) ?: return@HorizontalPager
                        GroupPagerPage(
                            groupId = group.id,
                            mainViewModel = mainViewModel,
                            selectedGuid = selectedGuid,
                            locateTarget = uiState.locateTarget,
                            doubleColumnDisplay = doubleColumnDisplay,
                            searchQuery = searchQuery,
                            lazyListStates = lazyListStates,
                            lazyGridStates = lazyGridStates,
                            onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                            onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                            onShareServer = { guid, profile -> shareTarget = Triple(guid, profile, false) },
                            onMoreServer = { guid, profile -> shareTarget = Triple(guid, profile, true) },
                            onRemoveServer = removeServer,
                            usageInfo = group.usageInfo,
                            groupTitle = group.remarks,
                            onOpenLink = { url -> onAction(MainAction.OpenSubscriptionLink(url)) },
                            contentPadding = PaddingValues(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 80.dp)
                        )
                    }
                }
                else -> Unit
            }''' 

THEME_BLOCK = '''// V2PlasCustomThemeMarker: branded light/dark Material 3 palettes.
private val LightColor = lightColorScheme(
    primary = Color(0xFF246FE8), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E8FF), onPrimaryContainer = Color(0xFF07152F),
    secondary = Color(0xFF765BFF), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E2FF), onSecondaryContainer = Color(0xFF1D1554),
    tertiary = Color(0xFF008F78), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8F5E9), onTertiaryContainer = Color(0xFF002019),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
    onError = Color.White, onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4F7FF), onBackground = Color(0xFF10192B),
    surface = Color(0xFFFAFCFF), onSurface = Color(0xFF10192B),
    surfaceVariant = Color(0xFFE2EAF8), onSurfaceVariant = Color(0xFF465675),
    outline = Color(0xFF687C9D), outlineVariant = Color(0xFFCBD8EE),
    inverseSurface = Color(0xFF202A3D), inverseOnSurface = Color(0xFFF3F6FF),
    inversePrimary = Color(0xFF8DBAFF), scrim = Color.Black, surfaceTint = Color(0xFF246FE8),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F9FF),
    surfaceContainer = Color(0xFFEEF3FF), surfaceContainerHigh = Color(0xFFE7EEFC),
    surfaceContainerHighest = Color(0xFFDDE7F8),
)
private val DarkColor = darkColorScheme(
    primary = Color(0xFF4A9BFF), onPrimary = Color(0xFF001B3D),
    primaryContainer = Color(0xFF14376C), onPrimaryContainer = Color(0xFFD7E6FF),
    secondary = Color(0xFF9B8AFF), onSecondary = Color(0xFF20105D),
    secondaryContainer = Color(0xFF382B78), onSecondaryContainer = Color(0xFFE7E0FF),
    tertiary = Color(0xFF55D8B4), onTertiary = Color(0xFF00382E),
    tertiaryContainer = Color(0xFF005143), onTertiaryContainer = Color(0xFFA0F2D0),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF050B1B), onBackground = Color(0xFFF1F6FF),
    surface = Color(0xFF08152B), onSurface = Color(0xFFF1F6FF),
    surfaceVariant = Color(0xFF142746), onSurfaceVariant = Color(0xFFB7C8E5),
    outline = Color(0xFF45658F), outlineVariant = Color(0xFF213A60),
    inverseSurface = Color(0xFFE5ECFA), inverseOnSurface = Color(0xFF0D172A),
    inversePrimary = Color(0xFF246FE8), scrim = Color.Black, surfaceTint = Color(0xFF4A9BFF),
    surfaceContainerLowest = Color(0xFF030815), surfaceContainerLow = Color(0xFF071128),
    surfaceContainer = Color(0xFF0A1831), surfaceContainerHigh = Color(0xFF0D1E3D),
    surfaceContainerHighest = Color(0xFF13284B),
)
'''


def add_imports(text: str, imports: list[str]) -> str:
    lines = text.splitlines()
    for imp in imports:
        if imp not in text:
            indexes = [i for i, line in enumerate(lines) if line.startswith('import ')]
            if not indexes:
                raise ValueError('No import section found; refusing to patch this Kotlin file.')
            lines.insert(indexes[-1] + 1, imp)
    return '\n'.join(lines) + ('\n' if text.endswith('\n') else '')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise ValueError(f'{label}: expected exactly one source marker, found {count}.')
    return text.replace(old, new, 1)


def replace_home_body(screen: str, new_body: str) -> str:
    """Replace the complete original Scaffold body (not just its opening lines)."""
    count = screen.count(HOME_BODY_OLD)
    if count != 1:
        raise ValueError(f'home/server dashboard body: expected exactly one opening marker, found {count}.')
    start = screen.find(HOME_BODY_OLD)
    end = screen.rfind(HOME_BODY_END, start)
    if end < start:
        raise ValueError('home/server dashboard body: original MainScreen closing markers were not found.')
    # The custom `when` body includes its own final branch closing brace.
    replace_end = end + len("\n            }")
    updated = screen[:start] + new_body + screen[replace_end:]
    if 'V2PlasHomeScreen(' not in updated or HOME_BODY_OLD in updated:
        raise ValueError('home/server dashboard body: replacement did not remove the complete original body.')
    return updated


def patch_theme(theme: str) -> str:
    if 'V2PlasCustomThemeMarker' in theme:
        return theme
    start = theme.find('private val LightColor = lightColorScheme(')
    end = theme.find('// Semantic Colors', start)
    if start < 0 or end < 0:
        raise ValueError('Theme.kt color scheme markers not found.')
    theme = theme[:start] + THEME_BLOCK + theme[end:]
    theme = replace_once(
        theme,
        '        else -> isSystemInDarkTheme()\n',
        '        else -> true // V2 Plas uses the reference dark theme by default.\n',
        'default dark theme'
    )
    cs_start = theme.find('    val colorScheme = when {')
    cs_end = theme.find('    val snackbarController =', cs_start)
    if cs_start < 0 or cs_end < 0:
        raise ValueError('Theme.kt color selection markers not found.')
    theme = theme[:cs_start] + '''    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColor
        else -> LightColor
    }
''' + theme[cs_end:]
    # Consistent semantic highlight colors used by connection controls and server rows.
    theme = theme.replace('val colorPing = Color(0xFF009966)', 'val colorPing = Color(0xFF35D6A0)')
    theme = theme.replace('val colorPingRed = Color(0xFFFF0099)', 'val colorPingRed = Color(0xFFFF6B8B)')
    theme = theme.replace('val colorConfigType = Color(0xFFf97910)', 'val colorConfigType = Color(0xFF4A9BFF)')
    theme = theme.replace('val colorFabActive = Color(0xFFf97910)', 'val colorFabActive = Color(0xFF35D6A0)')
    theme = theme.replace('val colorFabInactiveLight = Color(0xFF9C9C9C)', 'val colorFabInactiveLight = Color(0xFF246FE8)')
    theme = theme.replace('val colorFabInactiveDark = Color(0xFF646464)', 'val colorFabInactiveDark = Color(0xFF246FE8)')
    # Use the branded palette by default, but preserve the user's system/dynamic-color choice.
    dynamic_default = 'MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, true)'
    if dynamic_default not in theme:
        raise ValueError('ThemeManager dynamic-color default marker not found.')
    theme = theme.replace(dynamic_default, 'MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)')
    return theme


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('source_root', type=Path, help='v2rayNG checkout directory (contains app/src/main)')
    parser.add_argument('--kit-root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    root = args.source_root.resolve()
    kit = args.kit_root.resolve()

    main_file = root / 'app/src/main/java/com/v2ray/ang/ui/main/MainActivity.kt'
    about_file = root / 'app/src/main/java/com/v2ray/ang/ui/AboutActivity.kt'
    screen_file = root / 'app/src/main/java/com/v2ray/ang/ui/main/MainScreen.kt'
    theme_file = root / 'app/src/main/java/com/v2ray/ang/ui/compose/Theme.kt'
    settings_file = root / 'app/src/main/java/com/v2ray/ang/ui/settings/SettingsActivity.kt'
    experience_src = kit / 'src/V2PlasExperience.kt'
    dashboard_src = kit / 'src/V2PlasDashboard.kt'
    paths = [main_file, about_file, screen_file, theme_file, settings_file, experience_src, dashboard_src]
    missing = [p for p in paths if not p.is_file()]
    if missing:
        print('ERROR: Required source file(s) are missing; no files were modified:', file=sys.stderr)
        for path in missing:
            print(f'  - {path}', file=sys.stderr)
        return 2

    # Read and prepare everything in memory first, so validation errors don't partially patch source.
    main_text = main_file.read_text(encoding='utf-8')
    about_text = about_file.read_text(encoding='utf-8')
    screen_text = screen_file.read_text(encoding='utf-8')
    theme_text = theme_file.read_text(encoding='utf-8')
    settings_text = settings_file.read_text(encoding='utf-8')

    already_intro = 'V2PlasAnnouncementRepository.fetch(this@MainActivity)' in main_text
    if not already_intro:
        if INTRO_MARKER not in main_text:
            print('ERROR: MainActivity screen marker not found; source does not match the pinned revision.', file=sys.stderr)
            return 3
        main_text = add_imports(main_text, IMPORTS)
        main_text = replace_once(main_text, INTRO_MARKER, INTRO_INSERT, 'MainActivity introduction/announcement hook')

    about_start = about_text.find(ABOUT_START)
    if about_start < 0:
        print('ERROR: AboutScreen marker not found in AboutActivity.kt.', file=sys.stderr)
        return 4
    if 'https://github.com/HIDDIFYplas/V2Plas' not in about_text:
        about_text = about_text[:about_start] + ABOUT_REPLACEMENT

    # Main screen hook: preserve the upstream server list, dialogs, actions and VPN command routing.
    if 'V2PlasHomeScreen(' not in screen_text:
        screen_text = replace_once(
            screen_text,
            '    val selectedGuid = uiState.selectedGuid\n',
            '    val selectedGuid = uiState.selectedGuid\n    val selectedGroupServers by mainViewModel.serversForGroup(uiState.selectedGroupId).collectAsStateWithLifecycle()\n',
            'selected server state'
        )
        screen_text = replace_once(
            screen_text,
            '    var searchQuery by remember { mutableStateOf("") }\n',
            '    var searchQuery by remember { mutableStateOf("") }\n    var v2PlasTab by rememberSaveable { mutableStateOf(0) }\n    var v2PlasShowServerDetails by rememberSaveable { mutableStateOf(false) }\n',
            'home/server tab state'
        )
        screen_text = replace_once(screen_text, TOP_AND_BOTTOM_OLD, TOP_AND_BOTTOM_NEW, 'home/server top and bottom navigation')
        screen_text = replace_home_body(screen_text, HOME_BODY_NEW)
    elif 'V2PlasBottomNavigation(' not in screen_text:
        print('ERROR: MainScreen appears partially patched; refusing to guess how to continue.', file=sys.stderr)
        return 5

    try:
        theme_text = patch_theme(theme_text)
        if 'var dynamicColor by rememberMmkvBool(AppConfig.PREF_DYNAMIC_COLOR, false)' not in settings_text:
            settings_text = replace_once(
                settings_text,
                'var dynamicColor by rememberMmkvBool(AppConfig.PREF_DYNAMIC_COLOR, true)',
                'var dynamicColor by rememberMmkvBool(AppConfig.PREF_DYNAMIC_COLOR, false)',
                'branded default dynamic-color setting'
            )
    except ValueError as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 6

    # All validations passed. Copy new Compose source, then write prepared patches.
    dashboard_dest = root / 'app/src/main/java/com/v2ray/ang/ui/main/V2PlasDashboard.kt'
    experience_dest = root / 'app/src/main/java/com/v2ray/ang/ui/main/V2PlasExperience.kt'
    dashboard_dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(dashboard_src, dashboard_dest)
    shutil.copy2(experience_src, experience_dest)
    main_file.write_text(main_text, encoding='utf-8')
    about_file.write_text(about_text, encoding='utf-8')
    screen_file.write_text(screen_text, encoding='utf-8')
    theme_file.write_text(theme_text, encoding='utf-8')
    settings_file.write_text(settings_text, encoding='utf-8')
    print('Applied V2 Plas splash, dashboard/server/detail screens, theme, onboarding, announcement and About links.')
    print('VPN/core/service implementation files were not changed by this patch.')
    print('A successful Android build and device VPN tests are still required before calling this release stable.')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
