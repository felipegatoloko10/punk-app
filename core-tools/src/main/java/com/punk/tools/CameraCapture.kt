package com.punk.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import kotlin.coroutines.resume

object CameraCapture {
    private val activeCaptures = java.util.concurrent.ConcurrentHashMap.newKeySet<() -> Unit>()
    fun cancelAll() { activeCaptures.toList().forEach { it.invoke() } }

    @SuppressLint("MissingPermission")
    suspend fun capturePhoto(ctx: Context, facing: String = "back"): Map<String, Any> = withContext(Dispatchers.IO) {
        ToolRuntime.captureError(ctx, android.Manifest.permission.CAMERA)?.let {
            return@withContext mapOf("ok" to false, "error" to it)
        }
        val result = mutableMapOf<String, Any>()
        val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            result["ok"] = false
            result["error"] = "CameraManager indisponível"
            return@withContext result
        }

        val targetFacing = if (facing.equals("front", true)) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        var selectedCameraId: String? = null
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val f = chars.get(CameraCharacteristics.LENS_FACING)
                if (f == targetFacing) {
                    selectedCameraId = id
                    break
                }
            }
        } catch (e: Exception) {
            result["ok"] = false
            result["error"] = "Erro ao listar câmeras: ${e.message}"
            return@withContext result
        }

        if (selectedCameraId == null) {
            result["ok"] = false
            result["error"] = "Câmera solicitada ($facing) não encontrada"
            return@withContext result
        }

        val res = withTimeoutOrNull(8000L) {
            takePictureInternal(cameraManager, selectedCameraId)
        }

        if (res != null) {
            result.putAll(res)
        } else {
            result["ok"] = false
            result["error"] = "Timeout de 8s ao capturar foto da câmera"
        }

        result
    }

    @SuppressLint("MissingPermission")
    private suspend fun takePictureInternal(
        cm: CameraManager,
        cameraId: String
    ): Map<String, Any> = suspendCancellableCoroutine { cont ->
        val sizes = cm.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)
        val size = sizes?.filter { it.width <= 1920 && it.height <= 1920 }?.maxByOrNull { it.width * it.height }
            ?: sizes?.minByOrNull { it.width.toLong() * it.height }
        if (size == null) {
            cont.resume(mapOf("ok" to false, "error" to "Câmera não oferece captura JPEG compatível"))
            return@suspendCancellableCoroutine
        }
        val thread = HandlerThread("PunkCameraCapture")
        thread.start()
        val handler = Handler(thread.looper)

        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var opening = true
        var cancelCapture: (() -> Unit)? = null

        fun cleanup() {
            cancelCapture?.let { activeCaptures.remove(it) }
            try { captureSession?.close() } catch (_: Exception) {}
            try { cameraDevice?.close() } catch (_: Exception) {}
            try { imageReader.close() } catch (_: Exception) {}
            // Keep the callback thread alive until a pending open can be closed in onOpened.
            if (!opening) thread.quitSafely()
        }
        cancelCapture = { cont.cancel(kotlinx.coroutines.CancellationException("Captura interrompida")); cleanup() }
        cancelCapture?.let { activeCaptures.add(it) }

        imageReader.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val buffer: ByteBuffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    if (cont.isActive) {
                        cont.resume(mapOf(
                            "ok" to true,
                            "mime" to "image/jpeg",
                            "cameraId" to cameraId,
                            "sizeBytes" to bytes.size,
                            "base64" to b64
                        ))
                    }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(mapOf("ok" to false, "error" to "Erro na leitura do buffer: ${e.message}"))
            } finally {
                image?.close()
                cleanup()
            }
        }, handler)

        try {
            if (!cont.isActive) { opening = false; cleanup(); return@suspendCancellableCoroutine }
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    if (!cont.isActive) { camera.close(); cleanup(); return }
                    cameraDevice = camera
                    try {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(imageReader.surface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        }
                                        session.capture(req.build(), null, handler)
                                    } catch (e: Exception) {
                                        if (cont.isActive) cont.resume(mapOf("ok" to false, "error" to "Erro ao disparar captura: ${e.message}"))
                                        cleanup()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    if (cont.isActive) cont.resume(mapOf("ok" to false, "error" to "Falha ao configurar sessão da câmera"))
                                    cleanup()
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(mapOf("ok" to false, "error" to "Erro na sessão da câmera: ${e.message}"))
                        cleanup()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    opening = false
                    camera.close()
                    if (cont.isActive) cont.resume(mapOf("ok" to false, "error" to "Câmera desconectada"))
                    cleanup()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false
                    camera.close()
                    if (cont.isActive) cont.resume(mapOf("ok" to false, "error" to "Erro no hardware da câmera: código $error"))
                    cleanup()
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            if (cont.isActive) cont.resume(mapOf("ok" to false, "error" to "Falha ao abrir câmera: ${e.message}"))
            cleanup()
        }

        cont.invokeOnCancellation { cleanup() }
    }
}
