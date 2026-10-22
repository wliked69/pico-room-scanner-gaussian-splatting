package com.example.roomsplat.domain.model

enum class CapturePhase { CHECKING, READY, RECORDING, PAUSED, CAPTURED, PACKING, EXPORTED, UNAVAILABLE, ERROR }

data class CaptureSnapshot(
    val phase: CapturePhase = CapturePhase.CHECKING,
    val frames: Int = 0,
    val activeMillis: Long = 0,
    val segments: List<Int> = emptyList(),
    val cameraId: String = "",
    val sessionPath: String = "",
    val archivePath: String = "",
    val message: String = "",
    val scanRevision: Long = 0,
)
