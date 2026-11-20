// DEST: ui/main/MainTopBar.kt
package com.v2ray.ang.ui.main

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.LocalV2PlasTvFocusIndication
import com.v2ray.ang.ui.compose.verticalScrollbar

private enum class V2PlasToolbarGlyph { Qr, Clipboard, Add, Search, More, Back }

@Composable
fun MainTopBar(
    isLoading: Boolean,
    showSearch: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onAction: (MainAction) -> Unit,
    onMoreMenuAction: (MainMoreMenuAction) -> Unit
) {
    var showImportMenu by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val importMenuScrollState = rememberScrollState()
    val moreMenuScrollState = rememberScrollState()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val maxMenuHeight = LocalConfiguration.current.screenHeightDp.dp - statusBarHeight - navBarHeight - 20.dp

    AppTopBar(
        title = "کانفیگ‌ها",
        onBackClick = {},
        isLoading = isLoading,
        isSearchActive = showSearch,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onSearchClose = onSearchClose,
        searchPlaceholder = stringResource(R.string.menu_item_search),
        navigationIcon = {
            if (showSearch) {
                IconButton(onClick = onSearchClose) {
                    Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = stringResource(R.string.acc_back), tint = Color.White)
                }
            } else {
                // Keep search beside the title in RTL layout, not at the far-left toolbar edge.
                ToolbarActionButton(V2PlasToolbarGlyph.Search, stringResource(R.string.acc_search)) { onSearchToggle(true) }
            }
        },
        actions = {
            if (!showSearch) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(19.dp))
                        .background(Brush.linearGradient(listOf(Color(0xDD263F70), Color(0xCC111D43), Color(0xCC29235E))))
                        .border(1.dp, Brush.linearGradient(listOf(Color(0xAA9CCBFF), Color(0x555B83D8), Color(0x668B5CF6))), RoundedCornerShape(19.dp))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolbarActionButton(V2PlasToolbarGlyph.Qr, "ورود کانفیگ با QR") { onAction(MainAction.ImportQRcode) }
                    ToolbarActionButton(V2PlasToolbarGlyph.Clipboard, "ورود کانفیگ از کلیپ‌بورد") { onAction(MainAction.ImportClipboard) }
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                        ToolbarActionButton(V2PlasToolbarGlyph.Add, stringResource(R.string.acc_add)) { showImportMenu = true }
                        DropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                            scrollState = importMenuScrollState,
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.heightIn(max = maxMenuHeight).verticalScrollbar(importMenuScrollState),
                        ) {
                            DropdownMenuItem(
                                text = { Text("وارد کردن فایل کانفیگ") },
                                onClick = { showImportMenu = false; onAction(MainAction.ImportConfigLocal) },
                            )
                            val manualTypes = listOf(
                                "VMess" to EConfigType.VMESS,
                                "VLESS" to EConfigType.VLESS,
                                "Shadowsocks" to EConfigType.SHADOWSOCKS,
                                "SOCKS" to EConfigType.SOCKS,
                                "HTTP" to EConfigType.HTTP,
                                "Trojan" to EConfigType.TROJAN,
                                "WireGuard" to EConfigType.WIREGUARD,
                                "Hysteria" to EConfigType.HYSTERIA,
                                "Hysteria2" to EConfigType.HYSTERIA2,
                                "گروه خط مشی" to EConfigType.POLICYGROUP,
                                "زنجیره پروکسی" to EConfigType.PROXYCHAIN,
                            )
                            manualTypes.forEach { (label, type) ->
                                DropdownMenuItem(
                                    text = { Text("افزودن [$label]") },
                                    onClick = { showImportMenu = false; onAction(MainAction.ImportManually(type.value)) },
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                        ToolbarActionButton(V2PlasToolbarGlyph.More, stringResource(R.string.acc_more)) { showMenu = true }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            scrollState = moreMenuScrollState,
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.heightIn(max = maxMenuHeight).verticalScrollbar(moreMenuScrollState),
                        ) {
                            MoreMenuContent { action ->
                                showMenu = false
                                onMoreMenuAction(action)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ToolbarActionButton(
    glyph: V2PlasToolbarGlyph,
    description: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "toolbarActionPress",
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) Color(0x555B9BFF) else Color(0x1F3D5B91))
            .border(1.dp, Color(0x3D9CCBFF), RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = LocalV2PlasTvFocusIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(21.dp)) {
            val white = Color.White
            val stroke = 2.0.dp.toPx()
            val w = size.width
            val h = size.height
            when (glyph) {
                V2PlasToolbarGlyph.Qr -> {
                    val unit = w * 0.28f
                    val corners = listOf(Offset(w * 0.08f, h * 0.08f), Offset(w * 0.64f, h * 0.08f), Offset(w * 0.08f, h * 0.64f))
                    corners.forEach { p ->
                        drawRoundRect(white, p, androidx.compose.ui.geometry.Size(unit, unit), androidx.compose.ui.geometry.CornerRadius(1.8.dp.toPx()), style = Stroke(stroke))
                        drawRect(white, Offset(p.x + unit * 0.30f, p.y + unit * 0.30f), androidx.compose.ui.geometry.Size(unit * 0.40f, unit * 0.40f))
                    }
                    drawRect(white, Offset(w * 0.64f, h * 0.64f), androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.12f))
                    drawRect(white, Offset(w * 0.82f, h * 0.64f), androidx.compose.ui.geometry.Size(w * 0.10f, h * 0.10f))
                    drawRect(white, Offset(w * 0.64f, h * 0.82f), androidx.compose.ui.geometry.Size(w * 0.10f, h * 0.10f))
                }
                V2PlasToolbarGlyph.Clipboard -> {
                    drawRoundRect(white, Offset(w * 0.18f, h * 0.18f), androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.74f), androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx()), style = Stroke(stroke))
                    drawRoundRect(Color(0xFF18264F), Offset(w * 0.34f, h * 0.07f), androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.22f), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                    drawRoundRect(white, Offset(w * 0.34f, h * 0.07f), androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.22f), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(stroke))
                    drawLine(white, Offset(w * 0.31f, h * 0.48f), Offset(w * 0.69f, h * 0.48f), stroke, StrokeCap.Round)
                    drawLine(white, Offset(w * 0.31f, h * 0.67f), Offset(w * 0.62f, h * 0.67f), stroke, StrokeCap.Round)
                }
                V2PlasToolbarGlyph.Add -> {
                    drawLine(white, Offset(w * .5f, h * .14f), Offset(w * .5f, h * .86f), stroke, StrokeCap.Round)
                    drawLine(white, Offset(w * .14f, h * .5f), Offset(w * .86f, h * .5f), stroke, StrokeCap.Round)
                }
                V2PlasToolbarGlyph.Search -> {
                    drawCircle(white, w * .30f, Offset(w * .40f, h * .40f), style = Stroke(stroke))
                    drawLine(white, Offset(w * .62f, h * .62f), Offset(w * .90f, h * .90f), stroke, StrokeCap.Round)
                }
                V2PlasToolbarGlyph.More -> {
                    listOf(.22f, .5f, .78f).forEach { y -> drawCircle(white, 1.7.dp.toPx(), Offset(w * .5f, h * y)) }
                }
                V2PlasToolbarGlyph.Back -> {
                    val p = Path().apply { moveTo(w * .78f, h * .18f); lineTo(w * .30f, h * .5f); lineTo(w * .78f, h * .82f) }
                    drawPath(p, white, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
    }
}
