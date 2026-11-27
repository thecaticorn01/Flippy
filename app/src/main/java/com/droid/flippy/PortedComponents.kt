package com.droid.flippy

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * Shared composables ported from the upstream Dolphy project so that the
 * features incorporated from it (Cam Catch, Plugin Store, the rewritten TV
 * Cast and BLE Chat) compile against Flippy's existing design system without
 * modifying any of Flippy's own files. These are purely additive.
 */

/**
 * A full-screen scaffold with a transparent [SectionTopBar] drawn over a
 * progressively-blurred content area. The content lambda receives the height
 * of the header as padding so it can offset its first item below the bar.
 */
@Composable
fun BlurHeaderBox(
    title: String,
    onBack: (() -> Unit)? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    alwaysCollapsed: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (headerPadding: Dp) -> Unit,
) {
    val density = LocalDensity.current
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerPadding = with(density) { headerHeightPx.toDp() }

    MaterialBackground(accentColor = accentColor) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (headerHeightPx > 0)
                            Modifier.progressiveBlur(
                                blurRadius = 40f,
                                height = headerHeightPx.toFloat(),
                                direction = BlurDirection.TOP,
                            )
                        else Modifier
                    ),
            ) {
                content(headerPadding)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .onSizeChanged { headerHeightPx = it.height },
            ) {
                SectionTopBar(
                    title = title,
                    onBack = onBack,
                    transparent = true,
                    accentColor = accentColor,
                    alwaysCollapsed = alwaysCollapsed,
                    actions = actions,
                )
            }
        }
    }
}

/**
 * Returns an interaction source paired with a scale factor that shrinks the
 * target while it is pressed, for a tactile button feel.
 */
@Composable
fun rememberPressShrink(
    pressedScale: Float = 0.94f,
    source: MutableInteractionSource = remember { MutableInteractionSource() },
): Pair<MutableInteractionSource, Float> {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pressShrink"
    )
    return source to scale
}
