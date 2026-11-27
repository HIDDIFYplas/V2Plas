// DEST: ui/main/MainSubscriptionInfo.kt
package com.v2ray.ang.ui.main

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionUsageInfo
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.SubscriptionInfoParser
import com.v2ray.ang.util.Utils

private const val MILLIS_PER_SECOND = 1000L
private const val EXPIRY_DATE_PATTERN = "yyyy-MM-dd"

internal data class SubscriptionInfoUiModel(
    val title: String?,
    val usedBytes: Long,
    val totalBytes: Long,
    val trafficFraction: Float?,
    val expireMillis: Long?,
    val expired: Boolean,
    val supportUrl: String?,
    val webPageUrl: String?,
) {
    val trafficLow: Boolean get() = trafficFraction != null && trafficFraction >= TRAFFIC_WARNING_FRACTION
    companion object { const val TRAFFIC_WARNING_FRACTION = 0.9f }
}

internal fun buildSubscriptionInfoUiModel(info: SubscriptionUsageInfo, nowMillis: Long): SubscriptionInfoUiModel {
    val unlimited = info.total <= 0L
    val expireMillis = info.expire.takeIf { it > 0L }?.let { it * MILLIS_PER_SECOND }
    return SubscriptionInfoUiModel(
        title = info.title,
        usedBytes = info.used,
        totalBytes = if (unlimited) 0L else info.total,
        trafficFraction = if (unlimited) null else (info.used.toDouble() / info.total).toFloat().coerceIn(0f, 1f),
        expireMillis = expireMillis,
        expired = expireMillis != null && expireMillis <= nowMillis,
        supportUrl = info.supportUrl,
        webPageUrl = info.webPageUrl,
    )
}

/** Glass-styled subscription card. All information and action callbacks remain the upstream model. */
@Composable
internal fun SubscriptionInfoCard(
    info: SubscriptionUsageInfo,
    fallbackTitle: String,
    onOpenLink: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = remember(info) { buildSubscriptionInfoUiModel(info, System.currentTimeMillis()) }
    val shape = RoundedCornerShape(19.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xE52B4779), Color(0xE9162851), Color(0xE10B1535))))
            .border(1.dp, Brush.linearGradient(listOf(Color(0xAA9CCBFF), Color(0x665B83D8), Color(0x668B5CF6))), shape)
            .padding(start = 13.dp, end = 10.dp, top = 8.dp, bottom = 9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model.title ?: fallbackTitle,
                modifier = Modifier.weight(1f),
                color = Color(0xFFF0F3FF),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            model.supportUrl?.let { url ->
                SubscriptionGlassAction(
                    iconRes = if (SubscriptionInfoParser.isTelegramLink(url)) R.drawable.ic_telegram_24dp else R.drawable.ic_feedback_24dp,
                    description = stringResource(R.string.sub_info_open_support),
                    onClick = { onOpenLink(url) },
                )
            }
            model.webPageUrl?.let { url ->
                SubscriptionGlassAction(
                    iconRes = R.drawable.ic_about_24dp,
                    description = stringResource(R.string.sub_info_open_page),
                    onClick = { onOpenLink(url) },
                )
            }
            SubscriptionGlassAction(
                iconRes = R.drawable.ic_refresh_24dp,
                description = stringResource(R.string.title_sub_update),
                onClick = onRefresh,
            )
        }
        Spacer(Modifier.height(3.dp))
        Column(
            Modifier.fillMaxWidth().padding(end = 6.dp).semantics(mergeDescendants = true) {},
        ) {
            val totalText = if (model.totalBytes > 0L) model.totalBytes.toTrafficString() else stringResource(R.string.sub_info_unlimited)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.sub_info_traffic, model.usedBytes.toTrafficString(), totalText),
                    modifier = Modifier.weight(1f, fill = false),
                    color = if (model.trafficLow) Color(0xFFFFA26B) else Color(0xFFE3E9FF),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                model.expireMillis?.let { expiry ->
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = Utils.formatTimestamp(expiry, EXPIRY_DATE_PATTERN),
                        color = if (model.expired) Color(0xFFFF647C) else Color(0xFFB8C7F5),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
            model.trafficFraction?.let { fraction ->
                Spacer(Modifier.height(5.dp))
                SubscriptionProgress(
                    fraction = fraction,
                    warning = model.trafficLow,
                    modifier = Modifier.fillMaxWidth().height(5.dp).clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun SubscriptionGlassAction(iconRes: Int, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(30.dp)
            .clip(shape)
            .background(Color(0x332F528E))
            .border(1.dp, Color(0x446FA8F5), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(iconRes), description, Modifier.size(17.dp), tint = Color(0xFFF5F7FF))
    }
}

@Composable
private fun SubscriptionProgress(fraction: Float, warning: Boolean, modifier: Modifier = Modifier) {
    val target = fraction.coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "subscriptionUsageProgress",
    )
    val track = RoundedCornerShape(50)
    Canvas(modifier.clip(track)) {
        drawRoundRect(Color(0x44495D91), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
        val colorBrush = if (warning) {
            Brush.horizontalGradient(listOf(Color(0xFFFFC56A), Color(0xFFFF6B78)))
        } else {
            Brush.horizontalGradient(listOf(Color(0xFF43D6E9), Color(0xFF5B91FF), Color(0xFF9B76FF)))
        }
        if (progress > 0f) {
            drawRoundRect(
                brush = colorBrush,
                size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
            )
        }
    }
}
