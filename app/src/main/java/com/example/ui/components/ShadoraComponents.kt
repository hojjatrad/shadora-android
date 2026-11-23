package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.ui.theme.ShadoraIcons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.ConnectionState
import com.example.core.model.EngineSupportTier
import com.example.core.model.VpnProtocol
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.BrushedSilver
import com.example.ui.theme.CrimsonBaseLedge
import com.example.ui.theme.CrimsonFaceBottom
import com.example.ui.theme.CrimsonFaceMid
import com.example.ui.theme.CrimsonFaceTop
import com.example.ui.theme.CrimsonSpecularHighlight
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCrimsonGlow
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.SilverBaseLedgeLight
import com.example.ui.theme.SilverFaceBottomLight
import com.example.ui.theme.SilverFaceTopLight
import com.example.ui.theme.SilverSpecularLight
import com.example.ui.theme.TitaniumBaseLedge
import com.example.ui.theme.TitaniumFaceBottom
import com.example.ui.theme.TitaniumFaceTop
import com.example.ui.theme.TitaniumSpecularHighlight

/**
 * Luxury layered metallic card with brushed top sheen rim & crisp depth.
 */
@Composable
fun ShadoraCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(18.dp)

    val borderBrush = if (highlighted) {
        Brush.verticalGradient(
            colors = listOf(
                NeonCrimsonGlow.copy(alpha = 0.92f),
                NeonCrimson.copy(alpha = 0.35f)
            )
        )
    } else if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                TitaniumSpecularHighlight.copy(alpha = 0.52f),
                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )
        )
    }

    val surfaceBrush = if (isDark) {
        if (highlighted) {
            Brush.verticalGradient(listOf(Color(0xFF201620), Color(0xFF13151E)))
        } else {
            Brush.verticalGradient(listOf(Color(0xFF151924), Color(0xFF0E1118)))
        }
    } else {
        if (highlighted) {
            Brush.verticalGradient(listOf(Color(0xFFFFF4F6), Color(0xFFFFFFFF)))
        } else {
            Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF6F8FC)))
        }
    }

    val shadowElevation = if (highlighted) 10.dp else 4.dp

    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                ambientColor = if (highlighted) NeonCrimson.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.45f),
                spotColor = if (highlighted) NeonCrimson.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.6f)
            )
            .clip(shape)
            .background(surfaceBrush)
            .border(BorderStroke(if (highlighted) 1.4.dp else 1.dp, borderBrush), shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            content = content
        )
    }
}

/**
 * Primary 3D Sculpted Neon-Crimson Tactile Key:
 * - Physical extruded 4dp 3D crimson base ledge
 * - Vertical metallic crimson face gradient + glossy top specular bevel
 * - Spring-animated physical press-down offset + subtle haptic click
 * - Strictly responsive single-line label fit on all phone sizes
 */
@Composable
fun Shadora3DButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    depth: Dp = 4.dp
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressOffset by animateDpAsState(
        targetValue = if (pressed && enabled) depth - 0.5.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 750f),
        label = "btn3d_offset"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 750f),
        label = "btn3d_scale"
    )

    val shape = RoundedCornerShape(15.dp)

    Box(
        modifier = modifier
            .scale(scale)
            .alpha(if (enabled) 1f else 0.48f)
            .padding(bottom = depth)
    ) {
        // 3D Extruded Bottom Ledge
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = depth)
                .shadow(
                    elevation = if (pressed) 2.dp else 9.dp,
                    shape = shape,
                    ambientColor = NeonCrimson.copy(alpha = 0.55f),
                    spotColor = NeonCrimson.copy(alpha = 0.65f)
                )
                .clip(shape)
                .background(CrimsonBaseLedge)
        )

        // Raised Glossy Crimson Keycap Face
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .offset(y = pressOffset)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(CrimsonFaceTop, CrimsonFaceMid, CrimsonFaceBottom)
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            CrimsonSpecularHighlight.copy(alpha = 0.85f),
                            NeonCrimson.copy(alpha = 0.25f),
                            CrimsonBaseLedge.copy(alpha = 0.7f)
                        )
                    ),
                    shape = shape
                )
                .clickable(
                    enabled = enabled && !loading,
                    role = Role.Button,
                    interactionSource = interactionSource,
                    indication = ripple(color = Color.White),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(7.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Secondary 3D Brushed Titanium / Silver Tactile Key:
 * - Physical 3.5dp extruded metallic base ledge
 * - Brushed titanium (dark) or satin silver (light) bevel face + top specular highlight
 * - Spring-animated press-down depression + haptic feedback
 */
@Composable
fun Shadora3DOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    depth: Dp = 3.5.dp
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressOffset by animateDpAsState(
        targetValue = if (pressed && enabled) depth - 0.5.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 750f),
        label = "btn3d_out_offset"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 750f),
        label = "btn3d_out_scale"
    )

    val shape = RoundedCornerShape(15.dp)
    val ledgeColor = if (isDark) TitaniumBaseLedge else SilverBaseLedgeLight
    val faceColors = if (isDark) {
        listOf(TitaniumFaceTop, TitaniumFaceBottom)
    } else {
        listOf(SilverFaceTopLight, SilverFaceBottomLight)
    }
    val rimColors = if (isDark) {
        listOf(
            TitaniumSpecularHighlight.copy(alpha = 0.78f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        )
    } else {
        listOf(
            SilverSpecularLight,
            SilverBaseLedgeLight.copy(alpha = 0.85f)
        )
    }

    Box(
        modifier = modifier
            .scale(scale)
            .alpha(if (enabled) 1f else 0.46f)
            .padding(bottom = depth)
    ) {
        // 3D Extruded Bottom Ledge
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = depth)
                .shadow(
                    elevation = if (pressed) 1.dp else 5.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = 0.55f),
                    spotColor = Color.Black.copy(alpha = 0.65f)
                )
                .clip(shape)
                .background(ledgeColor)
        )

        // Raised Metallic Keycap Face
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .offset(y = pressOffset)
                .clip(shape)
                .background(Brush.verticalGradient(faceColors))
                .border(1.dp, Brush.verticalGradient(rimColors), shape)
                .clickable(
                    enabled = enabled && !loading,
                    role = Role.Button,
                    interactionSource = interactionSource,
                    indication = ripple(),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                )
                .padding(horizontal = 12.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = iconTint ?: NeonCrimsonGlow
                    )
                    Spacer(modifier = Modifier.size(7.dp))
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint ?: MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.size(7.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Compact 3D Beveled Surface Key for Icon/Pill Controls (header '+', language switcher, quick actions).
 */
@Composable
fun Shadora3DKeyContainer(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentCrimson: Boolean = false,
    cornerRadius: Dp = 13.dp,
    depth: Dp = 3.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressOffset by animateDpAsState(
        targetValue = if (pressed) depth - 0.5.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 750f),
        label = "key3d_offset"
    )
    val shape = RoundedCornerShape(cornerRadius)

    val ledgeColor = when {
        accentCrimson -> CrimsonBaseLedge
        isDark -> TitaniumBaseLedge
        else -> SilverBaseLedgeLight
    }
    val faceBrush = when {
        accentCrimson -> Brush.verticalGradient(listOf(CrimsonFaceTop, CrimsonFaceBottom))
        isDark -> Brush.verticalGradient(listOf(TitaniumFaceTop, TitaniumFaceBottom))
        else -> Brush.verticalGradient(listOf(SilverFaceTopLight, SilverFaceBottomLight))
    }
    val rimBrush = when {
        accentCrimson -> Brush.verticalGradient(listOf(CrimsonSpecularHighlight.copy(alpha = 0.85f), CrimsonBaseLedge))
        isDark -> Brush.verticalGradient(listOf(TitaniumSpecularHighlight.copy(alpha = 0.78f), Color.Transparent))
        else -> Brush.verticalGradient(listOf(SilverSpecularLight, SilverBaseLedgeLight))
    }

    Box(modifier = modifier.padding(bottom = depth)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = depth)
                .shadow(
                    elevation = if (pressed) 1.dp else 6.dp,
                    shape = shape,
                    ambientColor = if (accentCrimson) NeonCrimson.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f),
                    spotColor = if (accentCrimson) NeonCrimson.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.6f)
                )
                .clip(shape)
                .background(ledgeColor)
        )
        Box(
            modifier = Modifier
                .offset(y = pressOffset)
                .clip(shape)
                .background(faceBrush)
                .border(1.dp, rimBrush, shape)
                .clickable(
                    role = Role.Button,
                    interactionSource = interactionSource,
                    indication = ripple(color = if (accentCrimson) Color.White else Color.Unspecified),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                ),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

@Composable
fun ProtocolBadge(
    protocol: VpnProtocol,
    modifier: Modifier = Modifier
) {
    val dotColor = when (protocol.defaultTier) {
        EngineSupportTier.NATIVE_BUNDLED -> EmeraldSignal
        EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE -> NeonCrimsonGlow
        EngineSupportTier.UNSUPPORTED_NO_ENGINE -> ErrorRed
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.75f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = protocol.displayName.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun LatencyBadge(
    latencyMs: Int,
    timeoutLabel: String,
    modifier: Modifier = Modifier
) {
    val (text, color) = when {
        latencyMs in 1..150 -> "${latencyMs}ms" to EmeraldSignal
        latencyMs in 151..350 -> "${latencyMs}ms" to AmberSignal
        latencyMs > 350 -> "${latencyMs}ms" to ErrorRed
        else -> timeoutLabel to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.SemiBold
            ),
            color = color
        )
    }
}

/**
 * 3D Sculpted Metallic Obsidian-Crimson Orbital Power Reactor Button:
 * - Outer brushed bezel well with concentric depth shadow
 * - Dual counter-rotating orbital neon arcs
 * - Physically raised 3D obsidian dome with top-left metallic specular reflection,
 *   lower 3D extruded bevel, spring physical press-down offset, and haptic pulse
 */
@Composable
fun OrbitalConnectButton(
    connectionState: ConnectionState,
    actionHint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.945f else 1f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 480f),
        label = "connect_btn_scale"
    )
    val domeDepression by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 620f),
        label = "connect_dome_press"
    )

    val isConnected = connectionState is ConnectionState.Connected
    val isBusy = connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Preparing ||
        connectionState is ConnectionState.Reconnecting ||
        connectionState is ConnectionState.Disconnecting
    val isError = connectionState is ConnectionState.Error

    val infiniteTransition = rememberInfiniteTransition(label = "orbital_reactor")
    val radarAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isBusy) 1150 else 6200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_angle"
    )
    val counterAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isBusy) 1800 else 9500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "counter_angle"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_glow"
    )

    val ringPrimary = when {
        isConnected -> NeonCrimson
        isBusy -> AmberSignal
        isError -> ErrorRed
        else -> BrushedSilver.copy(alpha = 0.72f)
    }
    val glowAccent = when {
        isConnected -> NeonCrimsonGlow
        isBusy -> AmberSignal
        isError -> ErrorRed
        else -> NeonCrimson.copy(alpha = 0.38f)
    }
    val silverTrack = MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier
            .size(232.dp)
            .scale(scale)
            .clip(CircleShape)
            .testTag("connect_toggle_button")
            .clickable(
                role = Role.Button,
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = 116.dp, color = glowAccent),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val pressShiftPx = domeDepression * 5.dp.toPx()

            // 1. Ambient neon reactor halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowAccent.copy(alpha = if (isConnected) 0.44f else 0.18f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * (if (isConnected) pulse else 0.95f)
                ),
                radius = radius,
                center = center
            )

            // 2. Outer Recessed Metallic Well Ring (3D chassis bezel)
            val chassisRadius = radius * 0.88f
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = if (isDark) {
                        listOf(Color(0xFF07090E), Color(0xFF1E2331))
                    } else {
                        listOf(Color(0xFFCFD6E4), Color(0xFFFFFFFF))
                    },
                    startY = center.y - chassisRadius,
                    endY = center.y + chassisRadius
                ),
                radius = chassisRadius,
                center = center
            )
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        silverTrack.copy(alpha = 0.2f),
                        BrushedSilver.copy(alpha = 0.55f)
                    )
                ),
                radius = chassisRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 3. Outer & Inner Counter-Rotating Orbital Arcs
            val arcRadius = radius * 0.79f
            val topLeft = Offset(center.x - arcRadius, center.y - arcRadius)
            val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
            drawArc(
                color = ringPrimary.copy(alpha = 0.22f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 4.dp.toPx())
            )
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(Color.Transparent, ringPrimary, glowAccent),
                    center = center
                ),
                startAngle = radarAngle,
                sweepAngle = if (isConnected) 250f else 120f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round)
            )

            val innerTrackRadius = radius * 0.72f
            val innerTopLeft = Offset(center.x - innerTrackRadius, center.y - innerTrackRadius)
            val innerArcSize = Size(innerTrackRadius * 2f, innerTrackRadius * 2f)
            drawArc(
                color = BrushedSilver.copy(alpha = if (isConnected) 0.5f else 0.28f),
                startAngle = counterAngle,
                sweepAngle = 68f,
                useCenter = false,
                topLeft = innerTopLeft,
                size = innerArcSize,
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            )

            // 4. 3D Extruded Base Pedestal Shadow under the Dome
            val domeRadius = radius * 0.65f
            val ledgeOffsetY = 6.dp.toPx()
            drawCircle(
                color = if (isConnected) CrimsonBaseLedge else if (isDark) ObsidianBlack else Color(0xFF9EA8BC),
                radius = domeRadius,
                center = Offset(center.x, center.y + ledgeOffsetY)
            )

            // 5. Raised 3D Sculpted Dome Face (shifts down physically on press)
            val domeCenter = Offset(center.x, center.y + pressShiftPx)
            val domeBrush = if (isConnected) {
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF3A0C18),
                        Color(0xFF1D0810),
                        Color(0xFF0E070B)
                    ),
                    center = Offset(domeCenter.x - domeRadius * 0.28f, domeCenter.y - domeRadius * 0.32f),
                    radius = domeRadius * 1.35f
                )
            } else if (isDark) {
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF282F42),
                        Color(0xFF151924),
                        Color(0xFF0B0D14)
                    ),
                    center = Offset(domeCenter.x - domeRadius * 0.28f, domeCenter.y - domeRadius * 0.32f),
                    radius = domeRadius * 1.35f
                )
            } else {
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFFFFF),
                        Color(0xFFEDF1F8),
                        Color(0xFFD5DCEB)
                    ),
                    center = Offset(domeCenter.x - domeRadius * 0.28f, domeCenter.y - domeRadius * 0.32f),
                    radius = domeRadius * 1.35f
                )
            }
            drawCircle(
                brush = domeBrush,
                radius = domeRadius,
                center = domeCenter
            )

            // 6. Specular 3D Top Bevel Rim around the Dome
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = if (isConnected) {
                        listOf(CrimsonSpecularHighlight.copy(alpha = 0.9f), NeonCrimson, CrimsonBaseLedge)
                    } else if (isDark) {
                        listOf(BrushedSilver.copy(alpha = 0.72f), ringPrimary.copy(alpha = 0.3f), ObsidianBlack)
                    } else {
                        listOf(Color.White, SilverBaseLedgeLight)
                    },
                    startY = domeCenter.y - domeRadius,
                    endY = domeCenter.y + domeRadius
                ),
                radius = domeRadius,
                center = domeCenter,
                style = Stroke(width = 2.4.dp.toPx())
            )

            // 7. Glossy Top-Edge Crescent Highlight inside Dome
            drawArc(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isDark) 0.16f else 0.55f),
                        Color.Transparent
                    ),
                    startY = domeCenter.y - domeRadius * 0.85f,
                    endY = domeCenter.y
                ),
                startAngle = 210f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(domeCenter.x - domeRadius * 0.86f, domeCenter.y - domeRadius * 0.86f),
                size = Size(domeRadius * 1.72f, domeRadius * 1.72f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        Column(
            modifier = Modifier.offset(y = (domeDepression * 5f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = if (isConnected) ShadoraIcons.Shield else ShadoraIcons.PowerSettingsNew,
                contentDescription = actionHint,
                tint = when {
                    isConnected -> NeonCrimsonGlow
                    isBusy -> AmberSignal
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(46.dp)
            )
            Text(
                text = actionHint,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp
                ),
                color = if (isConnected) NeonCrimsonGlow else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
fun TrafficSparkline(
    rxHistory: List<Float>,
    txHistory: List<Float>,
    modifier: Modifier = Modifier
) {
    val lineRx = NeonCrimsonGlow
    val lineTx = EmeraldSignal
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
    ) {
        val w = size.width
        val h = size.height
        drawLine(gridColor, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 1f)
        drawLine(gridColor, Offset(0f, h - 1f), Offset(w, h - 1f), strokeWidth = 1f)

        val maxVal = (rxHistory + txHistory).maxOrNull()?.coerceAtLeast(1024f) ?: 1024f

        fun drawSeries(values: List<Float>, color: Color, fillAlpha: Float) {
            if (values.size < 2) return
            val linePath = Path()
            val fillPath = Path()
            val stepX = w / (values.size - 1).coerceAtLeast(1)
            values.forEachIndexed { idx, v ->
                val x = idx * stepX
                val y = h - ((v / maxVal).coerceIn(0f, 1f) * (h - 10f)) - 5f
                if (idx == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(w, h)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = fillAlpha), Color.Transparent)
                )
            )
            drawPath(
                path = linePath,
                color = color,
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        drawSeries(txHistory, lineTx.copy(alpha = 0.85f), 0.14f)
        drawSeries(rxHistory, lineRx, 0.24f)
    }
}
