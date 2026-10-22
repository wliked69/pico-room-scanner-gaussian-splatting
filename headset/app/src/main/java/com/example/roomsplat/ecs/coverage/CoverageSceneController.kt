package com.example.roomsplat.ecs.coverage

import android.os.SystemClock
import android.os.Trace
import android.content.Context
import android.util.Log
import com.example.roomsplat.data.repository.HeadPoseCache
import com.example.roomsplat.data.repository.LiveDepthRepository
import com.example.roomsplat.domain.usecase.LiveDepthAdmissionUseCase
import com.example.roomsplat.domain.usecase.LiveDepthVisibilityUseCase
import com.example.roomsplat.domain.usecase.LiveDepthQueryCacheUseCase
import com.example.roomsplat.domain.usecase.VerifiedCoverageProgressUseCase
import com.example.roomsplat.domain.model.CaptureSnapshot
import com.example.roomsplat.domain.model.CapturePhase
import com.example.roomsplat.domain.model.CoverageSummary
import com.example.roomsplat.domain.model.HeadPoseEvidence
import com.example.roomsplat.domain.model.Point3
import com.example.roomsplat.domain.model.SavedFrame
import com.example.roomsplat.domain.model.SurfaceRecord
import com.example.roomsplat.domain.usecase.SurfaceCoverageUseCase
import com.example.roomsplat.domain.usecase.MeshDisplayStabilityUseCase
import com.example.roomsplat.domain.usecase.SubdivideSurfaceMeshUseCase
import com.example.roomsplat.domain.usecase.SurfaceRevealUseCase
import com.example.roomsplat.domain.usecase.ActiveSceneOwnerUseCase
import com.example.roomsplat.domain.usecase.MeasuredMeshGeometryUseCase
import com.example.roomsplat.domain.usecase.MeshRenderPlanUseCase
import com.example.roomsplat.domain.usecase.BakeMeshDisplayFrameUseCase
import com.example.roomsplat.domain.usecase.InsetTriangleMeshUseCase
import com.example.roomsplat.domain.usecase.ObservedSurfacePlanUseCase
import com.example.roomsplat.domain.usecase.RegularPlaneMeshUseCase
import com.example.roomsplat.domain.usecase.SurfaceOverlapUseCase
import com.example.roomsplat.domain.usecase.ClippedSurfaceMeshUseCase
import com.example.roomsplat.domain.usecase.ReconcileTrackedSurfacesUseCase
import com.example.roomsplat.domain.usecase.ScanGeometryAdmissionUseCase
import com.example.roomsplat.domain.usecase.LatestMeshUpdateUseCase
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PolygonFillMode
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.mesh.MeshAnchor
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import com.pico.spatial.sense.mesh.MeshTrackingManager
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Live geometry with explicitly approximate, head-centred photographic evidence. */
class CoverageSceneController(private val cache: HeadPoseCache, private val frames: SharedFlow<SavedFrame>, private val captures: StateFlow<CaptureSnapshot>,private val diagnosticsDirectory:File?=null,context:Context) {
    private val displayRoot = CoverageDisplayRoot()
    val root: Entity get() = displayRoot.entity
    private var attached = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(CoverageSummary())
    val summary = mutable.asStateFlow()
    private val hmd = HMDTrackingProvider()
    private val coverage = SurfaceCoverageUseCase()
    private val reveal = SurfaceRevealUseCase(coverage::key,spreadNanos=350_000_000L)
    private val liveDepth=LiveDepthRepository(context.applicationContext,cache)
    private val depthAdmission=LiveDepthAdmissionUseCase()
    private val depthVisibility=LiveDepthVisibilityUseCase()
    private val depthQueryCache=LiveDepthQueryCacheUseCase()
    private var minDepthAcquisitionNanos=Long.MIN_VALUE
    private val verifiedProgress=VerifiedCoverageProgressUseCase()
    private var lastDepthAdmissionLog=0L
    private var observedPoints:Map<String,Point3> = emptyMap()
    private var drawOccludedKeys:Set<String> = emptySet()
    private var occludedKeys:Set<String> = emptySet()
    private var occlusionOrigin:Point3?=null
    private var occlusionRevision=-1L
    private var lastOcclusionNanos=0L
    private var lastOcclusionMillis=0L
    private val stability = MeshDisplayStabilityUseCase()
    private val subdivision = SubdivideSurfaceMeshUseCase()
    private val planeGrid = RegularPlaneMeshUseCase()
    private val overlap = SurfaceOverlapUseCase<UUID>()
    private val reconcile=ReconcileTrackedSurfacesUseCase<UUID>()
    private val admission=ScanGeometryAdmissionUseCase<UUID>()
    private var trackingToken=0L
    private val nativeEventCounts=mutableMapOf<String,Int>()
    private val lastNativeEvents=mutableMapOf<UUID,Long>()
    private val geometry = MeasuredMeshGeometryUseCase()
    private val renderPlans = MeshRenderPlanUseCase()
    private val insetTriangles=InsetTriangleMeshUseCase()
    private val displayFrame=BakeMeshDisplayFrameUseCase()
    private val observedPlans = ObservedSurfacePlanUseCase<UUID>()
    private var observationRevision=0L
    private var planeRevision=0L
    @Volatile private var geometryEpoch=0L
    private var currentScanRevision=captures.value.scanRevision
    private val renderRequestedNanos=mutableMapOf<UUID,Long>()
    private val renderMailbox = LatestMeshUpdateUseCase<UUID>()
    private var renderJob: Job? = null
    private var deferredMeshUpdates = 0
    private var sessionJob: Job? = null
    private var lastFrame: SavedFrame? = null
    private var lastPersistNanos = 0L
    private val persistenceMutex = Mutex()
    private var cellOwners: Map<String,Set<UUID>> = emptyMap()
    private val removedAnchors = mutableSetOf<UUID>()
    private val pendingRemovals = mutableSetOf<UUID>()
    private data class Source(val vertices: List<Vector3>,val indices: List<Int>,val origin: Vector3,val rotation: Quat,val kind:String,
        val vertexKinds:List<String> = emptyList())
    private data class QueuedAnchor(val id: UUID,val plane: Boolean,val receivedNanos: Long,
        val epoch:Long,val retryAfterNanos: Long=0L,val read: () -> Source)
    private data class RawGeometry(val points: List<Point3>,val centres: List<Point3>,val area: Float,
        val bounds: MeasuredMeshGeometryUseCase.Bounds?,val decision: MeshDisplayStabilityUseCase.Decision,val worldVertices: List<Point3>,
        val planeMesh: RegularPlaneMeshUseCase.Mesh?=null)
    private data class RefinedGeometry(val positions: List<Vector3>,val indices: List<Int>,val centres: List<Point3>,val keys: List<String>,val worldPositions: List<Point3>,val kinds:List<String>)
    private val pendingAnchors = linkedMapOf<UUID, QueuedAnchor>()
    private var meshJob: Job? = null
    private var revealJob: Job? = null
    private val revealStateMutex=Mutex()
    private var geometryBusy=false
    private var revealTicks=0L
    private var lastRevealTick=0L
    private var maxRevealGapMs=0L
    private var lastCadenceReport=0L
    private var loadJob: Job? = null
    private var frameMessage: String? = null
    private var receivedMeshUpdates = 0
    private var acceptedMeshUpdates = 0
    private var meshSubscription: Cancellable? = null
    private var planeSubscription: Cancellable? = null
    private var poseJob: Job? = null
    private var frameJob: Job? = null
    private var currentSession = ""
    private var started = false
    private var disposed = false
    private var visible = true
    private val materials = mutableMapOf<SurfaceRecord, UnlitMaterial>()
    private val anchors = mutableMapOf<UUID, AnchorEntry>()
    private data class Group(val entity: Entity, var mesh: MeshResource, var indices: List<Int>,var materialStatus: SurfaceRecord,
        var geometryRevision:Long=-1,var displayRevision:Long=-1)
    private data class AnchorEntry(val owner: Entity, val id: UUID,val plane: Boolean, var centres: List<Point3>, var positions: List<Vector3> = emptyList(), var indices: List<Int> = emptyList(),
        var rawCentres: List<Point3> = emptyList(), var rawWorldVertices: List<Point3> = emptyList(), var displayedNanos: Long = 0,
        var rawAreaM2: Float = 0f, var localBounds: BoundingBox? = null, var cellKeys: List<String> = emptyList(),
        var worldPositions: List<Point3> = emptyList(),var suppressed: BooleanArray = BooleanArray(0),
        var fragments:Map<Int,List<ClippedSurfaceMeshUseCase.Weight>> = emptyMap(),
        var displayMesh:ClippedSurfaceMeshUseCase.Mesh?=null,var displayRevision:Long=0,
        var kind:String="UNKNOWN",var triangleKinds:List<String> = emptyList(),
        var planeFrame: RegularPlaneMeshUseCase.Region? = null,var planeSupport: RegularPlaneMeshUseCase.Region? = null,
        var maskGeometryRevision: Long = -1,var maskPlaneRevision: Long = -1,
        var geometryRevision: Long = 0, var geometryPending: Boolean = false, var receivedNanos: Long = 0,
        val groups: MutableMap<SurfaceRecord, Group> = mutableMapOf())

    init {
        diagnosticsDirectory?.let { directory -> scope.launch {
            val request=File(directory,"request-display-snapshot")
            while(isActive) {
                delay(1000)
                if(!withContext(Dispatchers.IO) { request.exists() })continue
                val snapshot=anchors.values.map { e ->
                    val p=e.displayMesh?.positions?:e.positions.map { Point3(it.x,it.y,it.z) }
                    val currentGroups=e.groups.values.filter { it.geometryRevision==e.geometryRevision && it.displayRevision==e.displayRevision }
                    mapOf("id" to e.id.toString(),"type" to if(e.plane)"plane_${e.kind}" else "mesh",
                        "geometry_pending" to e.geometryPending,"world_points" to e.worldPositions,
                        "indices" to e.indices,"cell_keys" to e.cellKeys,"triangle_kinds" to e.triangleKinds,"suppressed" to e.suppressed.toList(),
                        "fragment_sources" to e.fragments.keys.toList(),"display_points" to p,
                        "draw_indices" to currentGroups.flatMap { it.indices },
                        "current_groups" to currentGroups.size,"stale_groups" to e.groups.size-currentGroups.size)
                }
                val revision=observationRevision;val phase=captures.value.phase.name
                val displayState=displayRoot.diagnostic()
                val eventCounts=nativeEventCounts.toMap()
                val depth=liveDepth.frame
                val snapshotDepthFloor=minDepthAcquisitionNanos
                val snapshotAt=SystemClock.elapsedRealtimeNanos()
                val head=cache.nearest(snapshotAt)
                val hiddenSnapshot=drawOccludedKeys.toList()
                val progressSnapshot=verifiedProgress.counts()
                val occlusionState=mapOf("measured_planes" to anchors.values.count { it.plane },"blocked_cells" to occludedKeys.size,
                    "mesh_triangles" to anchors.values.filter { !it.plane }.sumOf { it.indices.size/3 },"mesh_backend" to "unused_live_depth","draw_blocked_cells" to drawOccludedKeys.size,
                    "query_ms" to lastOcclusionMillis,"revision" to occlusionRevision,
                    "sample_nanos" to lastOcclusionNanos,"reveal_mode" to "retain_seen_progress",
                    "reveal_history_cells" to reveal.historyCount(),
                    "origin" to (occlusionOrigin?.let { listOf(it.x,it.y,it.z) }?:emptyList()),
                    "blocked_keys" to occludedKeys.toList())
                withContext(Dispatchers.Default) {
                    val sources=JSONArray()
                    snapshot.forEach { s ->
                        val row=JSONObject()
                        s.forEach { (key,value) ->
                            if(key in setOf("world_points","display_points")) {
                                val points=JSONArray()
                                @Suppress("UNCHECKED_CAST")
                                (value as List<Point3>).forEach { points.put(JSONArray(listOf(it.x,it.y,it.z))) }
                                row.put(key,points)
                            } else row.put(key,if(value is List<*>)JSONArray(value) else value)
                        }
                        sources.put(row)
                    }
                    fun headJson(h:HeadPoseEvidence)=JSONObject().put("received_nanos",h.receivedNanos)
                        .put("position",JSONArray(listOf(h.position.x,h.position.y,h.position.z)))
                        .put("forward",JSONArray(listOf(h.forward.x,h.forward.y,h.forward.z)))
                        .put("right",JSONArray(listOf(h.right.x,h.right.y,h.right.z)))
                        .put("up",JSONArray(listOf(h.up.x,h.up.y,h.up.z)))
                    val depthJson=depth?.let { f ->
                        val values=JSONArray();for(y in 0 until f.height)for(x in 0 until f.width) {
                            val v=f.at(x,y);values.put(if(v.isFinite())v else JSONObject.NULL)
                        }
                        JSONObject().put("width",f.width).put("height",f.height).put("metres_z",values)
                            .put("received_nanos",f.receivedNanos).put("acquisition_started_nanos",f.acquisitionStartedNanos)
                            .put("scan_min_acquisition_nanos",snapshotDepthFloor).put("age_nanos",snapshotAt-f.receivedNanos)
                            .put("pose",headJson(f.pose))
                    }
                    val json=JSONObject().put("schema","roomsplat.cached-display/1").put("observation_revision",revision)
                        .put("capture_phase",phase).put("display",JSONObject(displayState))
                        .put("coverage_requested_visible",visible).put("controller_started",started)
                        .put("native_events_since_reset",JSONObject(eventCounts))
                        .put("occlusion",JSONObject(occlusionState))
                        .put("snapshot_nanos",snapshotAt).put("head",head?.let(::headJson))
                        .put("live_depth",depthJson).put("depth_hidden_keys",JSONArray(hiddenSnapshot))
                        .put("verified_progress",JSONArray(listOf(progressSnapshot.first,progressSnapshot.second,progressSnapshot.third)))
                        .put("source_kind","cached fixed-frame pre-inset committed groups; stale groups separately marked")
                        .put("sources",sources).toString()
                    withContext(Dispatchers.IO) {
                        directory.mkdirs();File(directory,"display-snapshot.json").writeText(json);request.delete()
                    }
                    Log.i(TAG,"DISPLAY_SNAPSHOT_SAVED anchors=${snapshot.size} revision=$revision")
                }
            }
        } }
    }
    fun attach(content: SpatialViewContent) {
        if(disposed)return
        activeScene.claim(this) { previous ->
            Log.i(TAG,"COVERAGE_OWNER_REPLACED retiring_root=${previous.root.id} next_root=${root.id}")
            previous.close()
        }
        displayRoot.attach(content)
        attached = true
        start()
    }

    fun start() {
        if (started || disposed || !attached) return
        started = true
        displayRoot.setVisible(visible)
        renderRequestedNanos.replaceAll { _,_ -> SystemClock.elapsedRealtimeNanos() }
        try {
            poseJob = scope.launch(Dispatchers.Default) {
                hmd.dataFlow.collect { data ->
                    val pose = data.hmdPose
                    fun point(value: Vector3) = Point3(value.x, value.y, value.z)
                    val rotation = pose.rotation
                    cache.add(HeadPoseEvidence(SystemClock.elapsedRealtimeNanos(), point(pose.position),
                        point(rotation.rotateVector(Vector3(0f, 0f, -1f))), point(rotation.rotateVector(Vector3(1f, 0f, 0f))),
                        point(rotation.rotateVector(Vector3(0f, 1f, 0f))), listOf(rotation.x, rotation.y, rotation.z, rotation.w)))
                }
            }
            Log.i(TAG, "HMD_START result=${hmd.start()}")
            liveDepth.start(scope)
            subscribeGeometry()
            /* Geometry listeners belong to an immutable subscription token. */
            MeshTrackingManager.start()
            PlaneTrackingManager.start()
            renderJob = scope.launch {
                while (isActive) {
                    // While photography is paused, visibility can still change as the user moves.
                    val ticket=renderMailbox.take()
                    if(ticket==null) { delay(16);continue }
                    var committed=false
                    try { committed=renderTicket(ticket) }
                    catch(error:CancellationException) { throw error }
                    catch(error:Exception) { reportError(error) }
                    finally { if(committed)renderMailbox.complete(ticket) else renderMailbox.retry(ticket) }
                    delay(8)
                }
            }
            meshJob = scope.launch {
                while (isActive) {
                    delay(40)
                    try {
                        if (captures.value.phase !in setOf(CapturePhase.PAUSED, CapturePhase.CAPTURED, CapturePhase.PACKING, CapturePhase.EXPORTED)) {
                            geometryBusy=true
                            try { flushMeshUpdates() } finally { geometryBusy=false }
                        }
                    } catch (error: CancellationException) { throw error } catch (error: Exception) { reportError(error) }
                }
            }
            revealJob = scope.launch {
                lastRevealTick=0L;maxRevealGapMs=0L
                while(isActive) {
                    val cycleStarted=SystemClock.elapsedRealtimeNanos()
                    try {
                        // Only reveal-state prepare/commit is serialized, not expensive geometry work.
                        revealStateMutex.withLock {
                            val tick=SystemClock.elapsedRealtimeNanos()
                            if(lastRevealTick!=0L)maxRevealGapMs=maxOf(maxRevealGapMs,(tick-lastRevealTick)/1_000_000)
                            lastRevealTick=tick;revealTicks++
                            updateReveal()
                            if(tick-lastCadenceReport>=2_000_000_000L) {
                                Log.i(TAG,"REVEAL_CADENCE ticks=$revealTicks max_gap_ms=$maxRevealGapMs geometry_busy=$geometryBusy budget_cells=128")
                                lastCadenceReport=tick;maxRevealGapMs=0
                            }
                        }
                    } catch(error:CancellationException) { throw error } catch(error:Exception) { reportError(error) }
                    // Include lock wait and query/render work in the target interval. Still yield
                    // when overloaded so this worker cannot spin or monopolize Main.
                    val spentMs=(SystemClock.elapsedRealtimeNanos()-cycleStarted)/1_000_000
                    delay((40L-spentMs).coerceAtLeast(4L))
                }
            }
            // Apply a reset that happened while this view was paused before loading snapshots.
            if(currentScanRevision!=captures.value.scanRevision) {
                currentScanRevision=captures.value.scanRevision
                clearMeasuredGeometry()
            } else loadCurrentAnchors()
            sessionJob = scope.launch {
                captures.collect { capture ->
                    try {
                        if(capture.scanRevision!=currentScanRevision) {
                            currentScanRevision=capture.scanRevision
                            clearMeasuredGeometry()
                        }
                        if (capture.sessionPath != currentSession) changeSession(capture.sessionPath)
                        else if (capture.phase in setOf(CapturePhase.PAUSED, CapturePhase.CAPTURED)) lastFrame?.let { persist(it) }
                    } catch (error: CancellationException) { throw error } catch (error: Exception) { reportError(error) }
                }
            }
            frameJob = scope.launch { frames.collect { frame -> try { record(frame) } catch (error: CancellationException) { throw error } catch (error: Exception) { reportError(error) } } }
            Log.i(TAG, "COVERAGE_STARTED mesh_state=${MeshTrackingManager.state} plane_state=${PlaneTrackingManager.state}")
        } catch (error: CancellationException) { throw error } catch (error: Exception) { started = false; reportError(error) }
    }

    private fun subscribeGeometry() {
            meshSubscription?.cancel();planeSubscription?.cancel()
            val token=admission.begin();trackingToken=token
            val epoch=geometryEpoch
            meshSubscription = MeshTrackingManager.subscribeAnchorUpdate { update ->
                val received=SystemClock.elapsedRealtimeNanos()
                scope.launch {
                    if (!started || epoch!=geometryEpoch || currentScanRevision!=captures.value.scanRevision || !admission.current(token)) return@launch
                    try {
                        val eventKey="mesh.${update.event}"
                        nativeEventCounts[eventKey]=(nativeEventCounts[eventKey]?:0)+1
                        val event=when(update.event) {
                            AnchorUpdate.Event.ADDED -> ScanGeometryAdmissionUseCase.Event.ADDED
                            AnchorUpdate.Event.UPDATED -> ScanGeometryAdmissionUseCase.Event.UPDATED
                            AnchorUpdate.Event.LOADED -> ScanGeometryAdmissionUseCase.Event.LOADED
                            AnchorUpdate.Event.REMOVED -> ScanGeometryAdmissionUseCase.Event.REMOVED
                            else -> return@launch
                        }
                        if(!admission.event(update.anchor.anchorUUID,token,event))return@launch
                        if (update.event == AnchorUpdate.Event.REMOVED) remove(update.anchor.anchorUUID,received)
                        else if (update.event in setOf(AnchorUpdate.Event.ADDED, AnchorUpdate.Event.UPDATED, AnchorUpdate.Event.LOADED))
                            queue(update.anchor,received,epoch)
                    } catch (error: CancellationException) { throw error } catch (error: Exception) { reportError(error) }
                }
            }
            planeSubscription = PlaneTrackingManager.subscribeAnchorUpdate { update ->
                val received=SystemClock.elapsedRealtimeNanos()
                scope.launch {
                    if(!started || epoch!=geometryEpoch || currentScanRevision!=captures.value.scanRevision || !admission.current(token))return@launch
                    try {
                        val eventKey="plane.${update.event}"
                        nativeEventCounts[eventKey]=(nativeEventCounts[eventKey]?:0)+1
                        val event=when(update.event) {
                            AnchorUpdate.Event.ADDED -> ScanGeometryAdmissionUseCase.Event.ADDED
                            AnchorUpdate.Event.UPDATED -> ScanGeometryAdmissionUseCase.Event.UPDATED
                            AnchorUpdate.Event.LOADED -> ScanGeometryAdmissionUseCase.Event.LOADED
                            AnchorUpdate.Event.REMOVED -> ScanGeometryAdmissionUseCase.Event.REMOVED
                            else -> return@launch
                        }
                        if(!admission.event(planeId(update.anchor.anchorUUID),token,event))return@launch
                        if(update.event==AnchorUpdate.Event.REMOVED)
                            remove(planeId(update.anchor.anchorUUID),received)
                        else queue(update.anchor,received,epoch)
                    } catch(error:CancellationException) { throw error } catch(error:Exception) { reportError(error) }
                }
            }
            Log.i(TAG,"TRACKING_SUBSCRIPTION token=$token epoch=$epoch loaded_unknown=false")
    }

    private fun planeId(id: UUID)=UUID.nameUUIDFromBytes(("plane:"+id).toByteArray(Charsets.UTF_8))
    private fun queue(anchor: MeshAnchor,receivedNanos: Long=SystemClock.elapsedRealtimeNanos(),epoch:Long=geometryEpoch) {
        enqueue(QueuedAnchor(anchor.anchorUUID,false,receivedNanos,epoch) {
            val transform=anchor.transform
            Source(anchor.vertices.toList(),anchor.indices.toList(),transform.position,transform.rotation.toQuat(),"MESH",
                anchor.semantics.map { it.toString() })
        })
    }
    private fun queue(anchor: PlaneAnchor,receivedNanos: Long=SystemClock.elapsedRealtimeNanos(),epoch:Long=geometryEpoch) {
        enqueue(QueuedAnchor(planeId(anchor.anchorUUID),true,receivedNanos,epoch) {
            val transform=anchor.transform
            Source(anchor.vertices.toList(),anchor.indices.toList(),transform.position,transform.rotation.toQuat(),anchor.semantics.toString())
        })
    }
    private fun enqueue(incoming: QueuedAnchor) {
        if(!started || disposed || incoming.epoch!=geometryEpoch || currentScanRevision!=captures.value.scanRevision)return
        lastNativeEvents[incoming.id]=incoming.receivedNanos
        removedAnchors.remove(incoming.id)
        pendingRemovals.remove(incoming.id)
        // Retain newest data but the oldest outstanding arrival for honest end-to-end latency.
        pendingAnchors[incoming.id]=incoming.copy(receivedNanos=pendingAnchors[incoming.id]?.receivedNanos?:incoming.receivedNanos)
        receivedMeshUpdates++
    }

    private suspend fun flushMeshUpdates() {
        if (!started || pendingAnchors.isEmpty() && pendingRemovals.isEmpty()) return
        val epoch=geometryEpoch
        val removals=pendingRemovals.toList()
        pendingRemovals.clear()
        removals.forEach { removeEntry(it) }
        // New sources and planes receive priority; ordinary mesh refreshes still get two slots.
        val now=SystemClock.elapsedRealtimeNanos()
        val eligible=pendingAnchors.values.filter { it.retryAfterNanos<=now }
        val urgent=eligible.filter { it.id !in anchors || it.plane }.take(6).map { it.id }
        val rest=eligible.filter { it.id !in urgent }.take(2).map { it.id }
        val batch = (urgent+rest).mapNotNull { pendingAnchors.remove(it) }
        val changed = mutableListOf<AnchorEntry>()
        for (anchor in batch) {
            try { prepare(anchor)?.let { changed.add(it) } }
            catch (error: CancellationException) { throw error } catch (error: Exception) { reportError(error) }
        }
        if(epoch!=geometryEpoch)return
        if (changed.isNotEmpty() || removals.isNotEmpty()) {
            rebuildObserved()
            if(epoch!=geometryEpoch)return
            changed.filter { anchors[it.id]===it }.forEach {
                it.geometryPending=false;render(it,geometryChanged=true,prioritize=it.groups.isEmpty())
            }
            acceptedMeshUpdates += changed.size
            publish()
        }
        Log.i(TAG, "MESH_BATCH received=$receivedMeshUpdates accepted=$acceptedMeshUpdates deferred=$deferredMeshUpdates pending=${pendingAnchors.size} anchors=${anchors.size} cells=${coverage.counts().first}")
    }


    private suspend fun prepare(incoming: QueuedAnchor): AnchorEntry? {
        if(incoming.epoch!=geometryEpoch)return null
        val id=incoming.id
        val previous=anchors[id]
        val now=SystemClock.elapsedRealtimeNanos()
        val previousCentres=previous?.rawCentres.orEmpty()
        val previousDisplayed=previous?.displayedNanos?:0L
        val previousArea=previous?.rawAreaM2
        val previousWorldVertices=previous?.rawWorldVertices
        Trace.beginSection("RoomSplat.AnchorRead")
        val source=try { incoming.read() } finally { Trace.endSection() }
        val vertices=source.vertices;val triangleIndices=source.indices
        if(vertices.isEmpty() || triangleIndices.isEmpty())return null
        val rootOrigin=root.convertPositionFrom(Vector3(0f,0f,0f),null)
        val rootRotation=root.convertRotationFrom(Quat.identity(),null)
        val displayOriginPoint=Point3(rootOrigin.x,rootOrigin.y,rootOrigin.z)
        val displayQuaternion=listOf(rootRotation.x,rootRotation.y,rootRotation.z,rootRotation.w)
        val previousFrame=previous?.planeFrame
        val previousPlanePositions=previous?.worldPositions
        val previousPlaneIndices=previous?.indices
        val originalTriangles=triangleIndices.size/3
        val others=anchors.entries.sumOf { (otherId,entry) -> if(otherId==id)0 else entry.indices.size/3 }
        val budget=minOf(6000,(80_000-others).coerceAtLeast(maxOf(2,originalTriangles)))
        val raw=withContext(Dispatchers.Default) {
            traced("RoomSplat.GeometryDecision") {
                check(triangleIndices.size%3==0 && triangleIndices.all { it in vertices.indices }) { "空间网格索引无效。" }
                val worldVertices=vertices.map { val p=source.origin+source.rotation.rotateVector(it);Point3(p.x,p.y,p.z) }
                val planeMesh=if(incoming.plane)planeGrid.build(worldVertices,triangleIndices,previousFrame,maxTriangles=budget) else null
                val points=planeMesh?.positions?:worldVertices
                val usedIndices=planeMesh?.indices?:triangleIndices
                val centres=pointCentres(points,usedIndices)
                val area=geometry.area(points,usedIndices)
                val decision=when {
                    incoming.plane && (planeMesh==null || usedIndices.isEmpty()) -> MeshDisplayStabilityUseCase.Decision.IGNORE
                    incoming.plane && planeMesh?.reusedFrame==true && points==previousPlanePositions && usedIndices==previousPlaneIndices -> MeshDisplayStabilityUseCase.Decision.IGNORE
                    incoming.plane && planeMesh?.reusedFrame==true -> MeshDisplayStabilityUseCase.Decision.ACCEPT
                    else -> stability.decide(previousCentres,centres,previousDisplayed,now,previousArea,area,previousWorldVertices,worldVertices)
                }
                RawGeometry(points,centres,area,
                    if(decision==MeshDisplayStabilityUseCase.Decision.ACCEPT)geometry.bounds(displayFrame.build(points,displayOriginPoint,displayQuaternion)) else null,
                    decision,worldVertices,planeMesh)
            }
        }
        if(!acceptsGeometry(id,previous,incoming.epoch))return null
        when(raw.decision) {
            MeshDisplayStabilityUseCase.Decision.IGNORE -> {
                Log.d(TAG,"GEOMETRY_IGNORED id=$id plane=${incoming.plane}")
                return null
            }
            MeshDisplayStabilityUseCase.Decision.DEFER -> {
                pendingAnchors.putIfAbsent(id,incoming.copy(retryAfterNanos=now+200_000_000L))
                deferredMeshUpdates++
                return null
            }
            MeshDisplayStabilityUseCase.Decision.ACCEPT -> Unit
        }
        val refined=withContext(Dispatchers.Default) {
            traced("RoomSplat.GeometryBuild") {
                val mesh=raw.planeMesh?.let { SubdivideSurfaceMeshUseCase.Mesh(it.positions,it.indices) }
                    ?:subdivision.build(raw.points,triangleIndices,maxTriangles=budget)
                val centres=pointCentres(mesh.positions,mesh.indices)
                val positions=displayFrame.build(mesh.positions,displayOriginPoint,displayQuaternion).map { Vector3(it.x,it.y,it.z) }
                val sourceKinds=triangleIndices.chunked(3).map { t ->
                    val kinds=t.map { source.vertexKinds.getOrNull(it)?:"UNKNOWN" }
                    if(kinds.distinct().size==1)kinds[0] else "UNKNOWN"
                }
                val kinds=if(incoming.plane)emptyList() else mesh.sourceTriangles.map { sourceKinds[it] }
                RefinedGeometry(positions,mesh.indices,centres,centres.map(coverage::key),mesh.positions,kinds)
            }
        }
        if(!acceptsGeometry(id,previous,incoming.epoch))return null
        val entry=previous?:run {
            val owner=Entity().apply { setName("mesh_${id.toString().take(8)}") }
            root.addChild(owner)
            AnchorEntry(owner,id,incoming.plane,emptyList()).also { anchors[id]=it }
        }
        traced("RoomSplat.GeometryApply") {
            entry.planeFrame=raw.planeMesh?.measured
            entry.planeSupport=raw.planeMesh?.support
            entry.worldPositions=refined.worldPositions
            entry.kind=source.kind;entry.triangleKinds=refined.kinds
            entry.suppressed=BooleanArray(refined.indices.size/3)
            entry.fragments=emptyMap();entry.displayMesh=null;entry.displayRevision++
            entry.geometryPending=true
            entry.geometryRevision++
            if(entry.plane)planeRevision++
            observationRevision++
            entry.receivedNanos=incoming.receivedNanos
            entry.rawCentres=raw.centres
            entry.rawWorldVertices=raw.worldVertices
            entry.rawAreaM2=raw.area
            val bounds=checkNotNull(raw.bounds)
            entry.localBounds=BoundingBox(
                center=Vector3(bounds.centre.x,bounds.centre.y,bounds.centre.z),
                halfExtent=Vector3(bounds.halfExtent.x,bounds.halfExtent.y,bounds.halfExtent.z))
            entry.centres=refined.centres
            entry.cellKeys=refined.keys
            entry.positions=refined.positions
            entry.indices=refined.indices
            entry.displayedNanos=now
            // This owner stays identity. Existing groups keep their world pose until the staged meshes are ready.
        }
        Log.i(TAG,"MESH_DETAIL plane=${incoming.plane} kind=${source.kind} regular=${raw.planeMesh!=null} original=$originalTriangles refined=${refined.indices.size/3} area_m2=${raw.area} bounds_from_vertices=true cell_m=${SurfaceCoverageUseCase.CELL_SIZE_M} source_age_ms=${(SystemClock.elapsedRealtimeNanos()-incoming.receivedNanos)/1_000_000}")
        return entry
    }

    private fun acceptsGeometry(id: UUID, previous: AnchorEntry?,epoch:Long): Boolean =
        started && !disposed && epoch==geometryEpoch && id !in removedAnchors && anchors[id]===previous &&
            captures.value.phase !in setOf(CapturePhase.PAUSED,CapturePhase.CAPTURED,CapturePhase.PACKING,CapturePhase.EXPORTED)

    private fun pointCentres(points: List<Point3>,indices: List<Int>): List<Point3> =
        indices.chunked(3).map { t ->
            val a=points[t[0]];val b=points[t[1]];val c=points[t[2]]
            Point3((a.x+b.x+c.x)/3,(a.y+b.y+c.y)/3,(a.z+b.z+c.z)/3)
        }

    private fun material(status: SurfaceRecord): UnlitMaterial {
        materials[status]?.takeIf { it.valid }?.let { return it }
        materials.remove(status)?.close()
        return UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
            val color = when (status) {
                SurfaceRecord.UNRECORDED -> Color4(86f/255, 116f/255, 216f/255, 1f)
                SurfaceRecord.ONE_POSITION -> Color4(223f/255, 163f/255, 41f/255, 1f)
                SurfaceRecord.MULTIPLE_POSITIONS -> Color4(63f/255, 167f/255, 115f/255, 1f)
            }
            setBaseColor(color)
            setOpacity(if (status == SurfaceRecord.UNRECORDED) .32f else .28f)
            setPolygonFillMode(PolygonFillMode.FILL)
            setCullingMode(MaterialCullingMode.NONE)
            setDepthWrite(false)
            setDepthTest(true)
            // A Java/Kotlin cache does not retain SDK resource ownership when the last entity is destroyed.
            // These three shared resources belong to this controller and are closed in close().
            toGlobal()
        }.also { materials[status] = it }
    }


    private fun render(entry: AnchorEntry,geometryChanged: Boolean=false,prioritize: Boolean=false) {
        renderRequestedNanos.putIfAbsent(entry.id,SystemClock.elapsedRealtimeNanos())
        renderMailbox.request(entry.id,geometryChanged,prioritize)
    }


    private suspend fun renderTicket(ticket: LatestMeshUpdateUseCase.Ticket<UUID>): Boolean {
        val entry=anchors[ticket.key]?:return true
        if(entry.geometryPending)return false
        val revision=entry.geometryRevision
        val displayRevision=entry.displayRevision
        val bounds=checkNotNull(entry.localBounds) { "缺少已测量网格的实际边界。" }
        val positions=entry.positions
        val displayMesh=entry.displayMesh
        val indices=displayMesh?.indices?:entry.indices
        fun current()=started && !disposed && currentScanRevision==captures.value.scanRevision && anchors[ticket.key]===entry && !entry.geometryPending &&
            entry.geometryRevision==revision && entry.displayRevision==displayRevision && renderMailbox.isCurrent(ticket)
        val states=ByteArray(indices.size/3)
        traced("RoomSplat.RenderSnapshot") {
            for(i in states.indices) {
                val source=displayMesh?.sourceTriangles?.get(i)?:i
                val key=entry.cellKeys[source]
                // Only repeated coherent LIVE foreground depth can mask retained progress.
                states[i]=if(!entry.suppressed[source] && reveal.isVisibleKey(key) && key !in drawOccludedKeys)
                    coverage.statusKey(key).ordinal.toByte() else (-1).toByte()
            }
        }
        val plan=withContext(Dispatchers.Default) {
            traced("RoomSplat.RenderPlan") { renderPlans.build(indices,states,displayMesh?.positions?.size?:positions.size) }
        }
        if(!current())return false
        val staged=mutableMapOf<SurfaceRecord,MeshResource>()
        try {
            for((status,group) in plan) {
                val existing=entry.groups[status]
                if(group.globalIndices.isEmpty() || !ticket.geometryChanged && existing?.indices==group.globalIndices)continue
                val model=withContext(Dispatchers.Default) {
                    traced("RoomSplat.TriangleTiles") {
                        val compact=group.vertexSources.map { index -> displayMesh?.positions?.get(index)?:positions[index].let { Point3(it.x,it.y,it.z) } }
                        val tiles=insetTriangles.build(compact,group.localIndices)
                        MeshModel(positions=tiles.positions.map { Vector3(it.x,it.y,it.z) },triangleIndices=tiles.indices)
                    }
                }
                if(!current())return false
                withContext(NonCancellable+Dispatchers.IO) {
                    traced("RoomSplat.MeshPrepareIO") {
                        staged[status]=MeshResource.createWithMeshModel(model=model,bounds=bounds,
                            name="coverage_${ticket.key}_${status.ordinal}_${ticket.revision}")
                    }
                }
                currentCoroutineContext().ensureActive()
                if(!current())return false
            }
            // All resources are ready. Apply every colour group in one Main turn, without moving its owner.
            traced("RoomSplat.MeshCommit") {
                for((status,group) in plan) {
                    val existing=entry.groups[status]
                    if(group.globalIndices.isEmpty()) {
                        existing?.entity?.destroy();existing?.mesh?.close()
                        entry.groups.remove(status)
                        continue
                    }
                    val nextMesh=staged[status]
                    if(existing==null) {
                        val mesh=checkNotNull(nextMesh)
                        val entity=ModelEntity(mesh,material(status)).apply { setName("coverage_${status.ordinal}") }
                        try { entry.owner.addChild(entity) } catch(e:Exception) { entity.destroy();throw e }
                        entry.groups[status]=Group(entity,mesh,group.globalIndices,status,revision,displayRevision)
                        staged.remove(status)
                    } else {
                        val component=checkNotNull(existing.entity.components[ModelComponent::class.java])
                        if(existing.materialStatus!=status) {
                            component.materials.set(0,material(status))
                            existing.materialStatus=status
                        }
                        if(nextMesh!=null) {
                            val old=existing.mesh
                            component.mesh=nextMesh
                            existing.mesh=nextMesh
                            existing.indices=group.globalIndices
                            existing.geometryRevision=revision;existing.displayRevision=displayRevision
                            staged.remove(status)
                            old.close()
                        }
                    }
                }
            }
            if(!current())return false
            val queued=renderRequestedNanos.remove(ticket.key)?:SystemClock.elapsedRealtimeNanos()
            Log.i(TAG,"RENDER_APPLIED pending=${renderMailbox.size()} queue_ms=${(SystemClock.elapsedRealtimeNanos()-queued)/1_000_000} source_age_ms=${(SystemClock.elapsedRealtimeNanos()-entry.receivedNanos)/1_000_000} atomic_groups=true fixed_owner=true")
            return true
        } finally { staged.values.forEach { it.close() } }
    }

    private suspend fun record(frame: SavedFrame) {
        val processStart = SystemClock.elapsedRealtimeNanos()
        if (captures.value.phase != CapturePhase.RECORDING) return
        if (frame.sessionPath != captures.value.sessionPath) return
        if (frame.sessionPath != currentSession) changeSession(frame.sessionPath)
        val epoch=geometryEpoch;val points=observedPoints;val depth=liveDepth.frame
        val depthFloor=minDepthAcquisitionNanos
        val matched=withContext(Dispatchers.Default) { depthAdmission.photoMatches(points,depth,frame.headPose,frame.receivedNanos,depthFloor) }
        if(!started || epoch!=geometryEpoch || captures.value.phase!=CapturePhase.RECORDING ||
            frame.sessionPath!=captures.value.sessionPath)return
        val eligible=matched.filterTo(mutableSetOf()) { key ->
            val old=points[key];val current=observedPoints[key]
            old!=null && current!=null && (old-current).length()<=.02f && reveal.isVisibleKey(key) && key !in drawOccludedKeys
        }
        lastFrame = frame
        val changed = traced("RoomSplat.CoverageRecord") { coverage.record(frame,eligible) }
        verifiedProgress.recordSaved(coverage.lastChangedKeys.mapNotNull { key ->
            observedPoints[key]?.let { key to (it to coverage.statusKey(key)) }
        }.toMap())
        val dirty = coverage.lastChangedKeys.flatMap { cellOwners[it].orEmpty() }.toSet()
        dirty.forEach { id -> anchors[id]?.let { render(it) } }
        val message = when {
            anchors.isEmpty() -> "尚未发现空间网格；缓慢观察墙面与家具。"
            frame.headPose == null -> "头部追踪暂不可用，这张照片未用于覆盖标记。"
            eligible.isEmpty() -> "照片已保存；请停稳片刻，让当前表面得到确认。"
            frame.meanLuma < SurfaceCoverageUseCase.MIN_LUMA -> "画面太暗，仍需补拍；请开灯。"
            frame.detailVariance < SurfaceCoverageUseCase.MIN_DETAIL -> "细节不足；请停稳并对准有纹理的物体补拍。"
            frame.meanLuma < 45f -> "已记录照片，但偏暗；建议补光。绿色表示两个站位已拍过。"
            else -> "黄色区域请换一个位置拍摄，平移至少约 20cm。绿色表示双视角已记录。"
        }
        frameMessage = message
        publish()
        val finished = captures.value.phase in setOf(CapturePhase.PAUSED,CapturePhase.CAPTURED)
        if (finished || processStart-lastPersistNanos >= 1_000_000_000L) persist(frame)
        val (total,recorded,multi) = verifiedProgress.counts()
        val end = SystemClock.elapsedRealtimeNanos()
        Log.i(TAG, "COVERAGE_FRAME ${frame.file} changed=$changed total=$total recorded=$recorded multi=$multi luma=${frame.meanLuma} detail=${frame.detailVariance} dirty_anchors=${dirty.size} process_ms=${(end-processStart)/1_000_000} lag_ms=${(end-frame.receivedNanos)/1_000_000}")
    }

    private fun changeSession(path: String) {
        currentSession = path
        lastFrame = null
        lastPersistNanos = 0
        coverage.resetRecords()
        verifiedProgress.resetPhotos()
        // Reset photographic evidence only; retain the measured geometry and its revealed extent.
        val blue=material(SurfaceRecord.UNRECORDED)
        anchors.values.forEach { entry -> entry.groups.values.forEach { group ->
            group.entity.components[ModelComponent::class.java]?.materials?.set(0,blue)
            group.materialStatus=SurfaceRecord.UNRECORDED
        } }
        frameMessage = if (path.isEmpty()) "当前扫描已清空，可以重新开始。" else null
        anchors.values.forEach { render(it) }
        publish()
        Log.i(TAG,"COVERAGE_RESET session=$path geometry_retained=${anchors.isNotEmpty()} epoch=$geometryEpoch visible=${reveal.visibleCount()} recorded=0 multi=0")
    }

    private fun loadCurrentAnchors() {
        loadJob?.cancel()
        val epoch=geometryEpoch
        val token=trackingToken
        val began=SystemClock.elapsedRealtimeNanos()
        val cachedPlanes=anchors.filterValues { it.plane }.keys.toSet()
        val cachedMeshes=anchors.filterValues { !it.plane }.keys.toSet()
        loadJob=scope.launch {
            launch {
                try {
                    val data=withTimeout(10_000) { PlaneTrackingManager.loadAllAnchors() }
                    currentCoroutineContext().ensureActive()
                    if(epoch==geometryEpoch && started) {
                        val loaded=data.map { planeId(it.anchorUUID) }.toSet()
                        reconcile.removed(cachedPlanes,loaded,lastNativeEvents,began).forEach(::remove)
                        data.filter { admission.acceptsSnapshot(planeId(it.anchorUUID),token) && reconcile.acceptLoaded(planeId(it.anchorUUID),lastNativeEvents,began) }
                            .forEach { queue(it,epoch=epoch) }
                    }
                } catch(e:CancellationException) { throw e } catch(e:Exception) { reportError(e) }
            }
            try {
                val data=withTimeout(10_000) { MeshTrackingManager.loadAllAnchors() }
                currentCoroutineContext().ensureActive()
                if(epoch==geometryEpoch && started) {
                    val loaded=data.map { it.anchorUUID }.toSet()
                    reconcile.removed(cachedMeshes,loaded,lastNativeEvents,began).forEach(::remove)
                    data.filter { admission.acceptsSnapshot(it.anchorUUID,token) && reconcile.acceptLoaded(it.anchorUUID,lastNativeEvents,began) }
                        .forEach { queue(it,epoch=epoch) }
                }
            } catch(e:CancellationException) { throw e } catch(e:Exception) { reportError(e) }
        }
    }

    private fun clearMeasuredGeometry() {
        // Reject a read started before this scan, even if its callback completes after reset.
        minDepthAcquisitionNanos=SystemClock.elapsedRealtimeNanos()+150_000_000L
        liveDepth.discardBefore(minDepthAcquisitionNanos);depthQueryCache.clear()
        admission.reset()
        meshSubscription?.cancel();meshSubscription=null
        planeSubscription?.cancel();planeSubscription=null
        // Keep native tracking continuous: restarting it replays the existing system map.
        // This reset owns only a new application working set and subscription epoch.
        geometryEpoch++
        observationRevision++;planeRevision++
        loadJob?.cancel()
        pendingAnchors.clear();pendingRemovals.clear();removedAnchors.clear()
        lastNativeEvents.clear()
        nativeEventCounts.clear()
        renderMailbox.clear();renderRequestedNanos.clear()
        val removed=anchors.size
        // Retire the complete attached hierarchy, not just children listed in the cache.
        // The panel has a separate root and remains attached.
        val retirement=displayRoot.replace(visible && started)
        anchors.values.forEach { e -> e.groups.values.forEach { it.mesh.close() } }
        anchors.clear();cellOwners=emptyMap()
        coverage.clearMeasuredSurfaces();reveal.clearMeasuredSurfaces()
        verifiedProgress.clear();depthVisibility.clear()
        observedPoints=emptyMap();drawOccludedKeys=emptySet()
        occludedKeys=emptySet();occlusionOrigin=null;occlusionRevision=-1L
        lastOcclusionNanos=0L;lastOcclusionMillis=0L
        lastFrame=null;lastPersistNanos=0
        acceptedMeshUpdates=0;receivedMeshUpdates=0;deferredMeshUpdates=0
        frameMessage="网格和当前照片已清空；观察房间，重新生成蓝色网格。"
        publish()
        Log.i(TAG,"GRID_RESET epoch=$geometryEpoch removed_anchors=$removed anchors=0 cells=0 visible=0 pending=0 native_restarted=false bulk_load=false mesh_state=${MeshTrackingManager.state} plane_state=${PlaneTrackingManager.state}")
        Log.i(TAG,"GRID_ROOT_REPLACED old=${retirement.rootId} new=${root.id} descendants=${retirement.descendants} models=${retirement.models} destroyed=${retirement.destroyed} remaining=${retirement.remainingIds}")
        if(started && !disposed) {
            subscribeGeometry()
        }
    }

    private suspend fun persist(frame: SavedFrame) {
        persistenceMutex.withLock {
        if (frame.sessionPath != captures.value.sessionPath) return
        val (total,recorded,multi) = verifiedProgress.counts()
        val snapshot = verifiedProgress.records()
        withContext(Dispatchers.IO) {
            if (frame.sessionPath != captures.value.sessionPath) return@withContext
            val records = JSONArray()
            snapshot.forEach { (point,status) -> records.put(JSONObject()
                .put("position",JSONArray(listOf(point.x,point.y,point.z))).put("state",status.name)) }
            val json = JSONObject().put("schema","room-splat.coverage/1")
                .put("source","PICO measured planes + subdivided mesh + conservative HMD arrival-time reference")
                .put("rgb_alignment_verified",false).put("cell_size_m",SurfaceCoverageUseCase.CELL_SIZE_M)
                .put("observed_cells",total).put("recorded_cells",recorded).put("multi_position_cells",multi)
                .put("green_meaning", "two_distinct_recorded_viewpoints; reconstruction_quality_not_guaranteed")
                .put("green_required_stations", SurfaceCoverageUseCase.REQUIRED_STATIONS)
                .put("green_min_luma", SurfaceCoverageUseCase.MIN_LUMA).put("green_min_detail", SurfaceCoverageUseCase.MIN_DETAIL)
                .put("latest_frame",frame.file).put("cells",records)
            val temporary=File(frame.sessionPath,"coverage.json.partial")
            try {
                temporary.writeText(json.toString())
                if (frame.sessionPath != captures.value.sessionPath) { temporary.delete(); return@withContext }
                check(temporary.renameTo(File(frame.sessionPath,"coverage.json"))) { "覆盖清单写入失败。" }
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                if (frame.sessionPath == captures.value.sessionPath) throw error
            }
        }
        lastPersistNanos=SystemClock.elapsedRealtimeNanos()
        }
    }


    private suspend fun rebuildObserved() {
        // Geometry snapshots are immutable; background indexing never reads or mutates the live evidence.
        while(started && !disposed) {
            val revision=observationRevision
            val planeVersion=planeRevision
            val entries=anchors.toMap()
            val sources=entries.map { (id,e) -> SurfaceOverlapUseCase.Surface(id,e.worldPositions,e.indices,e.planeSupport,e.kind,e.triangleKinds) }
            val snapshot=entries.map { (id,e) -> ObservedSurfacePlanUseCase.Anchor(id,e.centres,e.cellKeys) }
            val cachedMasks=entries.filterValues { it.maskGeometryRevision==it.geometryRevision && it.maskPlaneRevision==planeVersion }
                .mapValues { SurfaceOverlapUseCase.Display(it.value.suppressed,it.value.fragments) }
            val (displays,plan,drawMeshes)=withContext(Dispatchers.Default) {
                val displays=traced("RoomSplat.SurfaceOverlap") { overlap.buildDisplay(sources,cachedMasks) }
                val drawMeshes=entries.mapValues { (id,e) ->
                    val d=checkNotNull(displays[id])
                    if(d.fragments.isEmpty())null else if(e.fragments==d.fragments)e.displayMesh
                    else ClippedSurfaceMeshUseCase().build(e.positions.map { Point3(it.x,it.y,it.z) },e.indices,d.fragments)
                }
                val supported=snapshot.map { a ->
                    val mask=checkNotNull(displays[a.id]).suppressed
                    ObservedSurfacePlanUseCase.Anchor(a.id,a.points.filterIndexed { i,_ -> !mask[i] },
                        a.keys.filterIndexed { i,_ -> !mask[i] })
                }
                Triple(displays,traced("RoomSplat.ObservedPlan") { observedPlans.build(supported) },drawMeshes)
            }
            val committed=revealStateMutex.withLock {
                val revealPlan=withContext(Dispatchers.Default) {
                    traced("RoomSplat.RevealPlan") { reveal.prepareObservedKeyed(plan.points) }
                }
                if(!started || disposed || revision!=observationRevision)return@withLock false
                if(!traced("RoomSplat.RevealCommit") { revealPlan.commit() })return@withLock false
                traced("RoomSplat.ObservedApply") {
                entries.forEach { (id,e) ->
                    val display=checkNotNull(displays[id]);val mask=display.suppressed
                    if(!e.suppressed.contentEquals(mask) || e.fragments!=display.fragments) {
                        e.suppressed=mask
                        e.fragments=display.fragments;e.displayMesh=drawMeshes[id];e.displayRevision++
                        render(e,geometryChanged=true,prioritize=e.plane)
                    }
                    e.maskGeometryRevision=e.geometryRevision
                    e.maskPlaneRevision=planeVersion
                }
                coverage.replaceObservedKeyed(plan.points)
                val restored=revealPlan.restoredKeys
                restored.flatMap { plan.owners[it].orEmpty() }.toSet().forEach { id ->
                    anchors[id]?.let { render(it,prioritize=true) }
                }
                val progress=reveal.lastObservation
                Log.i(TAG,"REVEAL_RECONCILE retained=${progress.retained} restored_exact=${progress.restoredExact} restored_nearby=${progress.restoredNearby} removed_visible=${progress.removedVisible} history=${progress.historySize} revision=$revision")
                observedPoints=plan.points
                depthVisibility.retain(plan.points.keys)
                cellOwners=plan.owners
                }
                true
            }
            if(!started || disposed)return
            if(!committed)continue
            Log.i(TAG,"PLANE_COVERAGE planes=${sources.count { it.plane!=null }} suppressed=${displays.values.sumOf { d -> d.suppressed.count { it } }} clipped=${displays.values.sumOf { it.fragments.size }} cells=${plan.points.size} wall_plane_policy=all_eligible_junctions")
            return
        }
    }

    private suspend fun updateReveal() {
        val now = SystemClock.elapsedRealtimeNanos()
        val pose=cache.nearest(now)?:return
        if(kotlin.math.abs(now-pose.receivedNanos)>100_000_000L)return
        val revision=observationRevision
        val epoch=geometryEpoch
        val points=observedPoints
        val frame=liveDepth.frame
        val depthFloor=minDepthAcquisitionNanos
        val result=withContext(Dispatchers.Default) {
            traced("RoomSplat.LiveDepthAdmission") {
                if(depthAdmission.usable(frame,pose,now,depthFloor))depthQueryCache.evaluate(points,frame!!)
                else LiveDepthQueryCacheUseCase.Result(points.keys,emptyMap())
            }
        }
        if(!started || disposed || epoch!=geometryEpoch || revision!=observationRevision ||
            currentScanRevision!=captures.value.scanRevision)return
        val checkedAt=SystemClock.elapsedRealtimeNanos()
        val currentPose=cache.nearest(checkedAt)
        // A head turn while the worker queried points must not admit using the old view.
        val usable=currentPose!=null && depthAdmission.usable(frame,currentPose,checkedAt,minDepthAcquisitionNanos)
        occludedKeys=if(usable)result.blocked else points.keys
        val drawChanged=if(currentPose!=null && depthAdmission.drawUsable(frame,currentPose,checkedAt,minDepthAcquisitionNanos))
            depthVisibility.update(result.visibility,frame!!.receivedNanos) else { depthVisibility.pause();emptySet() }
        drawOccludedKeys=depthVisibility.hiddenKeys();lastOcclusionNanos=checkedAt
        lastOcclusionMillis=(checkedAt-now)/1_000_000;occlusionOrigin=pose.position;occlusionRevision=revision
        if(checkedAt-lastDepthAdmissionLog>=2_000_000_000L) {
            lastDepthAdmissionLog=checkedAt
            Log.i(TAG,"DEPTH_ADMISSION cells=${points.size} matched=${points.size-occludedKeys.size} fresh=$usable query_ms=${(checkedAt-now)/1_000_000} cache_hits=${depthQueryCache.hits} cache_builds=${depthQueryCache.builds} prepared_pixels=${frame?.bands?.preparedPixels ?: 0} retained_progress=true depth_hidden=${drawOccludedKeys.size} draw_changed=${drawChanged.size} progress=${verifiedProgress.counts()} epoch=$epoch")
            publish()
        }
        val allowReveal=captures.value.phase !in setOf(CapturePhase.PAUSED,CapturePhase.CAPTURED,CapturePhase.PACKING,CapturePhase.EXPORTED)
        val changed = reveal.update(currentPose,checkedAt,occludedKeys,allowReveal,maxNewCells=128)
        verifiedProgress.discover(changed.mapNotNull { key->observedPoints[key]?.let { key to it } }.toMap())
        if (changed.isEmpty() && drawChanged.isEmpty()) return
        publish()
        val owners = (changed+drawChanged).flatMap { cellOwners[it].orEmpty() }.toSet()
        owners.forEach { id ->
            anchors[id]?.let { render(it,prioritize=true) }
            yield()
        }
        Log.i(TAG, "MESH_REVEAL changed=${changed.size} visible=${reveal.visibleCount()} dirty_anchors=${owners.size} newly_revealed=${changed.size} hidden=0 history=${reveal.historyCount()} sample_nanos=$lastOcclusionNanos centre_to_edge=true")
    }

    private fun remove(id: UUID,receivedNanos:Long=SystemClock.elapsedRealtimeNanos()) {
        // Native providers also send updates for unsupported planes. Never start a background
        // full-room rebuild per callback: one geometry worker owns indexing and deduplication.
        lastNativeEvents[id]=receivedNanos
        removedAnchors.add(id)
        if(id !in anchors && id !in pendingAnchors)return
        pendingAnchors.remove(id)
        pendingRemovals.add(id)
    }
    private fun removeEntry(id: UUID) {
        observationRevision++
        renderRequestedNanos.remove(id)
        renderMailbox.remove(id)
        removedAnchors.add(id)
        pendingAnchors.remove(id)
        anchors.remove(id)?.let { entry ->
            if(entry.plane)planeRevision++
            entry.groups.values.forEach { it.entity.destroy(); it.mesh.close() }; entry.owner.destroy()
        }
    }
    fun setVisible(value: Boolean) {
        if(disposed)return
        visible = value
        displayRoot.setVisible(value && started)
        Log.i(TAG,"COVERAGE_VISIBILITY requested=$value started=$started display=${displayRoot.diagnostic()}")
    }
    private fun publish() {
        val message = liveDepth.message ?: frameMessage ?: "蓝：待拍摄 · 黄：需要补拍 · 绿：双视角已记录"
        val (total, recorded, multi) = verifiedProgress.counts()
        mutable.value = CoverageSummary(total, recorded, multi, message, anchors.isNotEmpty())
    }
    private fun reportError(error: Exception) { mutable.value = mutable.value.copy(message = "空间覆盖暂不可用：${error.message}"); Log.e(TAG, "COVERAGE_ERROR", error) }
    fun pause() {
        started = false
        liveDepth.stop()
        depthQueryCache.clear()
        depthVisibility.pause()
        displayRoot.setVisible(false)
        Log.i(TAG,"COVERAGE_PAUSED root=${root.id}")
        admission.pause()
        meshSubscription?.cancel(); meshSubscription = null
        planeSubscription?.cancel();planeSubscription=null
        poseJob?.cancel(); frameJob?.cancel(); meshJob?.cancel(); revealJob?.cancel(); loadJob?.cancel(); sessionJob?.cancel();renderJob?.cancel()
        pendingAnchors.clear()
        pendingRemovals.clear()
        runCatching { hmd.stop() }; runCatching { MeshTrackingManager.stop() };runCatching { PlaneTrackingManager.stop() }; cache.clear()
    }
    fun close() {
        if (disposed) return
        activeScene.release(this)
        disposed = true
        runCatching { pause() }
        scope.cancel()
        renderMailbox.clear()
        renderRequestedNanos.clear()
        val retirement=displayRoot.close()
        anchors.values.forEach { entry -> entry.groups.values.forEach { it.mesh.close() } }
        anchors.clear()
        Log.i(TAG,"GRID_ROOT_CLOSED old=${retirement.rootId} destroyed=${retirement.destroyed} remaining=${retirement.remainingIds}")
        materials.values.forEach { it.close() }; materials.clear()
    }

    private inline fun <T> traced(name:String,block:()->T):T {
        Trace.beginSection(name)
        return try { block() } finally { Trace.endSection() }
    }
    companion object {
        private val activeScene=ActiveSceneOwnerUseCase<CoverageSceneController>()
        private const val TAG = "RoomSplatCoverage"
    }
}
