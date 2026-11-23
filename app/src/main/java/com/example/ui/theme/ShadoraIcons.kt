package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight Material-style 24dp vector icons replacing the 55 MB `material-icons-extended` DEX.
 * Keeps the universal multi-ABI APK lean and speeds up Android installation (`dex2oat`) by ~4x.
 */
object ShadoraIcons {

    private fun icon(
        name: String,
        autoMirror: Boolean = false,
        block: PathBuilder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.NonZero,
            pathBuilder = block
        )
    }.build()

    private fun strokeIcon(
        name: String,
        autoMirror: Boolean = false,
        strokeWidth: Float = 2f,
        block: PathBuilder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }.build()

    val Shield: ImageVector by lazy {
        icon("Shield") {
            moveTo(12f, 2f)
            lineTo(4f, 5f)
            verticalLineTo(11.09f)
            curveTo(4f, 16.14f, 7.41f, 20.85f, 12f, 22f)
            curveTo(16.59f, 20.85f, 20f, 16.14f, 20f, 11.09f)
            verticalLineTo(5f)
            lineTo(12f, 2f)
            close()
        }
    }

    val ShieldOutlined: ImageVector by lazy {
        strokeIcon("ShieldOutlined", strokeWidth = 1.9f) {
            moveTo(12f, 2.5f)
            lineTo(4.5f, 5.4f)
            verticalLineTo(11.1f)
            curveTo(4.5f, 15.8f, 7.7f, 20.2f, 12f, 21.4f)
            curveTo(16.3f, 20.2f, 19.5f, 15.8f, 19.5f, 11.1f)
            verticalLineTo(5.4f)
            close()
        }
    }

    val VerifiedUser: ImageVector by lazy {
        icon("VerifiedUser") {
            moveTo(12f, 2f)
            lineTo(4f, 5f)
            verticalLineTo(11.09f)
            curveTo(4f, 16.14f, 7.41f, 20.85f, 12f, 22f)
            curveTo(16.59f, 20.85f, 20f, 16.14f, 20f, 11.09f)
            verticalLineTo(5f)
            lineTo(12f, 2f)
            close()
            moveTo(10.5f, 16.2f)
            lineTo(7f, 12.7f)
            lineTo(8.4f, 11.3f)
            lineTo(10.5f, 13.4f)
            lineTo(15.6f, 8.3f)
            lineTo(17f, 9.7f)
            lineTo(10.5f, 16.2f)
            close()
        }
    }

    val Security: ImageVector by lazy {
        icon("Security") {
            moveTo(12f, 2f)
            lineTo(4f, 5f)
            verticalLineTo(11f)
            curveTo(4f, 16.05f, 7.41f, 20.76f, 12f, 22f)
            curveTo(16.59f, 20.76f, 20f, 16.05f, 20f, 11f)
            verticalLineTo(5f)
            lineTo(12f, 2f)
            close()
            moveTo(12f, 11.99f)
            horizontalLineTo(18f)
            curveTo(17.53f, 15.72f, 15.16f, 19.04f, 12f, 19.93f)
            verticalLineTo(12f)
            horizontalLineTo(6f)
            verticalLineTo(6.3f)
            lineTo(12f, 4.05f)
            verticalLineTo(11.99f)
            close()
        }
    }

    val PowerSettingsNew: ImageVector by lazy {
        strokeIcon("PowerSettingsNew", strokeWidth = 2.4f) {
            moveTo(12f, 3f)
            verticalLineTo(11f)
            moveTo(17.2f, 6.2f)
            curveTo(18.9f, 7.6f, 20f, 9.7f, 20f, 12f)
            curveTo(20f, 16.4f, 16.4f, 20f, 12f, 20f)
            curveTo(7.6f, 20f, 4f, 16.4f, 4f, 12f)
            curveTo(4f, 9.7f, 5.1f, 7.6f, 6.8f, 6.2f)
        }
    }

    val Dns: ImageVector by lazy {
        icon("Dns") {
            moveTo(19f, 3f)
            horizontalLineTo(5f)
            curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
            verticalLineTo(9f)
            curveTo(3f, 10.1f, 3.9f, 11f, 5f, 11f)
            horizontalLineTo(19f)
            curveTo(20.1f, 11f, 21f, 10.1f, 21f, 9f)
            verticalLineTo(5f)
            curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
            close()
            moveTo(7f, 8.5f)
            curveTo(6.17f, 8.5f, 5.5f, 7.83f, 5.5f, 7f)
            curveTo(5.5f, 6.17f, 6.17f, 5.5f, 7f, 5.5f)
            curveTo(7.83f, 5.5f, 8.5f, 6.17f, 8.5f, 7f)
            curveTo(8.5f, 7.83f, 7.83f, 8.5f, 7f, 8.5f)
            close()
            moveTo(19f, 13f)
            horizontalLineTo(5f)
            curveTo(3.9f, 13f, 3f, 13.9f, 3f, 15f)
            verticalLineTo(19f)
            curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
            horizontalLineTo(19f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            verticalLineTo(15f)
            curveTo(21f, 13.9f, 20.1f, 13f, 19f, 13f)
            close()
            moveTo(7f, 18.5f)
            curveTo(6.17f, 18.5f, 5.5f, 17.83f, 5.5f, 17f)
            curveTo(5.5f, 16.17f, 6.17f, 15.5f, 7f, 15.5f)
            curveTo(7.83f, 15.5f, 8.5f, 16.17f, 8.5f, 17f)
            curveTo(8.5f, 17.83f, 7.83f, 18.5f, 7f, 18.5f)
            close()
        }
    }

    val DnsOutlined: ImageVector by lazy {
        strokeIcon("DnsOutlined", strokeWidth = 1.9f) {
            moveTo(4f, 4f)
            horizontalLineTo(20f)
            verticalLineTo(10f)
            horizontalLineTo(4f)
            close()
            moveTo(4f, 14f)
            horizontalLineTo(20f)
            verticalLineTo(20f)
            horizontalLineTo(4f)
            close()
            moveTo(7.5f, 7f)
            horizontalLineTo(7.6f)
            moveTo(7.5f, 17f)
            horizontalLineTo(7.6f)
        }
    }

    val CloudSync: ImageVector by lazy {
        icon("CloudSync") {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
            curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
            curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
            horizontalLineTo(19f)
            curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
            curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
            close()
        }
    }

    val CloudSyncOutlined: ImageVector by lazy {
        strokeIcon("CloudSyncOutlined", strokeWidth = 1.9f) {
            moveTo(6.5f, 19f)
            horizontalLineTo(18f)
            curveTo(20.2f, 19f, 22f, 17.2f, 22f, 15f)
            curveTo(22f, 12.9f, 20.4f, 11.2f, 18.4f, 11f)
            curveTo(17.7f, 7.6f, 14.8f, 5f, 11.2f, 5f)
            curveTo(8.1f, 5f, 5.5f, 7.1f, 4.7f, 10f)
            curveTo(2.6f, 10.4f, 1.5f, 12.2f, 1.5f, 14.2f)
            curveTo(1.5f, 16.9f, 3.7f, 19f, 6.5f, 19f)
            close()
        }
    }

    val Bolt: ImageVector by lazy {
        icon("Bolt") {
            moveTo(13f, 2f)
            lineTo(4.5f, 13.5f)
            horizontalLineTo(11f)
            lineTo(10f, 22f)
            lineTo(19.5f, 10.5f)
            horizontalLineTo(13f)
            lineTo(13f, 2f)
            close()
        }
    }

    val ArrowDownward: ImageVector by lazy {
        strokeIcon("ArrowDownward", strokeWidth = 2.2f) {
            moveTo(12f, 4f)
            verticalLineTo(19f)
            moveTo(6f, 13.5f)
            lineTo(12f, 19.5f)
            lineTo(18f, 13.5f)
        }
    }

    val ArrowUpward: ImageVector by lazy {
        strokeIcon("ArrowUpward", strokeWidth = 2.2f) {
            moveTo(12f, 20f)
            verticalLineTo(5f)
            moveTo(6f, 10.5f)
            lineTo(12f, 4.5f)
            lineTo(18f, 10.5f)
        }
    }

    val SwapHoriz: ImageVector by lazy {
        strokeIcon("SwapHoriz", strokeWidth = 2f) {
            moveTo(6f, 9f)
            horizontalLineTo(19f)
            moveTo(15f, 5f)
            lineTo(19f, 9f)
            lineTo(15f, 13f)
            moveTo(18f, 16f)
            horizontalLineTo(5f)
            moveTo(9f, 12f)
            lineTo(5f, 16f)
            lineTo(9f, 20f)
        }
    }

    val DataUsage: ImageVector by lazy {
        strokeIcon("DataUsage", strokeWidth = 2.2f) {
            moveTo(12f, 3f)
            curveTo(17f, 3f, 21f, 7f, 21f, 12f)
            curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f)
            curveTo(3f, 9.5f, 4f, 7.2f, 5.7f, 5.6f)
        }
    }

    val Schedule: ImageVector by lazy {
        strokeIcon("Schedule", strokeWidth = 2f) {
            moveTo(12f, 3f)
            curveTo(17f, 3f, 21f, 7f, 21f, 12f)
            curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f)
            curveTo(3f, 7f, 7f, 3f, 12f, 3f)
            close()
            moveTo(12f, 7f)
            verticalLineTo(12.2f)
            lineTo(15.5f, 14.3f)
        }
    }

    val Wifi: ImageVector by lazy {
        strokeIcon("Wifi", strokeWidth = 2f) {
            moveTo(2.5f, 8.8f)
            curveTo(5.1f, 6.4f, 8.4f, 5f, 12f, 5f)
            curveTo(15.6f, 5f, 18.9f, 6.4f, 21.5f, 8.8f)
            moveTo(5.5f, 12.2f)
            curveTo(7.3f, 10.5f, 9.6f, 9.5f, 12f, 9.5f)
            curveTo(14.4f, 9.5f, 16.7f, 10.5f, 18.5f, 12.2f)
            moveTo(8.8f, 15.6f)
            curveTo(9.7f, 14.7f, 10.8f, 14.2f, 12f, 14.2f)
            curveTo(13.2f, 14.2f, 14.3f, 14.7f, 15.2f, 15.6f)
            moveTo(12f, 19f)
            horizontalLineTo(12.1f)
        }
    }

    val Public: ImageVector by lazy {
        strokeIcon("Public", strokeWidth = 1.9f) {
            moveTo(12f, 2.5f)
            curveTo(17.2f, 2.5f, 21.5f, 6.8f, 21.5f, 12f)
            curveTo(21.5f, 17.2f, 17.2f, 21.5f, 12f, 21.5f)
            curveTo(6.8f, 21.5f, 2.5f, 17.2f, 2.5f, 12f)
            curveTo(2.5f, 6.8f, 6.8f, 2.5f, 12f, 2.5f)
            close()
            moveTo(3f, 12f)
            horizontalLineTo(21f)
            moveTo(12f, 2.5f)
            curveTo(14.8f, 5.3f, 15.8f, 8.6f, 15.8f, 12f)
            curveTo(15.8f, 15.4f, 14.8f, 18.7f, 12f, 21.5f)
            curveTo(9.2f, 18.7f, 8.2f, 15.4f, 8.2f, 12f)
            curveTo(8.2f, 8.6f, 9.2f, 5.3f, 12f, 2.5f)
        }
    }

    val Language: ImageVector by lazy { Public }

    val Radar: ImageVector by lazy {
        strokeIcon("Radar", strokeWidth = 2f) {
            moveTo(12f, 3f)
            curveTo(17f, 3f, 21f, 7f, 21f, 12f)
            curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f)
            curveTo(3f, 7f, 7f, 3f, 12f, 3f)
            moveTo(12f, 7.5f)
            curveTo(14.5f, 7.5f, 16.5f, 9.5f, 16.5f, 12f)
            curveTo(16.5f, 14.5f, 14.5f, 16.5f, 12f, 16.5f)
            moveTo(12f, 12f)
            lineTo(17.5f, 6.5f)
        }
    }

    val NetworkPing: ImageVector by lazy {
        strokeIcon("NetworkPing", strokeWidth = 2f) {
            moveTo(3f, 18f)
            horizontalLineTo(21f)
            moveTo(4f, 8f)
            lineTo(11f, 15f)
            lineTo(18.5f, 7.5f)
            moveTo(15f, 7.5f)
            horizontalLineTo(18.5f)
            verticalLineTo(11f)
        }
    }

    val ErrorOutline: ImageVector by lazy {
        strokeIcon("ErrorOutline", strokeWidth = 2f) {
            moveTo(12f, 3f)
            curveTo(17f, 3f, 21f, 7f, 21f, 12f)
            curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f)
            curveTo(3f, 7f, 7f, 3f, 12f, 3f)
            close()
            moveTo(12f, 8f)
            verticalLineTo(13f)
            moveTo(12f, 16.5f)
            horizontalLineTo(12.1f)
        }
    }

    val WarningAmber: ImageVector by lazy {
        strokeIcon("WarningAmber", strokeWidth = 2f) {
            moveTo(12f, 3.5f)
            lineTo(21f, 19.5f)
            horizontalLineTo(3f)
            close()
            moveTo(12f, 9.5f)
            verticalLineTo(14f)
            moveTo(12f, 17f)
            horizontalLineTo(12.1f)
        }
    }

    val ContentCopy: ImageVector by lazy {
        strokeIcon("ContentCopy", strokeWidth = 1.9f) {
            moveTo(9f, 9f)
            horizontalLineTo(19f)
            verticalLineTo(20f)
            horizontalLineTo(9f)
            close()
            moveTo(15f, 9f)
            verticalLineTo(5f)
            horizontalLineTo(5f)
            verticalLineTo(16f)
            horizontalLineTo(9f)
        }
    }

    val ContentPaste: ImageVector by lazy {
        strokeIcon("ContentPaste", strokeWidth = 1.9f) {
            moveTo(9f, 4f)
            horizontalLineTo(15f)
            verticalLineTo(7f)
            horizontalLineTo(9f)
            close()
            moveTo(9f, 5.5f)
            horizontalLineTo(5.5f)
            verticalLineTo(20f)
            horizontalLineTo(18.5f)
            verticalLineTo(5.5f)
            horizontalLineTo(15f)
        }
    }

    val DeleteOutline: ImageVector by lazy {
        strokeIcon("DeleteOutline", strokeWidth = 1.9f) {
            moveTo(5f, 6.5f)
            horizontalLineTo(19f)
            moveTo(9f, 6.5f)
            verticalLineTo(4f)
            horizontalLineTo(15f)
            verticalLineTo(6.5f)
            moveTo(7f, 6.5f)
            verticalLineTo(20f)
            horizontalLineTo(17f)
            verticalLineTo(6.5f)
            moveTo(10f, 10.5f)
            verticalLineTo(16.5f)
            moveTo(14f, 10.5f)
            verticalLineTo(16.5f)
        }
    }

    val DeleteSweep: ImageVector by lazy {
        strokeIcon("DeleteSweep", strokeWidth = 1.9f) {
            moveTo(3f, 7f)
            horizontalLineTo(13f)
            moveTo(5f, 7f)
            verticalLineTo(19f)
            horizontalLineTo(11f)
            verticalLineTo(7f)
            moveTo(15f, 9f)
            horizontalLineTo(21f)
            moveTo(15f, 13f)
            horizontalLineTo(20f)
            moveTo(15f, 17f)
            horizontalLineTo(18.5f)
        }
    }

    val CleaningServices: ImageVector by lazy {
        strokeIcon("CleaningServices", strokeWidth = 1.9f) {
            moveTo(10f, 3f)
            horizontalLineTo(14f)
            verticalLineTo(10f)
            horizontalLineTo(10f)
            close()
            moveTo(5f, 10f)
            horizontalLineTo(19f)
            lineTo(20.5f, 20f)
            horizontalLineTo(3.5f)
            close()
            moveTo(9f, 15f)
            verticalLineTo(20f)
            moveTo(15f, 15f)
            verticalLineTo(20f)
        }
    }

    val Code: ImageVector by lazy {
        strokeIcon("Code", strokeWidth = 2.1f) {
            moveTo(8.5f, 7f)
            lineTo(3.5f, 12f)
            lineTo(8.5f, 17f)
            moveTo(15.5f, 7f)
            lineTo(20.5f, 12f)
            lineTo(15.5f, 17f)
        }
    }

    val StarBorder: ImageVector by lazy {
        strokeIcon("StarBorder", strokeWidth = 1.9f) {
            moveTo(12f, 3f)
            lineTo(14.8f, 8.7f)
            lineTo(21f, 9.6f)
            lineTo(16.5f, 14f)
            lineTo(17.6f, 20.2f)
            lineTo(12f, 17.3f)
            lineTo(6.4f, 20.2f)
            lineTo(7.5f, 14f)
            lineTo(3f, 9.6f)
            lineTo(9.2f, 8.7f)
            close()
        }
    }

    val QrCodeScanner: ImageVector by lazy {
        strokeIcon("QrCodeScanner", strokeWidth = 2f) {
            moveTo(4f, 8f)
            verticalLineTo(4f)
            horizontalLineTo(8f)
            moveTo(16f, 4f)
            horizontalLineTo(20f)
            verticalLineTo(8f)
            moveTo(20f, 16f)
            verticalLineTo(20f)
            horizontalLineTo(16f)
            moveTo(8f, 20f)
            horizontalLineTo(4f)
            verticalLineTo(16f)
            moveTo(8f, 8f)
            horizontalLineTo(11f)
            verticalLineTo(11f)
            horizontalLineTo(8f)
            close()
            moveTo(13f, 13f)
            horizontalLineTo(16f)
            verticalLineTo(16f)
            horizontalLineTo(13f)
            close()
        }
    }

    val ImageSearch: ImageVector by lazy {
        strokeIcon("ImageSearch", strokeWidth = 1.9f) {
            moveTo(4f, 5f)
            horizontalLineTo(18f)
            verticalLineTo(18f)
            horizontalLineTo(4f)
            close()
            moveTo(4f, 14.5f)
            lineTo(8.5f, 10.5f)
            lineTo(12.5f, 14f)
            lineTo(15f, 12f)
            lineTo(18f, 15f)
            moveTo(17.5f, 17.5f)
            lineTo(21f, 21f)
        }
    }

    val FileOpen: ImageVector by lazy {
        strokeIcon("FileOpen", strokeWidth = 1.9f) {
            moveTo(6f, 3f)
            horizontalLineTo(14f)
            lineTo(18f, 7f)
            verticalLineTo(20f)
            horizontalLineTo(6f)
            close()
            moveTo(14f, 3f)
            verticalLineTo(7f)
            horizontalLineTo(18f)
        }
    }

    val Save: ImageVector by lazy {
        strokeIcon("Save", strokeWidth = 1.9f) {
            moveTo(5f, 4f)
            horizontalLineTo(16.5f)
            lineTo(20f, 7.5f)
            verticalLineTo(20f)
            horizontalLineTo(5f)
            close()
            moveTo(8f, 4f)
            verticalLineTo(9f)
            horizontalLineTo(15f)
            verticalLineTo(4f)
            moveTo(8f, 20f)
            verticalLineTo(14f)
            horizontalLineTo(16f)
            verticalLineTo(20f)
        }
    }

    val Download: ImageVector by lazy {
        strokeIcon("Download", strokeWidth = 2f) {
            moveTo(12f, 4f)
            verticalLineTo(15f)
            moveTo(7.5f, 10.5f)
            lineTo(12f, 15f)
            lineTo(16.5f, 10.5f)
            moveTo(5f, 19.5f)
            horizontalLineTo(19f)
        }
    }

    val Upload: ImageVector by lazy {
        strokeIcon("Upload", strokeWidth = 2f) {
            moveTo(12f, 15f)
            verticalLineTo(4f)
            moveTo(7.5f, 8.5f)
            lineTo(12f, 4f)
            lineTo(16.5f, 8.5f)
            moveTo(5f, 19.5f)
            horizontalLineTo(19f)
        }
    }

    val Apps: ImageVector by lazy {
        icon("Apps") {
            for (row in listOf(4f, 10f, 16f)) {
                for (col in listOf(4f, 10f, 16f)) {
                    moveTo(col, row)
                    horizontalLineTo(col + 4f)
                    verticalLineTo(row + 4f)
                    horizontalLineTo(col)
                    close()
                }
            }
        }
    }

    val BugReport: ImageVector by lazy {
        strokeIcon("BugReport", strokeWidth = 1.9f) {
            moveTo(8f, 9f)
            horizontalLineTo(16f)
            verticalLineTo(15f)
            curveTo(16f, 17.8f, 14.2f, 20f, 12f, 20f)
            curveTo(9.8f, 20f, 8f, 17.8f, 8f, 15f)
            close()
            moveTo(9.5f, 9f)
            curveTo(9.5f, 6.8f, 10.6f, 5.5f, 12f, 5.5f)
            curveTo(13.4f, 5.5f, 14.5f, 6.8f, 14.5f, 9f)
            moveTo(4.5f, 10f)
            horizontalLineTo(8f)
            moveTo(16f, 10f)
            horizontalLineTo(19.5f)
            moveTo(4.5f, 15f)
            horizontalLineTo(8f)
            moveTo(16f, 15f)
            horizontalLineTo(19.5f)
        }
    }

    val Widgets: ImageVector by lazy {
        strokeIcon("Widgets", strokeWidth = 1.9f) {
            moveTo(4f, 4f)
            horizontalLineTo(10.5f)
            verticalLineTo(10.5f)
            horizontalLineTo(4f)
            close()
            moveTo(4f, 13.5f)
            horizontalLineTo(10.5f)
            verticalLineTo(20f)
            horizontalLineTo(4f)
            close()
            moveTo(13.5f, 13.5f)
            horizontalLineTo(20f)
            verticalLineTo(20f)
            horizontalLineTo(13.5f)
            close()
            moveTo(16.7f, 3.3f)
            lineTo(20.7f, 7.3f)
            lineTo(16.7f, 11.3f)
            lineTo(12.7f, 7.3f)
            close()
        }
    }

    val OpenInNew: ImageVector by lazy {
        strokeIcon("OpenInNew", autoMirror = true, strokeWidth = 2f) {
            moveTo(14f, 4f)
            horizontalLineTo(20f)
            verticalLineTo(10f)
            moveTo(11f, 13f)
            lineTo(20f, 4f)
            moveTo(18f, 14f)
            verticalLineTo(19.5f)
            horizontalLineTo(4.5f)
            verticalLineTo(6f)
            horizontalLineTo(10f)
        }
    }

    val Backspace: ImageVector by lazy {
        strokeIcon("Backspace", autoMirror = true, strokeWidth = 2f) {
            moveTo(8.5f, 6f)
            horizontalLineTo(20.5f)
            verticalLineTo(18f)
            horizontalLineTo(8.5f)
            lineTo(3.5f, 12f)
            close()
            moveTo(12f, 9.5f)
            lineTo(16.5f, 14.5f)
            moveTo(16.5f, 9.5f)
            lineTo(12f, 14.5f)
        }
    }

    val Fingerprint: ImageVector by lazy {
        strokeIcon("Fingerprint", strokeWidth = 1.8f) {
            moveTo(6f, 12f)
            curveTo(6f, 8.7f, 8.7f, 6f, 12f, 6f)
            curveTo(15.3f, 6f, 18f, 8.7f, 18f, 12f)
            verticalLineTo(15.5f)
            moveTo(9f, 12f)
            curveTo(9f, 10.3f, 10.3f, 9f, 12f, 9f)
            curveTo(13.7f, 9f, 15f, 10.3f, 15f, 12f)
            verticalLineTo(17.5f)
            moveTo(12f, 12f)
            verticalLineTo(20f)
            moveTo(4f, 9.5f)
            curveTo(5.2f, 5.7f, 8.3f, 3f, 12f, 3f)
            curveTo(15.7f, 3f, 18.8f, 5.7f, 20f, 9.5f)
        }
    }
}
