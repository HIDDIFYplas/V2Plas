# Applied automatically during the build (see overlay step in build-v2plas.yml).
# All changes are applied to files that already exist in the pinned v2rayNG source.
# This script intentionally creates no source/resource files.
import sys
from pathlib import Path

BASE = Path("V2rayNG/app/src/main/java/com/v2ray/ang/ui/main")


def edit(path, old, new, what):
    path = Path(path)
    if not path.is_file():
        sys.exit("ERROR: source file not found: " + str(path))
    source = path.read_text(encoding="utf-8")
    if old not in source:
        sys.exit("ERROR: patch anchor not found: " + what)
    path.write_text(source.replace(old, new, 1), encoding="utf-8")
    print("patched:", what)


def edit_optional(path, old, new, what):
    """Apply a cosmetic refinement only when its exact upstream anchor exists."""
    path = Path(path)
    if not path.is_file():
        sys.exit("ERROR: source file not found: " + str(path))
    source = path.read_text(encoding="utf-8")
    if old not in source:
        print("skipped optional refinement (upstream layout differs):", what)
        return
    path.write_text(source.replace(old, new, 1), encoding="utf-8")
    print("patched:", what)


# 1) Replace the original landing screen with the V2 Plas home screen.
edit(
    BASE / "MainActivity.kt",
    "        MainScreen(\n            mainViewModel = mainViewModel,",
    "        V2PlasApp(\n            mainViewModel = mainViewModel,",
    "MainActivity -> V2PlasApp",
)

# 2) Keep the original server list, import/edit/share/delete actions and dialogs,
# but let the V2 Plas bottom navigation own the lower navigation area.
path = BASE / "MainScreen.kt"
s = path.read_text(encoding="utf-8")
start = s.find("            bottomBar = {\n                MainBottomBar(")
if start < 0:
    sys.exit("ERROR: patch anchor not found: MainScreen bottomBar")
end = s.find("            },\n", start)
if end < 0:
    sys.exit("ERROR: patch anchor not found: MainScreen bottomBar end")
end += len("            },\n")
s = s[:start] + "            bottomBar = {},\n" + s[end:]
path.write_text(s, encoding="utf-8")
print("patched: MainScreen bottomBar")

# 2b) The hidden free subscription is a technical backing store only. Keep it
# out of the personal server/group picker while preserving its selected GUID
# for the home screen and VPN core.
edit(
    path,
    "    val groups = uiState.groups\n",
    "    val groups = uiState.groups.filterNot { it.id == \"v2plas_free_remote_group\" }\n",
    "hide free-only group from personal server navigation",
)
# Wrap the original group pager tabs in a compact glass selector. The pager state,
# selection callbacks and group identity remain owned by the upstream screen.
edit(
    path,
    "import androidx.compose.foundation.layout.Column\n",
    "import androidx.compose.foundation.layout.Column\n"
    "import androidx.compose.foundation.layout.fillMaxWidth\n"
    "import androidx.compose.foundation.background\n"
    "import androidx.compose.foundation.border\n"
    "import androidx.compose.foundation.shape.RoundedCornerShape\n"
    "import androidx.compose.ui.draw.clip\n"
    "import androidx.compose.ui.graphics.graphicsLayer\n"
    "import androidx.compose.ui.graphics.Brush\n"
    "import androidx.compose.ui.graphics.Color\n",
    "group selector glass imports",
)
edit(
    path,
    """                    if (groups.size > 1) {
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
""",
    """                    if (groups.size > 1) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Brush.linearGradient(listOf(Color(0xDD253D70), Color(0xCC121E43), Color(0xCC29235E))))
                                .border(1.dp, Brush.linearGradient(listOf(Color(0x889CCBFF), Color(0x555B83D8), Color(0x558B5CF6))), RoundedCornerShape(22.dp))
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        ) {
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
                    }
""",
    "glass subscription group selector",
)

# 3) Restyle the existing server rows as V2 Plas glass cards. Selection,
# reordering, GUID identity and all existing row actions remain unchanged.
server_path = BASE / "MainServerPager.kt"
edit(
    server_path,
    "import androidx.compose.foundation.background\n",
    "import androidx.compose.foundation.background\n"
    "import androidx.compose.foundation.border\n"
    "import androidx.compose.foundation.shape.RoundedCornerShape\n",
    "server card visual imports",
)
edit(
    server_path,
    "import androidx.compose.ui.draw.clip\n",
    "import androidx.compose.ui.draw.clip\n"
    "import androidx.compose.ui.graphics.graphicsLayer\n"
    "import androidx.compose.ui.graphics.Brush\n"
    "import androidx.compose.ui.graphics.Color\n",
    "server card graphics imports",
)
edit(
    server_path,
    """        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics {
""",
    """        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF111D42), Color(0xFF0B1430))))
            .border(
                width = 1.dp,
                color = if (isSelected) Color(0xFF579CFF) else Color(0x333F66B7),
                shape = RoundedCornerShape(17.dp)
            )
            .height(IntrinsicSize.Min)
            .semantics {
""",
    "server rows -> neon glass cards",
)

# 3b) Add country flags, persistent favorites and a compact favorites filter
# to the existing server list. Favorites are stored in the existing MMKV store.
edit(
    server_path,
    "import androidx.compose.material3.IconButton\n",
    "import androidx.compose.material3.IconButton\n"
    "import androidx.compose.material3.FilterChip\n",
    "server favorite filter chip import",
)
edit(
    server_path,
    "import androidx.compose.runtime.remember\n",
    "import androidx.compose.runtime.remember\n"
    "import androidx.compose.runtime.saveable.rememberSaveable\n",
    "server saveable favorites filter import",
)
edit(
    server_path,
    "import androidx.compose.runtime.mutableIntStateOf\n",
    "import androidx.compose.runtime.mutableIntStateOf\n"
    "import androidx.compose.runtime.mutableStateOf\n",
    "server favorite state imports",
)
edit(
    server_path,
    "import com.v2ray.ang.dto.LocateTarget\n",
    "import com.v2ray.ang.dto.LocateTarget\n"
    "import com.v2ray.ang.handler.MmkvManager\n",
    "server favorite persistence import",
)
edit(
    server_path,
    """    val groupState by groupStateFlow.collectAsStateWithLifecycle()
    val canReorder = groupId.isNotEmpty() && searchQuery.isEmpty()
""",
    """    val groupState by groupStateFlow.collectAsStateWithLifecycle()
    // Exclude the hidden free profile even from an optional "all servers" page.
    val personalRows = remember(groupState.rows) {
        groupState.rows.filterNot { row ->
            row.profile.subscriptionId == "v2plas_free_remote_group"
        }
    }
    var favoritesOnly by rememberSaveable(groupId) { mutableStateOf(false) }
    var favoritesRevision by remember(groupId) { mutableIntStateOf(0) }
    val visibleRows = remember(personalRows, favoritesOnly, favoritesRevision) {
        if (favoritesOnly) personalRows.filter { row ->
            MmkvManager.decodeSettingsBool("v2plas_favorite_${row.guid}", false)
        } else personalRows
    }
    LaunchedEffect(locateTarget) {
        if (locateTarget != null) favoritesOnly = false
    }
    val canReorder = groupId.isNotEmpty() && searchQuery.isEmpty()
""",
    "server favorite filter state",
)
edit(
    server_path,
    """            remove = onRemoveServer,
        )
""",
    """            remove = onRemoveServer,
            favoriteChanged = { favoritesRevision++ },
        )
""",
    "server favorite refresh callback",
)
# Keep the existing subscription information panel, but put a small filter row
# above the scrolling list. The list receives extra top padding for the chips.
edit(
    server_path,
    """    val listPadding = if (usageInfo == null) {
        contentPadding
    } else {
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding() + with(density) { infoHeightPx.toDp() },
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding()
        )
    }
""",
    """    val panelTop = if (usageInfo == null) 0.dp else with(density) { infoHeightPx.toDp() }
    val listPadding = PaddingValues(
        start = contentPadding.calculateStartPadding(layoutDirection),
        top = contentPadding.calculateTopPadding() + panelTop + 48.dp,
        end = contentPadding.calculateEndPadding(layoutDirection),
        bottom = contentPadding.calculateBottomPadding()
    )
""",
    "server filter top spacing",
)
edit(
    server_path,
    """        ServerListPage(
            rows = groupState.rows,
""",
    """        ServerListPage(
            rows = visibleRows,
""",
    "server favorites filtered rows",
)
edit(
    server_path,
    """        if (usageInfo != null) {
            SubscriptionInfoCard(
""",
    """        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = contentPadding.calculateTopPadding() + panelTop + 5.dp,
                    end = 14.dp
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !favoritesOnly,
                onClick = { favoritesOnly = false },
                label = { Text("همه سرورها") }
            )
            FilterChip(
                selected = favoritesOnly,
                onClick = { favoritesOnly = true },
                label = { Text("ستاره‌دار") }
            )
        }
        if (usageInfo != null) {
            SubscriptionInfoCard(
""",
    "server favorite filter controls",
)
# Add a callback to the existing row actions holder.
edit(
    server_path,
    """    val remove: (String, String) -> Unit,
)
""",
    """    val remove: (String, String) -> Unit,
    val favoriteChanged: () -> Unit,
)
""",
    "server favorite action holder",
)
# Country flags are rendered by the shared vector-only V2PlasFlag composable.
# The favorite state is persisted in MMKV and is presented in the side action rail.
edit(
    server_path,
    """    val selectedStateDescription = if (isSelected) {
        stringResource(R.string.acc_selected_server)
    } else {
        null
    }
    Row(
""",
    """    val selectedStateDescription = if (isSelected) {
        stringResource(R.string.acc_selected_server)
    } else {
        null
    }
    var isFavorite by remember(row.guid) {
        mutableStateOf(MmkvManager.decodeSettingsBool("v2plas_favorite_${row.guid}", false))
    }
    Row(
""",
    "server persistent favorite state",
)
edit(
    server_path,
    """            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.remarks, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (doubleColumnDisplay) {
""",
    """            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                V2PlasFlag(countryCodeFromText("${row.remarks} ${row.profile.server}"), Modifier.padding(end = 8.dp).size(42.dp))
                Text(
                    row.remarks,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineBreak = LineBreak.Paragraph),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (doubleColumnDisplay) {
""",
    "server title row with vector flag",
)

# 4) Restyle the reusable preference rows. Settings and routing screens both
# use this component, so their existing controls/actions are preserved.
settings_item = Path("V2rayNG/app/src/main/java/com/v2ray/ang/ui/compose/SettingsItem.kt")
edit(
    settings_item,
    "import androidx.compose.foundation.clickable\n",
    "import androidx.compose.foundation.clickable\n"
    "import androidx.compose.foundation.background\n"
    "import androidx.compose.foundation.border\n"
    "import androidx.compose.foundation.shape.RoundedCornerShape\n",
    "settings card imports",
)
edit(
    settings_item,
    "import androidx.compose.ui.graphics.painter.Painter\n",
    "import androidx.compose.ui.graphics.painter.Painter\n"
    "import androidx.compose.ui.graphics.Brush\n",
    "settings brush import",
)
edit(
    settings_item,
    """        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(16.dp),
""",
    """        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF111D42), Color(0xFF0B1430))))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
""",
    "settings controls -> glass cards",
)
# The SettingsItem file already imports MaterialTheme; add Color only when needed.
edit(
    settings_item,
    "import androidx.compose.ui.graphics.Brush\n",
    "import androidx.compose.ui.graphics.Brush\n"
    "import androidx.compose.ui.graphics.Color\n",
    "settings color import",
)
# Style collapsible section headers as compact category cards.
edit(
    settings_item,
    """        modifier = modifier
            .fillMaxWidth()
            .clickable { onExpandedChange(!expanded) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
""",
    """        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF101A3A))
            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.24f), RoundedCornerShape(14.dp))
            .clickable { onExpandedChange(!expanded) }
            .padding(horizontal = 14.dp, vertical = 13.dp),
""",
    "settings category headers -> compact cards",
)
# Ensure the reusable settings item file has clip imported (it already uses clip in dialogs only on some revisions).
settings_text = settings_item.read_text(encoding="utf-8")
if "import androidx.compose.ui.draw.clip\n" not in settings_text:
    edit(
        settings_item,
        "import androidx.compose.ui.draw.rotate\n",
        "import androidx.compose.ui.draw.rotate\nimport androidx.compose.ui.draw.clip\n",
        "settings clip import",
    )

# 4b) Give routing-rule rows the same card treatment without changing their
# edit/toggle/reorder callbacks.
routing_activity = Path("V2rayNG/app/src/main/java/com/v2ray/ang/ui/routing/RoutingSettingActivity.kt")
edit(
    routing_activity,
    "import androidx.compose.foundation.layout.Box\n",
    "import androidx.compose.foundation.layout.Box\n"
    "import androidx.compose.foundation.background\n"
    "import androidx.compose.foundation.border\n"
    "import androidx.compose.foundation.shape.RoundedCornerShape\n",
    "routing card layout imports",
)
edit(
    routing_activity,
    "import androidx.compose.ui.draw.scale\n",
    "import androidx.compose.ui.draw.scale\n"
    "import androidx.compose.ui.draw.clip\n"
    "import androidx.compose.ui.graphics.Brush\n"
    "import androidx.compose.ui.graphics.Color\n",
    "routing card graphics imports",
)
edit(
    routing_activity,
    """        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
""",
    """        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF111D42), Color(0xFF0B1430))))
            .border(1.dp, Color(0x333F66B7), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
""",
    "routing rules -> glass cards",
)

# 4c) Restyle connection/application logs as compact glass cards. Log actions,
# search, copy, share and refresh handlers stay exactly as they are.
logcat_activity = Path("V2rayNG/app/src/main/java/com/v2ray/ang/ui/logcat/LogcatActivity.kt")
edit(
    logcat_activity,
    "import androidx.compose.foundation.combinedClickable\n",
    "import androidx.compose.foundation.combinedClickable\n"
    "import androidx.compose.foundation.background\n"
    "import androidx.compose.foundation.border\n"
    "import androidx.compose.foundation.shape.RoundedCornerShape\n",
    "log cards layout imports",
)
edit(
    logcat_activity,
    "import androidx.compose.ui.Modifier\n",
    "import androidx.compose.ui.Modifier\n"
    "import androidx.compose.ui.draw.clip\n"
    "import androidx.compose.ui.graphics.Brush\n"
    "import androidx.compose.ui.graphics.Color\n",
    "log cards graphics imports",
)
edit(
    logcat_activity,
    """    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(8.dp)
    ) {
""",
    """    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF111D42), Color(0xFF0B1430))))
            .border(1.dp, Color(0x333F66B7), RoundedCornerShape(14.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
""",
    "connection logs -> glass cards",
)

# 5) Add the user-controlled, MMKV-persisted star animation switch to the
# existing UI settings group. No resource file or new screen is created.
settings_activity = Path("V2rayNG/app/src/main/java/com/v2ray/ang/ui/settings/SettingsActivity.kt")
edit(
    settings_activity,
    "    var speedEnabled by rememberMmkvBool(AppConfig.PREF_SPEED_ENABLED, false)\n",
    "    var speedEnabled by rememberMmkvBool(AppConfig.PREF_SPEED_ENABLED, false)\n"
    "    var v2plasStarsEnabled by rememberMmkvBool(\"v2plas_animated_stars\", true)\n",
    "persistent star animation preference",
)
edit(
    settings_activity,
    """                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_speed_enabled),
                    summary = stringResource(R.string.summary_pref_speed_enabled),
                    checked = speedEnabled,
                    onCheckedChange = { speedEnabled = it }
                )
""",
    """                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_speed_enabled),
                    summary = stringResource(R.string.summary_pref_speed_enabled),
                    checked = speedEnabled,
                    onCheckedChange = { speedEnabled = it }
                )
                SettingsSwitchItem(
                    title = \"پس‌زمینه زنده ستاره‌ای\",
                    summary = \"حرکت ملایم ستاره‌ها؛ برای گوشی‌های ضعیف‌تر می‌توانید خاموش کنید.\",
                    checked = v2plasStarsEnabled,
                    onCheckedChange = { v2plasStarsEnabled = it }
                )
""",
    "star animation toggle in existing settings group",
)



# 6) MainTopBar.kt is supplied as a complete V2 Plas overlay with a shared glass action frame.

# Prevent the now-hidden drawer from opening with an edge swipe. Its destinations
# remain available through the V2 Plas home menu; no destination is deleted.
main_screen = BASE / "MainScreen.kt"
edit(
    main_screen,
    """    ModalNavigationDrawer(
        drawerState = drawerState,
""",
    """    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
""",
    "disable hidden server drawer gesture",
)

# 7) Put favorite/share/edit/delete into one consistent vertical glass action rail.
# Their callbacks and underlying MMKV/action flows are unchanged.
edit(
    server_path,
    """        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
""",
    """        Column(
            modifier = Modifier.width(34.dp).padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ServerFavoriteButton(
                selected = isFavorite,
                onClick = {
                    isFavorite = !isFavorite
                    MmkvManager.encodeSettings("v2plas_favorite_${row.guid}", isFavorite)
                    actions.favoriteChanged()
                },
            )
            ServerActionButton(
                glyph = ServerActionGlyph.Share,
                description = stringResource(R.string.title_configuration_share),
                onClick = { actions.share(row.guid, row.profile) },
            )
            ServerActionButton(
                glyph = ServerActionGlyph.Edit,
                description = stringResource(R.string.acc_edit),
                onClick = { actions.edit(row.guid, row.profile) },
            )
            ServerActionButton(
                glyph = ServerActionGlyph.Delete,
                description = stringResource(R.string.acc_delete),
                onClick = { actions.remove(row.guid, row.remarks) },
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 5.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
""",
    "server vertical favorite and actions rail",
)

# The ping stays on the lower-right of the card in a capsule whose border and fill
# use the same current latency color.
edit(
    server_path,
    """                Text(
                    testResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.testDelayMillis < 0L) colorPingRed else colorPing,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
""",
    """                if (testResult.isNotBlank()) {
                    val pingBadgeColor = when {
                        row.testDelayMillis < 0L -> Color(0xFFFF4D5E)
                        row.testDelayMillis < 80L -> Color(0xFF35F59A)
                        row.testDelayMillis < 200L -> Color(0xFF9BE7A3)
                        row.testDelayMillis < 300L -> Color(0xFFFFD84D)
                        row.testDelayMillis < 500L -> Color(0xFFFF9B45)
                        else -> Color(0xFFFF4D5E)
                    }
                    Text(
                        testResult,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(pingBadgeColor.copy(alpha = 0.12f))
                            .border(1.dp, pingBadgeColor.copy(alpha = 0.48f), RoundedCornerShape(9.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = pingBadgeColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
""",
    "server ping badge color-matched frame",
)

# Smaller vertical rhythm compensates for the new fixed-size left control rail.
edit_optional(
    server_path,
    """            Spacer(modifier = Modifier.height(6.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
""",
    """            Spacer(modifier = Modifier.height(4.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
""",
    "server card compact detail spacing",
)

edit_optional(
    server_path,
    """            Spacer(modifier = Modifier.height(6.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
""",
    """            Spacer(modifier = Modifier.height(4.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
""",
    "server card compact ping row spacing",
)

# Reusable 28dp icon buttons use the same glass-frame treatment as QR/clipboard,
# with small spring feedback. Star is drawn as a vector rather than a text glyph.
edit(
    server_path,
    """@Composable
private fun ServerListItem(
""",
    """@Composable
private fun ServerFavoriteButton(selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "serverFavoriteSpring",
    )
    Box(
        modifier = Modifier
            .size(28.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(11.dp))
            .background(Brush.verticalGradient(listOf(Color(0x553D5D96), Color(0x33202F61))))
            .border(1.dp, if (selected) Color(0xAAFFD36B) else Color(0x665F83C7), RoundedCornerShape(11.dp))
            .semantics { contentDescription = if (selected) "حذف از علاقه‌مندی‌ها" else "افزودن به علاقه‌مندی‌ها" }
            .clickable(interactionSource = interaction, indication = com.v2ray.ang.ui.compose.LocalV2PlasTvFocusIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val w = size.width
            val h = size.height
            val star = Path().apply {
                moveTo(w * 0.50f, h * 0.04f)
                lineTo(w * 0.62f, h * 0.37f)
                lineTo(w * 0.97f, h * 0.38f)
                lineTo(w * 0.69f, h * 0.60f)
                lineTo(w * 0.79f, h * 0.96f)
                lineTo(w * 0.50f, h * 0.74f)
                lineTo(w * 0.21f, h * 0.96f)
                lineTo(w * 0.31f, h * 0.60f)
                lineTo(w * 0.03f, h * 0.38f)
                lineTo(w * 0.38f, h * 0.37f)
                close()
            }
            val starColor = if (selected) Color(0xFFFFD36B) else Color(0xFFB9C9EC)
            if (selected) drawPath(star, starColor) else drawPath(star, starColor, style = androidx.compose.ui.graphics.drawscope.Stroke(1.7.dp.toPx()))
        }
    }
}

private enum class ServerActionGlyph { Share, Edit, Delete }

@Composable
private fun ServerActionButton(glyph: ServerActionGlyph, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "serverActionSpring",
    )
    Box(
        modifier = Modifier
            .size(28.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(11.dp))
            .background(Brush.verticalGradient(listOf(Color(0x553D5D96), Color(0x33202F61))))
            .border(1.dp, Color(0x665F83C7), RoundedCornerShape(11.dp))
            .semantics { contentDescription = description }
            .clickable(interactionSource = interaction, indication = com.v2ray.ang.ui.compose.LocalV2PlasTvFocusIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val c = Color(0xFFE2E9FF)
            val sw = 1.8.dp.toPx()
            when (glyph) {
                ServerActionGlyph.Share -> {
                    val a = Offset(size.width * .25f, size.height * .50f)
                    val b = Offset(size.width * .73f, size.height * .25f)
                    val d = Offset(size.width * .73f, size.height * .75f)
                    drawLine(c, a, b, sw, StrokeCap.Round)
                    drawLine(c, a, d, sw, StrokeCap.Round)
                    drawCircle(c, size.width * .13f, a)
                    drawCircle(c, size.width * .13f, b)
                    drawCircle(c, size.width * .13f, d)
                }
                ServerActionGlyph.Edit -> {
                    val path = Path().apply {
                        moveTo(size.width * .25f, size.height * .75f)
                        lineTo(size.width * .68f, size.height * .30f)
                        lineTo(size.width * .82f, size.height * .44f)
                        lineTo(size.width * .38f, size.height * .88f)
                        lineTo(size.width * .20f, size.height * .91f)
                        close()
                    }
                    drawPath(path, c)
                    drawLine(c, Offset(size.width * .18f, size.height * .94f), Offset(size.width * .40f, size.height * .88f), sw, StrokeCap.Round)
                }
                ServerActionGlyph.Delete -> {
                    drawLine(c, Offset(size.width * .20f, size.height * .27f), Offset(size.width * .80f, size.height * .27f), sw, StrokeCap.Round)
                    drawLine(c, Offset(size.width * .38f, size.height * .15f), Offset(size.width * .62f, size.height * .15f), sw, StrokeCap.Round)
                    drawLine(c, Offset(size.width * .29f, size.height * .35f), Offset(size.width * .35f, size.height * .84f), sw, StrokeCap.Round)
                    drawLine(c, Offset(size.width * .71f, size.height * .35f), Offset(size.width * .65f, size.height * .84f), sw, StrokeCap.Round)
                    drawLine(c, Offset(size.width * .35f, size.height * .84f), Offset(size.width * .65f, size.height * .84f), sw, StrokeCap.Round)
                    drawLine(c, Offset(size.width * .46f, size.height * .43f), Offset(size.width * .47f, size.height * .73f), sw, StrokeCap.Round)
                    drawLine(c, Offset(size.width * .56f, size.height * .43f), Offset(size.width * .55f, size.height * .73f), sw, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun ServerListItem(
""",
    "server action buttons with framed spring feedback",
)

edit(
    server_path,
    "import com.v2ray.ang.ui.compose.colorPing\n",
    "",
    "remove legacy server ping color import",
)
edit(
    server_path,
    "import com.v2ray.ang.ui.compose.colorPingRed\n",
    "",
    "remove legacy server ping error color import",
)

edit(
    server_path,
    "import androidx.compose.ui.semantics.stateDescription\n",
    "import androidx.compose.ui.semantics.stateDescription\n"
    "import androidx.compose.ui.semantics.contentDescription\n"
    "import androidx.compose.ui.semantics.semantics\n",
    "server action accessibility imports",
)
# Imports for the local vector star and Compose spring interaction.
edit(
    server_path,
    "import androidx.compose.foundation.clickable\n",
    "import androidx.compose.foundation.clickable\n"
    "import androidx.compose.foundation.Canvas\n"
    "import androidx.compose.foundation.interaction.MutableInteractionSource\n"
    "import androidx.compose.foundation.interaction.collectIsPressedAsState\n",
    "server action animation imports",
)
edit(
    server_path,
    "import androidx.compose.material3.IconButton\n",
    "import androidx.compose.animation.core.Spring\n"
    "import androidx.compose.animation.core.animateFloatAsState\n"
    "import androidx.compose.animation.core.spring\n"
    "import androidx.compose.material3.IconButton\n",
    "server action spring imports",
)
edit(
    server_path,
    "import androidx.compose.ui.graphics.Color\n",
    "import androidx.compose.ui.geometry.Offset\n"
    "import androidx.compose.ui.graphics.Color\n"
    "import androidx.compose.ui.graphics.Path\n"
    "import androidx.compose.ui.graphics.StrokeCap\n",
    "server action vector drawing imports",
)

print("V2 Plas UI patches completed; no source/resource files were created by patch.py.")

# 4) Enforce free-session quotas from the VPN core/service lifecycle, not only
# from the Compose activity. The daemon service remains responsible while the
# UI is backgrounded or its activity is recreated.
core_manager = Path("V2rayNG/app/src/main/java/com/v2ray/ang/core/CoreServiceManager.kt")
edit(
    core_manager,
    """        if (!isRunning()) {
            error("Core failed to start")
        }
""",
    """        if (!isRunning()) {
            error("Core failed to start")
        }
        FreeQuotaServiceMonitor.start(config.subscriptionId)
""",
    "start service-side free quota monitor after core startup",
)
edit(
    core_manager,
    """    fun stopCoreLoop(): Boolean {
        connectionTestScope.coroutineContext.cancelChildren()
""",
    """    fun stopCoreLoop(): Boolean {
        FreeQuotaServiceMonitor.stop()
        connectionTestScope.coroutineContext.cancelChildren()
""",
    "stop service-side free quota monitor with core shutdown",
)
