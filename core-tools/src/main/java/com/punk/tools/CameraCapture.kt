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

    @SuppressLint("MissingPermission")
    suspend fun capturePhoto(ctx: Context, facing: String = "back"): Map<String, Any> = withContext(Dispatchers.IO) {
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
        val thread = HandlerThread("PunkCameraCapture")
        thread.start()
        val handler = Handler(thread.looper)

        val imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)
        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null

        fun cleanup() {
            try { captureSession?.close() } catch (_: Exception) {}
            try { cameraDevice?.close() } catch (_: Exception) {}
            try { imageReader.close() } catch (_: Exception) {}
            thread.quitSafely()
        }

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
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
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
                    if (cont.isActive) cont.resume(mapOf("ok" to false, "error" to "Câmera desconectada"))
                    cleanup()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (cont.isActive) cont.resume(mapOf("ok" to false, "error" to "Erro no hardware da câmera: código $error"))
                    cleanup()
                }
            }, handler)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(mapOf("ok" to false, "error" to "Falha ao abrir câmera: ${e.message}"))
            cleanup()
        }

        cont.invokeOnCancellation { cleanup() }
    }
}
