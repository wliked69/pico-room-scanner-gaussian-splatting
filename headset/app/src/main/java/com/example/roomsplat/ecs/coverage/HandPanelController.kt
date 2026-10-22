
package com.example.roomsplat.ecs.coverage

import android.os.SystemClock
import android.util.Log
import com.example.roomsplat.data.repository.HeadPoseCache
import com.example.roomsplat.domain.model.Point3
import com.example.roomsplat.domain.usecase.FacePanelAtHeadUseCase
import com.example.roomsplat.domain.usecase.SelectPanelAnchorUseCase
import com.example.roomsplat.domain.usecase.ActiveSceneOwnerUseCase
import com.example.roomsplat.domain.usecase.SelectPanelAnchorUseCase.Source
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.AnchorEntity
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Tracking drives only the control panel. The room mesh retains its world owner. */
class HandPanelController(private val headCache: HeadPoseCache) {
    private val owner = Entity().apply { setName("capture_controls_owner") }
    private val fallback = AnchorEntity(AnchorTarget.createCameraTarget()).apply {
        setName("capture_controls_camera_fallback")
        components[AnchorComponent::class.java]?.positionOffset = Vector3(-.65f, -.28f, -1.4f)
    }
    private val hands = HandTrackingProvider()
    private val controllers = ControllerTrackingProvider()
    private val selector = SelectPanelAnchorUseCase()
    private val facing = FacePanelAtHeadUseCase()
    private val fallbackOffset=Point3(-.65f,-.28f,-1.4f)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow("面板在视野左下方 · 抬起左手")
    val status = mutable.asStateFlow()
    private var hand: SelectPanelAnchorUseCase.Sample? = null
    private var controller: SelectPanelAnchorUseCase.Sample? = null
    private var panel: Entity? = null
    private var jobs = listOf<Job>()
    private var active = false
    private var disposed = false
    private var followHand = true
    private var source = Source.HEAD
    private var filteredWorldPosition: Vector3? = null
    private var lastTick = 0L
    private var lastEvidence = 0L

    init { owner.addChild(fallback) }

    fun attach(content: SpatialViewContent, attachment: Entity) {
        if(disposed)return
        activePanel.claim(this) { previous -> previous.close() }
        content.addEntity(owner)
        panel = attachment
        fallback.addChild(attachment)
        applyFallback()
        start()
    }

    fun setFollowHand(value: Boolean) { followHand = value; if (!value) applyFallback() }

    fun start() {
        if (active || disposed || panel == null) return
        active = true
        fun sample(position: Vector3) = SelectPanelAnchorUseCase.Sample(
            Point3(position.x, position.y, position.z), SystemClock.elapsedRealtimeNanos())
        jobs = listOf(
            scope.launch {
                try {
                    hands.dataFlow.collect { data ->
                        hand = data.left?.handJoints?.firstOrNull { it.index == HandJoint.Index.WRIST }?.let { sample(it.position) }
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { hand = null; Log.w(TAG, "HAND_FLOW_FAILED", e) }
            },
            scope.launch {
                try { controllers.dataFlow.collect { data -> controller = data.left?.let { sample(it.position) } } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { controller = null; Log.w(TAG, "CONTROLLER_FLOW_FAILED", e) }
            },
            scope.launch {
                while (isActive) {
                    try { update() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        Log.w(TAG, "PANEL_UPDATE_FAILED", e); applyFallback()
                    }
                    delay(16)
                }
            },
        )
        try { Log.i(TAG, "HAND_START support=${hands.supportState} result=${hands.start()}") }
        catch (e: Exception) { Log.w(TAG, "HAND_START_FAILED", e) }
        try { Log.i(TAG, "CONTROLLER_START support=${controllers.supportState} result=${controllers.start()}") }
        catch (e: Exception) { Log.w(TAG, "CONTROLLER_START_FAILED", e) }
    }

    private fun update() {
        val attachment = panel ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val head = headCache.nearest(now)
        val next = selector.select(now, head?.position, hand, controller, followHand)
        if (next == Source.HEAD || head == null) { applyFallback(); return }
        val sample = (if (next == Source.LEFT_HAND) hand else controller) ?: return
        if (source != next) {
            owner.addChild(attachment)
            filteredWorldPosition = null; lastTick = 0
            source = next
            mutable.value = if (next == Source.LEFT_HAND) "面板跟随左手腕" else "面板跟随左手柄"
            Log.i(TAG, "PANEL_ANCHOR source=$source")
        }
        // Wrist coordinates are world-space; the panel remains readable instead of rolling with the wrist.
        val world = Vector3(sample.position.x, sample.position.y + .20f, sample.position.z)
        val dt = if (lastTick == 0L) .016f else ((now-lastTick)/1_000_000_000f).coerceIn(.001f, .2f)
        val alpha = (1.0 - kotlin.math.exp((-dt/.055f).toDouble())).toFloat()
        val old = filteredWorldPosition
        val displayedWorld = if (old == null) world else old + (world-old)*alpha
        val face=facing.rotation(Point3(displayedWorld.x,displayedWorld.y,displayedWorld.z),head.position)
            ?:run { applyFallback();return }
        filteredWorldPosition=displayedWorld;lastTick=now
        attachment.components[TransformComponent::class.java]?.apply {
            position=owner.convertPositionFrom(displayedWorld,null)
            quaternion=owner.convertRotationFrom(Quat(face.x,face.y,face.z,face.w),null)
            scaleVector=Vector3(.70f)
        }
        if (now - lastEvidence >= 3_000_000_000L) {
            lastEvidence = now
            val bounds = attachment.getVisualBounds(owner, recursive = true, enabledOnly = true)
            Log.i(TAG, "PANEL_POSE source=$source world=$displayedWorld target_world=$world bounds=$bounds sample_age_ms=${(now-sample.receivedNanos)/1_000_000} face_target=HMD_POSITION")
        }
    }

    private fun applyFallback() {
        val attachment = panel ?: return
        if (source != Source.HEAD) {
            fallback.addChild(attachment)
            source = Source.HEAD
            Log.i(TAG, "PANEL_ANCHOR source=HEAD")
        }
        attachment.components[TransformComponent::class.java]?.apply {
            position = Vector3.ZERO
            val face=facing.rotation(fallbackOffset,Point3(0f,0f,0f))!!
            quaternion = Quat(face.x,face.y,face.z,face.w)
            scaleVector = Vector3(1f)
        }
        filteredWorldPosition = null
        mutable.value = if (followHand) "面板在视野左下方 · 抬起左手" else "面板在视野左下方"
    }

    fun pause() {
        if (!active) return
        active = false
        jobs.forEach { it.cancel() }; jobs = emptyList()
        runCatching { hands.stop() }; runCatching { controllers.stop() }
        hand = null; controller = null
        applyFallback()
    }

    fun close() {
        if (disposed) return
        activePanel.release(this)
        pause(); disposed = true
        scope.cancel()
        owner.destroy()
        panel = null
    }

    companion object {
        private const val TAG = "RoomSplatPanel"
        private val activePanel=ActiveSceneOwnerUseCase<HandPanelController>()
    }
}
