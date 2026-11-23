package com.example.configmanager

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Pure-JVM/Android ZXing QR Code encoder and decoder supporting:
 * - Live CameraX YUV ImageProxy decoding
 * - Zero-permission Photo Picker Bitmap QR decoding
 * - Generating crisp QR Code bitmaps for sharing VPN configs
 */
object QrCodeHelper {

    private val decodeHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true
    )

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        if (content.isBlank()) return null
        return try {
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun decodeFromBitmap(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply { setHints(decodeHints) }
            reader.decode(binary).text
        } catch (_: Throwable) {
            null
        }
    }

    fun decodeFromImageProxy(imageProxy: ImageProxy): String? {
        return try {
            val plane = imageProxy.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                imageProxy.height,
                0,
                0,
                imageProxy.width,
                imageProxy.height,
                false
            )
            val binary = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply { setHints(decodeHints) }
            reader.decode(binary).text
        } catch (_: Throwable) {
            null
        }
    }
}
