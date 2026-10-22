package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.HeadPoseEvidence
import com.example.roomsplat.domain.model.Point3

/** Reveal actual measured surfaces from the gaze centre outward. Revealed extent is retained
 * for this scan, independent of later unreliable occlusion masks. No geometry is invented.
 */
class SurfaceRevealUseCase(private val keyOf:(Point3)->String,private val historyCapacity:Int=80_000,
    private val spreadNanos:Long=700_000_000L) {
    private data class Cell(var point:Point3,var due:Long=Long.MAX_VALUE,var visible:Boolean=false)
    data class ObservationStats(val retained:Int=0,val restoredExact:Int=0,val restoredNearby:Int=0,
        val removedVisible:Int=0,val historySize:Int=0)
    private data class State(val cells:MutableMap<String,Cell>,val history:RevealedSurfaceHistoryUseCase)
    private fun emptyState()=State(mutableMapOf(),RevealedSurfaceHistoryUseCase(historyCapacity))
    @Volatile private var state=emptyState()
    @Volatile private var revision=0L
    var lastObservation=ObservationStats();private set

    fun clearMeasuredSurfaces() { state=emptyState();revision++;lastObservation=ObservationStats() }
    fun historyCount():Int=state.history.size
    fun replaceObserved(points:List<Point3>) { replaceObservedKeyed(points.associateBy(keyOf)) }

    class PreparedObservation internal constructor(val restoredKeys:Set<String>,private val publish:()->Boolean) {
        /** Main-thread commit. False means reset/newer state invalidated this result. */
        fun commit():Boolean=publish()
    }

    /** The returned restored keys need a render even if their new provider has no photo change. */
    fun replaceObservedKeyed(active:Map<String,Point3>):Set<String> {
        val prepared=prepareObservedKeyed(active)
        check(prepared.commit()) { "Observation state changed during synchronous reconciliation" }
        return prepared.restoredKeys
    }

    /** Worker preparation for the serialized geometry worker. update() must not run concurrently.
     * The renderer can read the old committed cells; Main reset swaps the whole state instead of
     * clearing maps a worker might still be reading. Nothing here changes the published state.
     */
    fun prepareObservedKeyed(active:Map<String,Point3>):PreparedObservation {
        val base=state;val baseRevision=revision
        val removed=base.cells.count { (key,cell) -> cell.visible && key !in active }
        val next=mutableMapOf<String,Cell>()
        var retained=0;var exact=0;var nearby=0
        val restored=mutableSetOf<String>()
        active.forEach { (key,point) ->
            val previous=base.cells[key]
            if(previous!=null) {
                next[key]=previous.copy(point=point)
                if(previous.visible)retained++
            } else {
                val match=base.history.match(key,point)
                next[key]=Cell(point,visible=match!=null)
                if(match!=null) {
                    restored.add(key)
                    if(match==RevealedSurfaceHistoryUseCase.Match.EXACT)exact++ else nearby++
                }
            }
        }
        val statistics=ObservationStats(retained,exact,nearby,removed,base.history.size)
        return PreparedObservation(restored) {
            if(state!==base || revision!=baseRevision)false else {
                state=State(next,base.history);revision++;lastObservation=statistics;true
            }
        }
    }
    fun isVisible(point:Point3):Boolean=isVisibleKey(keyOf(point))
    fun isVisibleKey(key:String):Boolean=state.cells[key]?.visible==true
    fun visibleCount():Int=state.cells.values.count { it.visible }

    fun update(pose:HeadPoseEvidence?,now:Long,blocked:Set<String> = emptySet(),
        allowReveal:Boolean=true,maxNewCells:Int=Int.MAX_VALUE):Set<String> {
        if(pose==null || kotlin.math.abs(now-pose.receivedNanos)>100_000_000L)return emptySet()
        revision++
        val current=state
        val changed=mutableSetOf<String>()
        val ready=mutableListOf<Pair<String,Cell>>()
        current.cells.forEach { (key,cell) ->
            // This is retained scan progress, not a continuously reclassified visibility mask.
            if(cell.visible)return@forEach
            if(key in blocked || !allowReveal) { cell.due=Long.MAX_VALUE;return@forEach }
            val delta=cell.point-pose.position
            val depth=delta.dot(pose.forward)
            if(depth in .25f..8f) {
                val h=delta.dot(pose.right)/depth/1.4f
                val v=delta.dot(pose.up)/depth
                val radiusSquared=h*h+v*v
                if(kotlin.math.abs(h)<=1f && kotlin.math.abs(v)<=1f)
                    cell.due=minOf(cell.due,now+(minOf(radiusSquared,1f)*spreadNanos).toLong())
            }
            if(cell.due<=now)ready.add(key to cell)
        }
        // Spread late-arriving geometry across short refreshes instead of releasing a full wall.
        ready.sortedBy { it.second.due }.take(maxNewCells.coerceAtLeast(0)).forEach { (key,cell)->
            cell.visible=true;current.history.remember(key,cell.point);changed.add(key)
        }
        return changed
    }
}
