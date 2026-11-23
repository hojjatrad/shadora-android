package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import com.example.core.i18n.LocalShadoraStrings
import com.example.core.model.AppLanguage
import com.example.core.model.EngineDiagnosticInfo
import com.example.core.model.EngineSupportTier
import com.example.ui.components.ShadoraCard
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.NeonCrimsonGlow
import com.example.ui.theme.OutfitFamily

@Composable
fun AboutScreen(
    language: AppLanguage,
    diagnostics: List<EngineDiagnosticInfo>,
    modifier: Modifier = Modifier
) {
    val strings = LocalShadoraStrings.current
    val isPersian = language == AppLanguage.FA
    val abis = Build.SUPPORTED_ABIS?.joinToString(", ").orEmpty().ifBlank { "arm64-v8a, armeabi-v7a, x86_64" }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Brand Hero Card
            ShadoraCard(modifier = Modifier.fillMaxWidth(), highlighted = true) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_shadora_icon),
                        contentDescription = strings.appTitle,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Column {
                        Text(
                            text = strings.appTitle,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = OutfitFamily,
                                letterSpacing = 3.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.tagline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                MetaLine(strings.versionLabel, "${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
                Spacer(modifier = Modifier.height(6.dp))
                MetaLine(strings.androidSupportLabel, "Android 7.0 – 16 (API 24 – 36)")
                Spacer(modifier = Modifier.height(6.dp))
                MetaLine(strings.abiSupportLabel, abis)
            }

            // Honest Protocol & Engine Matrix
            Text(
                text = strings.protocolMatrixTitle,
                style = MaterialTheme.typography.titleMedium,
                color = NeonCrimsonGlow
            )

            diagnostics.forEach { info ->
                val (tierColor, tierLabel) = when (info.tier) {
                    EngineSupportTier.NATIVE_BUNDLED -> EmeraldSignal to strings.tierNativeReady
                    EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE -> AmberSignal to strings.tierBridgeReady
                    EngineSupportTier.UNSUPPORTED_NO_ENGINE -> ErrorRed to strings.tierUnsupported
                }

                ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = info.engineName,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = info.protocols.joinToString(" • ") { it.displayName },
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = tierColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, tierColor.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(tierColor)
                            )
                            Text(
                                text = tierLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = tierColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isPersian) info.noteFa else info.noteEn,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = info.license,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }

            // Security Architecture Card
            ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = EmeraldSignal)
                    Text(strings.securityHighlightsTitle, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(8.dp))
                val bullets = if (isPersian) {
                    listOf(
                        "رمزنگاری AES-256-GCM با Android Keystore برای ذخیره امن UUID، کلید خصوصی و کانفیگ‌ها در Room",
                        "حذف خودکار اطلاعات حساس از تمامی گزارش‌ها و Logها (SafeLogger)",
                        "محافظت کامل اشتراک‌ها در برابر SSRF، تغییر مسیر به IP خصوصی، و سقف ۵ مگابایت دانلود",
                        "مسدودسازی دستورات اسکریپتی ناامن در فایل‌های OpenVPN (.ovpn)",
                        "عدم اجرای کد پویا (No Dynamic Code Loading) و سازگاری با سیاست‌های Google Play"
                    )
                } else {
                    listOf(
                        "Android Keystore AES-256-GCM encryption for UUIDs, private keys, and raw configs in Room",
                        "Automatic credential redaction across all diagnostic logs (SafeLogger)",
                        "Subscription HTTPS-only + SSRF private-IP / redirect guard + 5 MB payload limit",
                        "Malicious OpenVPN script/shell directive rejection on import",
                        "Zero Dynamic Code Loading — all native code bundled cleanly inside signed APK"
                    )
                }
                bullets.forEach { line ->
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
            }

            // Open-Source Licenses & Support
            ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                Text(strings.openSourceLicensesTitle, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                val licenses = listOf(
                    "sing-box / libbox-android (net.clever-vpn:libbox-android) — GPL-3.0-or-later",
                    "WireGuard Android Tunnel (com.wireguard.android:tunnel) — Apache-2.0",
                    "AmneziaWG Android Tunnel (com.zaneschepke:amneziawg-android) — Apache-2.0 / GPL-2.0",
                    "OpenVPN 3 Core Android (io.github.tim06:vpnprotocols-openvpn) — AGPL-3.0 / Apache-2.0",
                    "ZXing Core (com.google.zxing:core) — Apache-2.0",
                    "OkHttp (com.squareup.okhttp3:okhttp) — Apache-2.0",
                    "AndroidX Jetpack Compose, Room, CameraX, Biometric — Apache-2.0",
                    "Readex Pro, Outfit, Vazirmatn, JetBrains Mono — SIL Open Font License 1.1"
                )
                licenses.forEach { lic ->
                    Text(
                        text = "• $lic",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Text(strings.supportInfoTitle, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.supportInfoBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
