package com.example.roomsplat.domain.model

data class Point3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(other: Point3) = Point3(x - other.x, y - other.y, z - other.z)
    fun dot(other: Point3) = x * other.x + y * other.y + z * other.z
    fun length() = kotlin.math.sqrt(dot(this))
}

data class HeadPoseEvidence(
    val receivedNanos: Long,
    val position: Point3,
    val forward: Point3,
    val right: Point3,
    val up: Point3,
    val quaternion: List<Float>,
)

data class SavedFrame(
    val sessionPath: String,
    val file: String,
    val receivedNanos: Long,
    val meanLuma: Float,
    val detailVariance: Float,
    val headPose: HeadPoseEvidence?,
)

enum class SurfaceRecord { UNRECORDED, ONE_POSITION, MULTIPLE_POSITIONS }

data class CoverageSummary(
    val observedCells: Int = 0,
    val recordedCells: Int = 0,
    val multiPositionCells: Int = 0,
    val message: String = "正在发现房间表面…",
    val available: Boolean = false,
)
