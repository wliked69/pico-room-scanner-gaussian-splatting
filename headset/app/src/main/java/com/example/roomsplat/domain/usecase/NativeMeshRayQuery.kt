package com.example.roomsplat.domain.usecase

/** Pure geometry JNI backend. JVM tests use the Kotlin reference; device tests require native. */
internal object NativeMeshRayQuery {
    val available: Boolean = try {
        System.loadLibrary("roomsplat_rays")
        true
    } catch (_: UnsatisfiedLinkError) { false }

    @JvmStatic external fun queryNative(
        triangles: FloatArray, ox: Float, oy: Float, oz: Float,
        targets: FloatArray, targetPlaneBias: Float,
    ): BooleanArray
}
