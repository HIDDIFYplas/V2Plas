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
            .padding(horizontal = 12.dp, vertical = 5.dp)
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
    var favoritesOnly by rememberSaveable(groupId) { mutableStateOf(false) }
    var favoritesRevision by remember(groupId) { mutableIntStateOf(0) }
    val visibleRows = remember(groupState.rows, favoritesOnly, favoritesRevision) {
        if (favoritesOnly) groupState.rows.filter { row ->
            MmkvManager.decodeSettingsBool("v2plas_favorite_${row.guid}", false)
        } else groupState.rows
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
# Add a flag helper for common Persian/English country names without changing profile data.
edit(
    server_path,
    """@Composable
private fun ServerItemRow(
""",
    """private fun flagForServerName(name: String): String {
    val value = name.lowercase()
    val known = listOf(
        listOf("netherlands", "holland", "amsterdam", "هلند", "آمستردام") to "🇳🇱",
        listOf("germany", "deutschland", "frankfurt", "آلمان", "فرانکفورت") to "🇩🇪",
        listOf("turkey", "türkiye", "istanbul", "ترکیه", "استانبول") to "🇹🇷",
        listOf("iran", "ایران") to "🇮🇷",
        listOf("usa", "united states", "america", "new york", "آمریکا", "امریکا") to "🇺🇸",
        listOf("uk", "britain", "england", "london", "انگلیس", "بریتانیا", "لندن") to "🇬🇧",
        listOf("uae", "dubai", "emirates", "امارات", "دبی") to "🇦🇪",
        listOf("canada", "toronto", "کانادا", "تورنتو") to "🇨🇦",
        listOf("france", "paris", "فرانسه", "پاریس") to "🇫🇷",
        listOf("singapore", "سنگاپور") to "🇸🇬",
        listOf("japan", "tokyo", "ژاپن") to "🇯🇵",
        listOf("russia", "روسیه") to "🇷🇺",
        listOf("finland", "helsinki", "فنلاند") to "🇫🇮",
        listOf("sweden", "stockholm", "سوئد") to "🇸🇪",
        listOf("switzerland", "zurich", "سوئیس") to "🇨🇭"
    )
    return known.firstOrNull { (names, _) -> names.any { value.contains(it) } }?.second ?: "🌐"
}

@Composable
private fun ServerItemRow(
""",
    "server name country flag helper",
)
# Persistent star button alongside the server name. Selection/edit/share/delete remain intact.
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
                Text(flagForServerName(row.remarks), modifier = Modifier.padding(end = 7.dp), fontSize = 18.sp)
                Text(row.remarks, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph), maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(
                    onClick = {
                        isFavorite = !isFavorite
                        MmkvManager.encodeSettings("v2plas_favorite_${row.guid}", isFavorite)
                        actions.favoriteChanged()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text(if (isFavorite) "★" else "☆", color = if (isFavorite) Color(0xFFFFC857) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
                }
                if (doubleColumnDisplay) {
""",
    "server flag and favorite button",
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



# 6) Remove only the hamburger/navigation-drawer button from the server toolbar.
# Search still keeps its back/close control. Import, search, overflow actions,
# and all server-management actions remain available.
topbar = BASE / "MainTopBar.kt"
edit(
    topbar,
    """            } else {
                IconButton(onClick = onMenuClick) {
                    Icon(painterResource(R.drawable.ic_menu_24dp), contentDescription = stringResource(R.string.acc_open_menu))
                }
            }
""",
    """            }
""",
    "remove server toolbar hamburger only",
)

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

# 7) Move server actions to a dedicated lower action rail. Their exact callbacks
# and semantics are unchanged; only placement, spacing and visual treatment change.
edit(
    server_path,
    """            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(flagForServerName(row.remarks), modifier = Modifier.padding(end = 7.dp), fontSize = 18.sp)
                Text(row.remarks, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph), maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(
                    onClick = {
                        isFavorite = !isFavorite
                        MmkvManager.encodeSettings("v2plas_favorite_${row.guid}", isFavorite)
                        actions.favoriteChanged()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text(if (isFavorite) "★" else "☆", color = if (isFavorite) Color(0xFFFFC857) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
                }
                if (doubleColumnDisplay) {
                    IconButton(onClick = { actions.more(row.guid, row.profile) }, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert_24dp),
                            stringResource(R.string.acc_more),
                            Modifier.size(24.dp)
                        )
                    }
                } else {
                    IconButton(onClick = { actions.share(row.guid, row.profile) }, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_share_24dp),
                            stringResource(R.string.title_configuration_share),
                            Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = { actions.edit(row.guid, row.profile) }, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_edit_24dp),
                            stringResource(R.string.acc_edit),
                            Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = { actions.remove(row.guid, row.remarks) }, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_delete_24dp),
                            stringResource(R.string.acc_delete),
                            Modifier.size(24.dp)
                        )
                    }
                }
            }
""",
    """            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(flagForServerName(row.remarks), modifier = Modifier.padding(end = 8.dp), fontSize = 20.sp)
                Text(
                    row.remarks,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineBreak = LineBreak.Paragraph),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = {
                        isFavorite = !isFavorite
                        MmkvManager.encodeSettings("v2plas_favorite_${row.guid}", isFavorite)
                        actions.favoriteChanged()
                    },
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0x222F4E8C))
                ) {
                    Text(if (isFavorite) "★" else "☆", color = if (isFavorite) Color(0xFFFFC857) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
                }
            }
""",
    "server title row with favorite action",
)

edit(
    server_path,
    """                Text(
                    testResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.testDelayMillis < 0L) colorPingRed else colorPing,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
""",
    """                Text(
                    testResult,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0x222B5D9D))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (row.testDelayMillis < 0L) colorPingRed else colorPing,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (doubleColumnDisplay) {
                    ServerActionButton(
                        icon = R.drawable.ic_more_vert_24dp,
                        description = stringResource(R.string.acc_more),
                        onClick = { actions.more(row.guid, row.profile) },
                    )
                } else {
                    ServerActionButton(
                        icon = R.drawable.ic_delete_24dp,
                        description = stringResource(R.string.acc_delete),
                        onClick = { actions.remove(row.guid, row.remarks) },
                    )
                    Spacer(Modifier.width(5.dp))
                    ServerActionButton(
                        icon = R.drawable.ic_edit_24dp,
                        description = stringResource(R.string.acc_edit),
                        onClick = { actions.edit(row.guid, row.profile) },
                    )
                    Spacer(Modifier.width(5.dp))
                    ServerActionButton(
                        icon = R.drawable.ic_share_24dp,
                        description = stringResource(R.string.title_configuration_share),
                        onClick = { actions.share(row.guid, row.profile) },
                    )
                }
            }
""",
    "server action rail below details",
)

# Add a local reusable visual wrapper for those same existing server actions.
edit(
    server_path,
    """@Composable
private fun ServerListItem(
""",
    """@Composable
private fun ServerActionButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0x332D477F))
            .border(1.dp, Color(0x334F7FD2), RoundedCornerShape(11.dp)),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ServerListItem(
""",
    "server action button visual helper",
)

print("V2 Plas UI patches completed; no source/resource files were created by patch.py.")
