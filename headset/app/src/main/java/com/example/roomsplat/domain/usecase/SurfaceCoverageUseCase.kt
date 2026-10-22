package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import com.example.roomsplat.domain.model.SavedFrame
import com.example.roomsplat.domain.model.SurfaceRecord
import kotlin.math.acos
import kotlin.math.floor

/** Conservative head-centred reference coverage. No calibrated RGB footprint is claimed. */
class SurfaceCoverageUseCase {
    private data class Cell(val point: Point3, val key: String, var attempted: Boolean = false, val stations: MutableList<Point3> = mutableListOf())
    private val cells = mutableMapOf<String, Cell>()
    private val frameIds = mutableSetOf<String>()
    var lastChangedKeys: Set<String> = emptySet(); private set
    fun clearMeasuredSurfaces() { cells.clear();frameIds.clear();lastChangedKeys=emptySet() }
    fun key(point: Point3): String = "${floor(point.x / CELL_SIZE_M).toInt()}:${floor(point.y / CELL_SIZE_M).toInt()}:${floor(point.z / CELL_SIZE_M).toInt()}"
    fun observe(point: Point3) { cells.getOrPut(key(point)) { Cell(point, key(point)) } }
    fun replaceObserved(points: List<Point3>) {
        replaceObservedKeyed(points.associateBy(::key))
    }
    fun replaceObservedKeyed(active: Map<String,Point3>) {
        cells.keys.retainAll(active.keys)
        active.forEach { (key,point) -> cells.getOrPut(key) { Cell(point,key) } }
    }
    fun resetRecords() { cells.values.forEach { it.stations.clear(); it.attempted = false }; frameIds.clear(); lastChangedKeys = emptySet() }
    fun status(point: Point3): SurfaceRecord = statusKey(key(point))
    fun statusKey(key: String): SurfaceRecord = cells[key]?.let(::stateOf) ?: SurfaceRecord.UNRECORDED
    private fun stateOf(cell: Cell): SurfaceRecord {
        return when { cell.stations.size >= REQUIRED_STATIONS -> SurfaceRecord.MULTIPLE_POSITIONS; cell.attempted -> SurfaceRecord.ONE_POSITION; else -> SurfaceRecord.UNRECORDED }
    }
    fun counts(): Triple<Int, Int, Int> = Triple(cells.size, cells.values.count { it.attempted }, cells.values.count { it.stations.size >= REQUIRED_STATIONS })
    fun records(): List<Pair<Point3, SurfaceRecord>> = cells.values.map { it.point to stateOf(it) }
    fun record(frame: SavedFrame,eligible:Set<String>?=null): Int {
        lastChangedKeys = emptySet()
        val pose = frame.headPose ?: return 0
        if (kotlin.math.abs(frame.receivedNanos - pose.receivedNanos) > 60_000_000L) return 0
        val qualityAccepted = frame.meanLuma >= MIN_LUMA && frame.detailVariance >= MIN_DETAIL
        if (!frameIds.add(frame.file)) return 0
        // A narrow reference cone reduces uncertain edges without claiming a camera calibration.
        val projected = mutableListOf<Triple<Cell, Pair<Int, Int>, Float>>()
        val nearest = mutableMapOf<Pair<Int, Int>, Float>()
        for (cell in cells.values) {
            if(eligible!=null && cell.key !in eligible)continue
            val delta = cell.point - pose.position
            val depth = delta.dot(pose.forward)
            if (depth !in .4f..6f) continue
            val horizontal = delta.dot(pose.right) / depth
            val vertical = delta.dot(pose.up) / depth
            if (kotlin.math.abs(horizontal) > .466f || kotlin.math.abs(vertical) > .364f) continue
            val bin = Pair(floor(horizontal / .025f).toInt(), floor(vertical / .025f).toInt())
            nearest[bin] = minOf(nearest[bin] ?: Float.POSITIVE_INFINITY, depth)
            projected.add(Triple(cell, bin, depth))
        }
        val changed = mutableSetOf<String>()
        for ((cell, bin, depth) in projected) {
            if (depth > nearest.getValue(bin) + CELL_SIZE_M * 2) continue
            val before = stateOf(cell)
            cell.attempted = true
            if (qualityAccepted && cell.stations.size < REQUIRED_STATIONS) {
                val distinct = cell.stations.all { previous ->
                    val baseline = (pose.position - previous).length()
                    val a = cell.point - previous
                    val b = cell.point - pose.position
                    val cosine = (a.dot(b) / (a.length() * b.length())).coerceIn(-1f, 1f)
                    baseline >= MIN_BASELINE_M && acos(cosine) >= MIN_VIEW_ANGLE_RADIANS
                }
                if (distinct) cell.stations.add(pose.position)
            }
            if (before != stateOf(cell)) changed.add(cell.key)
        }
        lastChangedKeys = changed
        return changed.size
    }
    companion object {
        const val CELL_SIZE_M = .08f
        const val REQUIRED_STATIONS = 2
        const val MIN_LUMA = 30f
        const val MIN_DETAIL = 6f
        const val MIN_BASELINE_M = .20f
        const val MIN_VIEW_ANGLE_RADIANS = .05f
    }
}
