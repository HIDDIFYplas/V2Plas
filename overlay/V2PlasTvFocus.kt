// DEST: ui/compose/V2PlasTvFocus.kt
package com.v2ray.ang.ui.compose

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Supplied only on Android TV. Keeping this local nullable lets custom controls
 * that intentionally disable touch ripples remain unchanged on phones/tablets.
 */
internal val LocalV2PlasTvFocusIndication = compositionLocalOf<Indication?> { null }

/**
 * Node-based indication keeps the focus effect lightweight. It draws only inside
 * the control's existing bounds and never changes measured size or layout.
 */
internal object V2PlasTvFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        V2PlasTvFocusNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0x563250
}

private class V2PlasTvFocusNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    private val focused = mutableStateOf(false)
    private val pressed = mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch {
            val activeFocus = linkedSetOf<FocusInteraction.Focus>()
            val activePress = linkedSetOf<PressInteraction.Press>()
            interactionSource.interactions.collect { interaction: Interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> activeFocus.add(interaction)
                    is FocusInteraction.Unfocus -> activeFocus.remove(interaction.focus)
                    is PressInteraction.Press -> activePress.add(interaction)
                    is PressInteraction.Release -> activePress.remove(interaction.press)
                    is PressInteraction.Cancel -> activePress.remove(interaction.press)
                }
                focused.value = activeFocus.isNotEmpty()
                pressed.value = activePress.isNotEmpty()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()

        val focus = if (focused.value) 1f else 0f
        val press = if (pressed.value) 1f else 0f
        val alpha = maxOf(focus, press * 0.55f)
        if (alpha <= 0f || size.minDimension <= 0f) return

        val inset = 1.25.dp.toPx()
        val innerInset = 2.8.dp.toPx()
        val corner = (size.minDimension * 0.18f).coerceAtLeast(5.dp.toPx())
        val innerCorner = (corner - innerInset).coerceAtLeast(2.dp.toPx())
        val outerSize = Size(
            width = (size.width - inset * 2f).coerceAtLeast(0f),
            height = (size.height - inset * 2f).coerceAtLeast(0f),
        )
        val innerSize = Size(
            width = (size.width - innerInset * 2f).coerceAtLeast(0f),
            height = (size.height - innerInset * 2f).coerceAtLeast(0f),
        )

        // A very soft white wash plus two fine rings; no size or position changes.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.045f * alpha),
            topLeft = Offset(inset, inset),
            size = outerSize,
            cornerRadius = CornerRadius(corner, corner),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.38f * focus + 0.16f * press),
            topLeft = Offset(inset, inset),
            size = outerSize,
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = 1.15.dp.toPx()),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.12f * focus),
            topLeft = Offset(innerInset, innerInset),
            size = innerSize,
            cornerRadius = CornerRadius(innerCorner, innerCorner),
            style = Stroke(width = 1.6.dp.toPx()),
        )
    }
}

/** Package-manager based detection avoids relying on screen resolution or touch availability. */
internal fun isV2PlasTelevisionDevice(packageManager: android.content.pm.PackageManager): Boolean =
    packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION)
