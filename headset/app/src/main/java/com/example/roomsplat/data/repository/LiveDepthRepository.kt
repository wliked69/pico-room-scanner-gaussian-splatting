package com.example.roomsplat.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import com.example.roomsplat.domain.model.LiveDepthFrame
import com.pico.spatial.ml.securemr.BaseHandle
import com.pico.spatial.ml.securemr.SpatialMLInstance
import com.pico.spatial.ml.securemr.SpatialMLSession
import com.pico.spatial.ml.securemr.Tensor
import com.pico.spatial.ml.readback.readbackContentSuspend
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One lifecycle-owned sensor graph. All readback/cleanup is off Main; no RGB is read here. */
class LiveDepthRepository(private val context:Context,private val poses:HeadPoseCache) {
    @Volatile var frame:LiveDepthFrame?=null;private set
    @Volatile var message:String?="正在确认当前房间表面…";private set
    private var job:Job?=null
    @Volatile private var generation=0L
    @Volatile private var minAcquisitionNanos=Long.MIN_VALUE
    fun discardBefore(acquisitionNanos:Long) { minAcquisitionNanos=acquisitionNanos;frame=null }

    fun start(scope:CoroutineScope) {
        if(job?.isActive==true)return
        val token=++generation
        job=scope.launch(Dispatchers.IO) {
            // Also serialize old-controller teardown against a replacement controller.
            sensorOwner.withLock {
                while(isActive && token==generation) {
                    if(!permitted()) {
                        frame=null;message="请允许相机和空间数据权限后重新进入应用。"
                        delay(500);continue
                    }
                    try { runSensor(token) }
                    catch(cancelled:CancellationException) { throw cancelled }
                    catch(error:Exception) {
                        frame=null;message="暂未取得当前表面，请停稳片刻；若持续无网格请重新进入应用。"
                        Log.e(TAG,"DEPTH_UNAVAILABLE",error)
                        delay(2000)
                    }
                }
            }
        }
    }

    fun stop() { generation++;job?.cancel();job=null;frame=null }
    private fun permitted()=listOf(Manifest.permission.CAMERA,SPATIAL_DATA).all {
        context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED
    }
    private fun requireReadbackPermission() {
        // Runtime otherwise aborts in JNI, so permission failures must never reach readback.
        check(permitted()) { "Camera and spatial data permissions required before sensor readback" }
    }

    private suspend fun awaitTrackedView(token:Long) {
        while(true) {
            currentCoroutineContext().ensureActive()
            if(token!=generation)throw CancellationException("Depth view retired")
            val now=SystemClock.elapsedRealtimeNanos()
            if(poses.stableSince(now-150_000_000L,now)!=null)return
            message="请戴上头显并停稳片刻，正在等待空间追踪…"
            delay(100)
        }
    }

    private suspend fun runSensor(token:Long) {
        val handles=mutableListOf<BaseHandle>()
        try {
            // Instance readiness only means Binder is bound, not that the device camera can
            // configure while asleep. That failure aborts in this OS's JNI session constructor.
            // Do not open a hardware session until HMD tracking has produced a complete window.
            awaitTrackedView(token)
            val instance=SpatialMLInstance.create(context)
            withTimeout(15_000) { while(!instance.ready)delay(100) }
            awaitTrackedView(token)
            requireReadbackPermission()
            val session=checkNotNull(instance.createSession(SpatialMLSession.InitInfo(160,128,0,0,0,SpatialMLSession.ContainerType.DISABLED)))
            handles.add(session)
            val depth=session.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.FLOAT32,intArrayOf(128,160)))
            handles.add(depth)
            val done=session.newGlobalTensor(Tensor.FloatArrayInitInfo(1));handles.add(done)
            val pipeline=session.newPipeline();handles.add(pipeline)
            val depthOut=pipeline.newPlaceholderLike(depth);val doneOut=pipeline.newPlaceholderLike(done)
            pipeline.getDepthMap(depthOut)
            pipeline.copy(pipeline.newLocalTensor(floatArrayOf(1f)),doneOut)
            var count=0;var accepted=0;var lastLog=0L
            while(currentCoroutineContext().isActive && token==generation) {
                requireReadbackPermission()
                SharedMemory.create("roomsplat_depth_done",4).use { memory ->
                    val buffer=memory.mapReadWrite().order(ByteOrder.nativeOrder());buffer.putFloat(0f)
                    SharedMemory.unmap(buffer);done.tensorResource=memory
                }
                val began=SystemClock.elapsedRealtimeNanos()
                pipeline.submit(mapOf(depthOut to depth,doneOut to done),null,null)
                withTimeout(1500) {
                    while(true) {
                        requireReadbackPermission()
                        val completed=done.readbackContentSuspend().use { it.buffer.order(ByteOrder.nativeOrder()).getFloat(0)>=.5f }
                        if(completed)break
                        delay(10)
                    }
                }
                requireReadbackPermission()
                val values=depth.readbackContentSuspend().use {
                    val buffer=it.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                    check(buffer.remaining()==128*160) { "Unexpected measured depth shape" }
                    FloatArray(128*160).also(buffer::get)
                }
                val now=SystemClock.elapsedRealtimeNanos()
                // Public depth has no source timestamp: allow only a stable window incl. sensor lag.
                val pose=poses.stableSince(minOf(began-100_000_000L,now-150_000_000L),now)
                currentCoroutineContext().ensureActive()
                if(token!=generation)return
                val valid=values.any { it.isFinite() && it in .25f..8f }
                frame=if(pose!=null && valid && began>=minAcquisitionNanos)
                    LiveDepthFrame(pose,now,160,128,values,acquisitionStartedNanos=began) else null
                message=if(frame!=null)null else "请停稳片刻，确认当前表面后展开网格。"
                count++;if(frame!=null)accepted++
                if(now-lastLog>=2_000_000_000L) {
                    Log.i(TAG,"LIVE_DEPTH samples=$count stable=$accepted usable=${frame!=null} read_ms=${(now-began)/1_000_000} permissions=granted")
                    lastLog=now
                }
                delay(80)
            }
        } finally {
            withContext(NonCancellable+Dispatchers.IO) {
                handles.asReversed().forEach { handle ->
                    runCatching { handle.destructor.let { it.clear();it.destroy() } }
                        .onFailure { Log.w(TAG,"DEPTH_CLEANUP",it) }
                }
                Log.i(TAG,"DEPTH_SESSION_CLOSED handles=${handles.size}")
            }
        }
    }

    companion object {
        const val SPATIAL_DATA="com.picovr.permission.SPATIAL_DATA"
        private const val TAG="RoomSplatDepth"
        private val sensorOwner=Mutex()
    }
}
