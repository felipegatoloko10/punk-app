package com.punk.tools

import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.punk.axmanager.AxShell
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object OcrEngine {
    suspend fun recognizeScreen(): JsonObject {
        val tempPath = "/sdcard/punk_ocr_${System.nanoTime()}.png"
        var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
        var bitmap: android.graphics.Bitmap? = null
        try {
            val captured = AxShell.screencap(tempPath)
            if (!captured.isSuccess) return error(captured.stderr.ifBlank { "Erro ao capturar tela para OCR" })
            val encoded = AxShell.exec("base64 -w 0 ${AxShell.quote(tempPath)}", requirePrivilege = true)
            if (!encoded.isSuccess || encoded.stdout.isBlank()) return error("Falha ao ler captura para OCR")
            val bytes = runCatching { Base64.decode(encoded.stdout.trim(), Base64.DEFAULT) }
                .getOrElse { return error("Captura inválida para OCR") }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return error("Não foi possível decodificar a captura")
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
            return result.toJson()
        } catch (e: Exception) {
            return error(e.message ?: "Falha no reconhecimento de texto")
        } finally {
            recognizer?.close()
            bitmap?.recycle()
            withContext(NonCancellable) {
                AxShell.exec("rm -f ${AxShell.quote(tempPath)}", timeoutMs = 2_000, requirePrivilege = true)
            }
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            addOnCanceledListener { continuation.cancel() }
        }

    private fun Text.toJson(): JsonObject = buildJsonObject {
        put("ok", true)
        put("text", text.take(12_000))
        put("blockCount", textBlocks.size)
        put("blocks", buildJsonArray {
            textBlocks.take(100).forEach { block ->
                add(buildJsonObject {
                    put("text", block.text.take(2_000))
                    put("bounds", block.boundingBox.toJson())
                    put("lines", buildJsonArray {
                        block.lines.take(100).forEach { line ->
                            add(buildJsonObject {
                                put("text", line.text.take(1_000))
                                put("bounds", line.boundingBox.toJson())
                                put("elements", buildJsonArray {
                                    line.elements.take(100).forEach { element ->
                                        add(buildJsonObject {
                                            put("text", element.text.take(500))
                                            put("bounds", element.boundingBox.toJson())
                                        })
                                    }
                                })
                            })
                        }
                    })
                })
            }
        })
    }

    private fun android.graphics.Rect?.toJson(): JsonObject = buildJsonObject {
        val rect = this@toJson
        put("left", rect?.left ?: 0)
        put("top", rect?.top ?: 0)
        put("right", rect?.right ?: 0)
        put("bottom", rect?.bottom ?: 0)
    }

    private fun error(message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", message)
    }
}
