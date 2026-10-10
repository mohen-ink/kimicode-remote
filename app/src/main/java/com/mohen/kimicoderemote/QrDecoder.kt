package com.mohen.kimicoderemote

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object QrDecoder {

    /** 返回 Pair(识别结果, 错误信息)；成功时错误为 null，失败时结果为 null。 */
    suspend fun decodeFromUri(context: Context, uri: Uri): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            val image = try {
                InputImage.fromFilePath(context, uri)
            } catch (e: Exception) {
                return@withContext null to "读取图片失败: ${e.message}"
            }
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
            )
            return@withContext try {
                val result = scanner.process(image).await()
                    .firstOrNull { it.rawValue != null }?.rawValue
                result to (result ?: "未在图片中识别到二维码")
            } catch (e: Exception) {
                null to "识别失败: ${e.message}"
            } finally {
                scanner.close()
            }
        }
}
