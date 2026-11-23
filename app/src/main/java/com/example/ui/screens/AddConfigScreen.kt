package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.configmanager.QrCodeHelper
import com.example.core.i18n.LocalShadoraStrings
import com.example.core.model.VpnProtocol
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.Shadora3DButton
import com.example.ui.components.Shadora3DKeyContainer
import com.example.ui.components.ShadoraCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.NeonCrimsonGlow
import java.util.concurrent.Executors

@Composable
fun AddConfigScreen(
    draftText: String,
    isImporting: Boolean,
    onDraftChange: (String) -> Unit,
    detectProtocol: (String) -> VpnProtocol?,
    isSubscriptionUrl: (String) -> Boolean,
    onImportSubmit: (String) -> Unit,
    onToast: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalShadoraStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showCameraScanner by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            showCameraScanner = true
        } else {
            onToast(strings.cameraPermissionNeeded)
        }
    }

    // Zero-permission Android Photo Picker for QR image decoding
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val decoded = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    bitmap?.let { QrCodeHelper.decodeFromBitmap(it) }
                }
            } catch (_: Throwable) {
                null
            }
            if (!decoded.isNullOrBlank()) {
                onDraftChange(decoded)
                onImportSubmit(decoded)
            } else {
                onToast(strings.qrNotFoundInImage)
            }
        }
    }

    // Zero-permission Storage Access Framework file picker (.ovpn, .conf, .json, .txt)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readNBytes(2 * 1024 * 1024) // 2 MB cap
                    String(bytes, Charsets.UTF_8)
                }
            } catch (_: Throwable) {
                null
            }
            if (!text.isNullOrBlank()) {
                onDraftChange(text)
            }
        }
    }

    BackHandler {
        if (showCameraScanner) {
            showCameraScanner = false
        } else {
            onClose()
        }
    }

    if (showCameraScanner && hasCameraPermission) {
        LiveQrScannerView(
            onQrDetected = { qrText ->
                showCameraScanner = false
                onDraftChange(qrText)
                onImportSubmit(qrText)
            },
            onClose = { showCameraScanner = false }
        )
        return
    }

    val detectedProto = remember(draftText) { detectProtocol(draftText) }
    val detectedSubUrl = remember(draftText) { isSubscriptionUrl(draftText) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("add_config_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = strings.close
                    )
                }
                Text(
                    text = strings.addConfigTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1
                )
            }

            // Quick Import Methods Grid (Clipboard, Camera QR, Gallery QR, File)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ImportMethodTile(
                    icon = Icons.Rounded.ContentPaste,
                    label = strings.importFromClipboard,
                    testTag = "import_clipboard_button",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val clip = clipboard.getText()?.text?.trim().orEmpty()
                        if (clip.isBlank()) {
                            onToast(strings.clipboardEmpty)
                        } else {
                            onDraftChange(clip)
                        }
                    }
                )
                ImportMethodTile(
                    icon = Icons.Rounded.QrCodeScanner,
                    label = strings.scanQrCamera,
                    testTag = "import_qr_camera_button",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (hasCameraPermission) {
                            showCameraScanner = true
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ImportMethodTile(
                    icon = Icons.Rounded.ImageSearch,
                    label = strings.scanQrGallery,
                    testTag = "import_qr_gallery_button",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
                ImportMethodTile(
                    icon = Icons.Rounded.FileOpen,
                    label = strings.importFromFile,
                    testTag = "import_file_button",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        filePickerLauncher.launch(arrayOf("text/*", "application/json", "application/octet-stream", "*/*"))
                    }
                )
            }

            // Direct Link / Subscription URL / JSON / OVPN / WireGuard Text Input Card
            ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                if (detectedSubUrl) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.detectedProtocolLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(1.dp, NeonCrimsonGlow.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "SUBSCRIPTION (HTTPS)",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = NeonCrimsonGlow
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                } else if (detectedProto != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.detectedProtocolLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ProtocolBadge(protocol = detectedProto)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = draftText,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .testTag("config_text_input"),
                    placeholder = { Text(strings.pasteConfigHint) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Shadora3DButton(
                    text = strings.importFromText,
                    onClick = { onImportSubmit(draftText) },
                    icon = Icons.Rounded.Save,
                    enabled = draftText.isNotBlank() && !isImporting,
                    loading = isImporting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("submit_config_button")
                )
            }

            // Android Share Info Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        tint = NeonCrimsonGlow
                    )
                    Text(
                        text = strings.shareSupportHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportMethodTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Shadora3DKeyContainer(
        onClick = onClick,
        cornerRadius = 16.dp,
        depth = 4.dp,
        modifier = modifier.testTag(testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = NeonCrimsonGlow,
                modifier = Modifier.size(25.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiveQrScannerView(
    onQrDetected: (String) -> Unit,
    onClose: () -> Unit
) {
    val strings = LocalShadoraStrings.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var delivered by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener(
                    {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            val decoded = QrCodeHelper.decodeFromImageProxy(imageProxy)
                            imageProxy.close()
                            if (!decoded.isNullOrBlank() && !delivered) {
                                delivered = true
                                previewView.post { onQrDetected(decoded) }
                            }
                        }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                        }
                    },
                    ContextCompat.getMainExecutor(ctx)
                )
                previewView
            }
        )

        OutlinedButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .clip(RoundedCornerShape(14.dp))
        ) {
            Text(strings.closeScanner)
        }
    }
}
