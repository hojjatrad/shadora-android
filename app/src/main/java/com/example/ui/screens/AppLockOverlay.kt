package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.i18n.LocalShadoraStrings
import com.example.security.AppLockManager
import com.example.ui.components.Shadora3DKeyContainer
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCrimsonGlow
import com.example.ui.theme.OutfitFamily
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full-screen luxury Obsidian-Crimson App Lock Overlay with:
 * - Biometric Fingerprint/Face unlock prompt (API 28+)
 * - 3D metallic tactile numeric PIN pad fallback
 * - Animated error shake feedback
 */
@Composable
fun AppLockOverlay(
    storedPinHash: String,
    biometricEnabled: Boolean,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalShadoraStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val biometricAvailable = remember(context, biometricEnabled) {
        biometricEnabled && AppLockManager.canUseBiometric(context)
    }

    var pinDraft by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val shakeOffset = remember { Animatable(0f) }

    fun triggerBiometric() {
        if (!biometricAvailable) return
        AppLockManager.authenticateBiometric(
            context = context,
            title = strings.appTitle,
            subtitle = strings.appLockSubtitle,
            negativeLabel = strings.usePinFallback,
            onSuccess = onUnlocked,
            onError = { /* User can enter PIN seamlessly */ }
        )
    }

    LaunchedEffect(biometricAvailable) {
        if (biometricAvailable) {
            triggerBiometric()
        }
    }

    fun onDigitPressed(digit: Char) {
        if (pinDraft.length >= 6) return
        errorMessage = null
        val updated = pinDraft + digit
        pinDraft = updated
        if (updated.length >= 4 && AppLockManager.verifyPin(updated, storedPinHash)) {
            onUnlocked()
            return
        }
        if (updated.length == 6) {
            errorMessage = strings.wrongPinError
            pinDraft = ""
            scope.launch {
                for (delta in listOf(-18f, 18f, -12f, 12f, -6f, 0f)) {
                    shakeOffset.animateTo(delta, animationSpec = tween(42))
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(NeonCrimson.copy(alpha = 0.2f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.22f),
                        radius = size.maxDimension * 0.52f
                    )
                )
            }
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.5.dp, NeonCrimsonGlow.copy(alpha = 0.7f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_shadora_icon),
                    contentDescription = strings.appTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = strings.appTitle,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = OutfitFamily,
                        letterSpacing = 3.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = NeonCrimsonGlow,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = strings.enterPinTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // PIN Dots Indicator
            Row(
                modifier = Modifier
                    .offset { IntOffset(shakeOffset.value.roundToInt(), 0) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                for (i in 0 until 6) {
                    val filled = i < pinDraft.length
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(
                                if (filled) NeonCrimsonGlow
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                width = 1.dp,
                                color = if (filled) NeonCrimson else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                    )
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = ErrorRed,
                    textAlign = TextAlign.Center
                )
            } else {
                Spacer(modifier = Modifier.height(18.dp))
            }

            // 3D Sculpted Numeric Keypad
            val rows = listOf(
                listOf('1', '2', '3'),
                listOf('4', '5', '6'),
                listOf('7', '8', '9')
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rows.forEach { rowDigits ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowDigits.forEach { digit ->
                            Pin3DDigitKey(
                                label = digit.toString(),
                                onClick = { onDigitPressed(digit) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("pin_key_$digit")
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Bottom-Start: Biometric button (or empty spacer)
                    if (biometricAvailable) {
                        Shadora3DKeyContainer(
                            onClick = { triggerBiometric() },
                            accentCrimson = true,
                            cornerRadius = 16.dp,
                            depth = 4.dp,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pin_biometric_button")
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(58.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Fingerprint,
                                    contentDescription = strings.unlockWithBiometric,
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Pin3DDigitKey(
                        label = "0",
                        onClick = { onDigitPressed('0') },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pin_key_0")
                    )

                    // Bottom-End: Backspace
                    Shadora3DKeyContainer(
                        onClick = {
                            if (pinDraft.isNotEmpty()) {
                                pinDraft = pinDraft.dropLast(1)
                                errorMessage = null
                            }
                        },
                        cornerRadius = 16.dp,
                        depth = 4.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pin_backspace_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Backspace,
                                contentDescription = "Backspace",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pin3DDigitKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Shadora3DKeyContainer(
        onClick = onClick,
        cornerRadius = 16.dp,
        depth = 4.dp,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
