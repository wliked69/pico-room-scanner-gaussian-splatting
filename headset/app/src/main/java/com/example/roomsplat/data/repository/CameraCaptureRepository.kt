package com.example.roomsplat.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.example.roomsplat.domain.usecase.ResetSessionFilesUseCase
import com.example.roomsplat.domain.usecase.FindRecoverableSessionUseCase
import com.example.roomsplat.domain.usecase.ScanSessionStateUseCase
import com.example.roomsplat.domain.usecase.CaptureWriteGate
import com.example.roomsplat.domain.usecase.CopyYuv420UseCase
import com.example.roomsplat.domain.usecase.SelectWritableExportDirectoryUseCase
import com.example.roomsplat.domain.model.CapturePhase
import com.example.roomsplat.domain.model.CaptureSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.example.roomsplat.domain.model.SavedFrame
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CameraCaptureRepository(context: Context, private val headPoseCache: HeadPoseCache = HeadPoseCache()) : CaptureRepository {
    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)
    private val storage = File(context.getExternalFilesDir(null) ?: context.filesDir, "RoomSplat").apply { mkdirs() }
    private val sessionMarker = File(context.filesDir,"RoomSplatState/active-session.txt")
    private val sessionState = ScanSessionStateUseCase(sessionMarker)
    private val thread = HandlerThread("RoomSplatCapture").apply { start() }
    private val handler = Handler(thread.looper)
    private val mutable = MutableStateFlow(CaptureSnapshot())
    override val snapshot = mutable.asStateFlow()
    private val frameEvents = MutableSharedFlow<SavedFrame>(extraBufferCapacity = 16)
    override val savedFrames = frameEvents.asSharedFlow()
    private var device: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var directory: File? = null
    private var camera: String? = null
    private var size = Size(1920, 1080)
    private var cameraMetadata = JSONObject()
    private var segmentStart = 0L
    private var accumulatedMillis = 0L
    private var lastSaveNanos = 0L
    private var serial = 0
    private var scanRevision = 0L
    private val segments = mutableListOf<Int>()
    private var recording = false
    private val writeGate = CaptureWriteGate()
    private val yuvCopy = CopyYuv420UseCase()
    private var nv21Scratch = ByteArray(0)
    private val jpegScratch = ByteArrayOutputStream(512*1024)
    @Volatile private var closed = false

    override suspend fun check(permissionGranted: Boolean): Unit = onThread {
        if (recording || mutable.value.phase in setOf(CapturePhase.PACKING, CapturePhase.PAUSED, CapturePhase.CAPTURED)) return@onThread
        if (!permissionGranted) {
            mutable.update { it.copy(phase = CapturePhase.UNAVAILABLE, message = "需要相机权限才能采集房间。") }
            return@onThread
        }
        try {
            val candidates = manager.cameraIdList.filter { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(ImageFormat.YUV_420_888)?.isNotEmpty() == true
            }
            camera = candidates.firstOrNull() ?: error("未找到可访问的外向 YUV 相机。")
            val characteristics = manager.getCameraCharacteristics(camera!!)
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(ImageFormat.YUV_420_888)
            size = sizes.filter { it.width <= 1920 && it.height <= 1440 }
                .maxByOrNull { it.width * it.height } ?: sizes.minBy { it.width * it.height }
            cameraMetadata = JSONObject().apply {
                put("id", camera)
                put("api", "android.camera2")
                put("width", size.width)
                put("height", size.height)
                put("sensor_orientation_degrees", characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION))
                put("pixel_rotation_applied_degrees", 0)
                put("timestamp_source", characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
                put("hardware_level", characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                put("intrinsics", characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                put("lens_distortion", characteristics.get(CameraCharacteristics.LENS_DISTORTION)?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                put("calibration_use", "metadata_only; Windows estimates intrinsics and poses using SfM")
            }
            recover()
            if (directory == null) {
                mutable.update { it.copy(phase = CapturePhase.READY, cameraId = camera!!,
                    message = "相机 ${camera!!} · ${size.width}×${size.height}；缓慢平移并保持画面重叠。") }
            }
            Log.i(TAG, "CAMERA_READY id=$camera size=$size; image source requires physical validation")
        } catch (error: Exception) {
            mutable.update { it.copy(phase = CapturePhase.UNAVAILABLE, message = error.message ?: "无法检查相机。") }
        }
    }

    private fun recover() {
        if (directory != null) return
        val previous = FindRecoverableSessionUseCase().find(storage,
            sessionState.currentMarker(File(storage,"active-session.txt"))) ?: return
        val frames = readFrames(previous)
        if (frames.length() == 0) return
        writeActiveSession(previous.name)
        directory = previous
        serial = frames.length()
        segments.clear()
        segments.add(serial)
        mutable.value = CaptureSnapshot(phase = CapturePhase.PAUSED, frames = serial,
            scanRevision = scanRevision,
            segments = segments.toList(), cameraId = camera.orEmpty(), sessionPath = previous.absolutePath,
            message = "恢复了上次采集的 $serial 张照片，保持暂停。可以继续采集，或完成后打包。")
        Log.i(TAG, "RECOVERED frames=$serial")
    }

    override suspend fun start() {
        onThread {
            check(directory == null) { "已有会话，请先打包。" }
            check(camera != null) { "先检查相机权限。" }
            check(storage.usableSpace > 512L * 1024 * 1024) { "可用空间不足 512MB。" }
            val id = "session-${Instant.now().toString().replace(':', '-')}-${UUID.randomUUID().toString().take(8)}"
            directory = File(storage, "sessions/$id").apply { check(mkdirs()) { "无法建立采集目录。" } }
            File(directory, "images").mkdirs()
            File(directory, "camera.json").writeText(cameraMetadata.toString(2))
            writeActiveSession(directory!!.name)
            serial = 0
            accumulatedMillis = 0
            segments.clear()
            mutable.value = CaptureSnapshot(phase = CapturePhase.PAUSED, cameraId = camera!!, sessionPath = directory!!.absolutePath,
                scanRevision = scanRevision)
        }
        resume()
    }

    @SuppressLint("MissingPermission")
    override suspend fun resume() {
        val token = writeGate.begin()
        onThread {
            check(directory != null) { "没有可继续的会话。" }
            check(mutable.value.phase in setOf(CapturePhase.PAUSED, CapturePhase.ERROR)) { "当前状态不能继续采集。" }
        }
        try {
            val opened = suspendCancellableCoroutine<CameraDevice> { continuation ->
                manager.openCamera(camera!!, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (continuation.isActive) continuation.resume(camera) else camera.close()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("相机断开。"))
                        else fail("相机断开，已保存的照片保留。")
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("相机错误 $error。"))
                        else fail("相机错误 $error，已保存的照片保留。")
                    }
                }, handler)
            }
            onThread {
                device = opened
                reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
                reader!!.setOnImageAvailableListener({ source ->
                    val frame = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (recording && writeGate.allows(token) && now - lastSaveNanos >= 250_000_000L) {
                            save(frame, now, token)
                            lastSaveNanos = now
                        }
                    } catch (error: Exception) {
                        fail(error.message ?: "照片写入失败。")
                    } finally { frame.close() }
                }, handler)
            }
            val configured = suspendCancellableCoroutine<CameraCaptureSession> { continuation ->
                opened.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (continuation.isActive) continuation.resume(session) else session.close()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("相机不支持单路 YUV 采集。"))
                    }
                }, handler)
            }
            onThread {
                if (!writeGate.allows(token)) {
                    configured.close()
                    stopCamera()
                    mutable.update { it.copy(phase = CapturePhase.PAUSED, message = "已暂停，照片保留。") }
                    return@onThread
                }
                cameraSession = configured
                val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader!!.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }.build()
                segmentStart = SystemClock.elapsedRealtime()
                lastSaveNanos = 0L
                segments.add(0)
                recording = true
                mutable.update { it.copy(phase = CapturePhase.RECORDING, message = "缓慢平移，拍摄物体多个角度；保持相邻画面重叠。") }
                configured.setRepeatingRequest(request, null, handler)
                val countAtStart = serial
                handler.postDelayed({
                    if (recording && writeGate.allows(token) && serial == countAtStart) fail("相机打开但 10 秒内没有图像。该设备可能限制相机输出。")
                }, 10_000)
            }
        } catch (error: Exception) {
            handler.post { fail(error.message ?: "无法开始采集。") }
            throw error
        }
    }

    private fun save(image: Image, receivedNanos: Long, token: Long) {
        val root = directory ?: return
        check(root.usableSpace > 256L * 1024 * 1024) { "存储余量不足，采集已停止，照片保留。" }
        val crop = image.cropRect
        check(crop.width() % 2 == 0 && crop.height() % 2 == 0) { "相机输出尺寸不是偶数。" }
        val byteCount = crop.width() * crop.height() * 3 / 2
        if (nv21Scratch.size != byteCount) nv21Scratch = ByteArray(byteCount)
        val nv21 = nv21Scratch
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        yuvCopy.copy(crop.width(),crop.height(),crop.left,crop.top,
            CopyYuv420UseCase.Plane(y.buffer,y.rowStride,y.pixelStride),
            CopyYuv420UseCase.Plane(u.buffer,u.rowStride,u.pixelStride),
            CopyYuv420UseCase.Plane(v.buffer,v.rowStride,v.pixelStride),nv21)
        jpegScratch.reset()
        check(YuvImage(nv21, ImageFormat.NV21, crop.width(), crop.height(), null)
            .compressToJpeg(Rect(0, 0, crop.width(), crop.height()), 93, jpegScratch)) { "无法编码照片。" }
        val bytes = jpegScratch.toByteArray()
        var lumaSum = 0L
        var detailSum = 0L
        var qualitySamples = 0
        for (row in 1 until crop.height() step 16) for (col in 1 until crop.width() step 16) {
            val index = row * crop.width() + col
            val value = nv21[index].toInt() and 255
            val dx = value - (nv21[index - 1].toInt() and 255)
            val dy = value - (nv21[index - crop.width()].toInt() and 255)
            lumaSum += value
            detailSum += dx * dx + dy * dy
            qualitySamples++
        }
        val meanLuma = lumaSum.toFloat() / qualitySamples
        val detailVariance = detailSum.toFloat() / qualitySamples
        val pose = headPoseCache.nearest(receivedNanos)
        val filename = "images/frame_${(serial + 1).toString().padStart(6, '0')}.jpg"
        if (!writeGate.allows(token)) return
        val target = File(root, filename)
        val temporary = File(root, "$filename.partial")
        temporary.outputStream().use { it.write(bytes); it.flush() }
        check(temporary.renameTo(target)) { "无法保存照片。" }
        val frame = JSONObject().apply {
            put("file", filename)
            put("sha256", sha256(bytes))
            put("width", crop.width())
            put("height", crop.height())
            put("sensor_timestamp_ns", image.timestamp.toString())
            put("received_elapsed_realtime_ns", receivedNanos.toString())
            put("segment", segments.size - 1)
            put("mean_luma", meanLuma)
            put("detail_variance", detailVariance)
            put("hmd_pose", pose?.let {
                JSONObject().put("received_elapsed_realtime_ns", it.receivedNanos.toString())
                    .put("position", JSONArray(listOf(it.position.x, it.position.y, it.position.z)))
                    .put("quaternion_xyzw", JSONArray(it.quaternion))
                    .put("rgb_alignment_verified", false)
            } ?: JSONObject.NULL)
        }
        File(root, "frames.jsonl").appendText(frame.toString() + "\n")
        serial++
        frameEvents.tryEmit(SavedFrame(root.absolutePath, filename, receivedNanos, meanLuma, detailVariance, pose))
        segments[segments.lastIndex]++
        mutable.update { it.copy(frames = serial, activeMillis = accumulatedMillis + SystemClock.elapsedRealtime() - segmentStart,
            segments = segments.toList()) }
        if (serial == 1 || serial % 20 == 0) Log.i(TAG, "FRAME_SAVED count=$serial bytes=${bytes.size} path=${target.absolutePath}")
    }

    override fun requestPause() {
        writeGate.pause()
        mutable.update { if (it.phase == CapturePhase.RECORDING) it.copy(phase = CapturePhase.PAUSED,
            message = "已停止拍照，正在释放相机。") else it }
        Log.i(TAG, "PAUSE_REQUESTED frames=${mutable.value.frames}")
    }

    override suspend fun pause() {
        requestPause()
        onThread {
            if (recording) stopCamera()
            if (mutable.value.phase == CapturePhase.PAUSED) mutable.update {
                it.copy(activeMillis = accumulatedMillis, message = "已暂停，照片保留。")
            }
            Log.i(TAG, "PAUSE_COMPLETE frames=${mutable.value.frames}")
        }
    }

    override suspend fun finish(): Unit = onThread {
        stopCamera()
        check(serial >= 3) { "至少采集 3 张照片再结束。" }
        mutable.update { it.copy(phase = CapturePhase.CAPTURED, activeMillis = accumulatedMillis, message = "采集已结束，可以在头显打包。") }
    }

    override suspend fun pack(): Unit = withContext(Dispatchers.IO) {
        val root = onThread {
            check(!recording) { "请先结束采集。" }
            check(directory != null && serial >= 3) { "没有足够的照片。" }
            mutable.update { it.copy(phase = CapturePhase.PACKING, message = "正在打包原始照片与清单…") }
            directory!!
        }
        try {
            val frames = readFrames(root)
            check(frames.length() >= 3) { "采集数据不完整。" }
            val metadata = JSONObject(File(root, "camera.json").readText())
            val manifest = JSONObject().apply {
                put("schema", "room-splat.capture/1")
                put("session_id", root.name)
                put("created_at_utc", Instant.now().toString())
                put("device_model", Build.MODEL)
                put("android_release", Build.VERSION.RELEASE)
                put("camera", metadata)
                put("pose_source", "optional HMD at image arrival; RGB extrinsics and sensor time alignment unverified; reconstruct_with_sfm")
                put("metric_scale_available", false)
                put("frames", frames)
            }
            File(root, "manifest.json").writeText(manifest.toString(2))
            val exports = SelectWritableExportDirectoryUseCase().select(storage)
            Log.i(TAG,"EXPORT_DIRECTORY path=${exports.absolutePath} writable_probe=true")
            val final = File(exports, "${root.name}.zip")
            val partial = File(exports, "${root.name}.zip.partial-${UUID.randomUUID()}")
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                val spatialFiles = listOf("coverage.json").filter { File(root, it).exists() }
                val names = listOf("manifest.json", "camera.json", "frames.jsonl") + spatialFiles +
                    (0 until frames.length()).map { frames.getJSONObject(it).getString("file") }
                names.forEach { name ->
                    val file = File(root, name)
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            ZipFile(partial).use { check(it.size() >= frames.length() + 3) { "打包文件数量不完整。" } }
            check(partial.renameTo(final)) { "无法完成数据包保存。" }
            File(root, "exported.txt").writeText(final.absolutePath)
            onThread { mutable.update { it.copy(phase = CapturePhase.EXPORTED, archivePath = final.absolutePath, message = "数据包已保存，可通过 USB 导出。") } }
            Log.i(TAG, "ARCHIVE_READY frames=${frames.length()} path=${final.absolutePath}")
        } catch (error: Exception) {
            onThread { mutable.update { it.copy(phase = CapturePhase.ERROR, message = "打包失败：${error.message}。原始照片保留。") } }
            throw error
        }
    }

    private fun readFrames(root: File): JSONArray {
        val result = JSONArray()
        val log = File(root, "frames.jsonl")
        if (log.exists()) log.forEachLine { line ->
            val frame = runCatching { JSONObject(line) }.getOrNull()
            if (frame != null) {
                val file = File(root, frame.getString("file"))
                check(file.exists() && sha256(file.readBytes()) == frame.getString("sha256")) { "照片缺失或校验失败：${file.name}" }
                result.put(frame)
            }
        }
        return result
    }

    override suspend fun newSession(): Unit = onThread {
        check(mutable.value.phase == CapturePhase.EXPORTED) { "先导出当前会话。" }
        writeActiveSession("")
        directory = null
        serial = 0
        scanRevision++
        mutable.value = CaptureSnapshot(phase = CapturePhase.READY, cameraId = camera.orEmpty(),scanRevision=scanRevision)
    }

    override suspend fun resetCurrentSession() {
        val detached = onThread {
            stopCamera()
            mutable.update { it.copy(phase = CapturePhase.PAUSED) }
            val old = directory
            val marker=sessionMarker
            val previousMarker=marker.takeIf { it.exists() }?.readText()
            writeActiveSession("")
            val removed = try { old?.let { ResetSessionFilesUseCase().detach(storage,it) } }
            catch (error: Exception) {
                runCatching { if (previousMarker == null) marker.delete() else writeActiveSession(previousMarker) }
                throw error
            }
            directory = null
            serial = 0
            accumulatedMillis = 0
            segmentStart = 0
            lastSaveNanos = 0
            segments.clear()
            scanRevision++
            mutable.value = CaptureSnapshot(phase = if (camera != null) CapturePhase.READY else CapturePhase.UNAVAILABLE,
                scanRevision=scanRevision,
                cameraId = camera.orEmpty(), message = "已重置当前扫描。已打包的 ZIP 保留。")
            Log.i(TAG,"SCAN_RESET current_photos_cleared=true exports_preserved=true")
            removed
        }
        detached?.let {
            withContext(Dispatchers.IO) {
                try { ResetSessionFilesUseCase().purge(storage,it) }
                catch (error: Exception) { Log.w(TAG,"RESET_PURGE_DEFERRED",error) }
            }
        }
    }

    private fun writeActiveSession(name: String) {
        sessionState.write(name)
    }

    private fun fail(message: String) {
        stopCamera()
        mutable.update { it.copy(phase = CapturePhase.ERROR, activeMillis = accumulatedMillis, message = message) }
        Log.e(TAG, "CAPTURE_ERROR $message")
    }

    private fun stopCamera() {
        writeGate.pause()
        if (recording) accumulatedMillis += SystemClock.elapsedRealtime() - segmentStart
        recording = false
        runCatching { cameraSession?.stopRepeating() }
        cameraSession?.close()
        cameraSession = null
        device?.close()
        device = null
        reader?.close()
        reader = null
    }

    private suspend fun <T> onThread(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        if (closed) {
            continuation.resumeWithException(IllegalStateException("采集会话已关闭。"))
        } else handler.post {
            if (continuation.isActive) try { continuation.resume(block()) }
            catch (error: Exception) { continuation.resumeWithException(error) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.post { stopCamera(); thread.quitSafely() }
    }

    companion object {
        private const val TAG = "RoomSplat"
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

