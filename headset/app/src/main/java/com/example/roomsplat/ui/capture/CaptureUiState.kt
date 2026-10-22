package com.example.roomsplat.ui.capture

import com.example.roomsplat.domain.model.CaptureSnapshot

data class CaptureUiState(
    val capture: CaptureSnapshot = CaptureSnapshot(),
    val permissionGranted: Boolean = false,
    val busy: Boolean = false,
    val showFinishConfirmation: Boolean = false,
    val showResetConfirmation: Boolean = false,
    val error: String = "",
)

sealed interface CaptureEvent {
    data class PermissionResult(val granted: Boolean) : CaptureEvent
    data object Start : CaptureEvent
    data object Pause : CaptureEvent
    data object Resume : CaptureEvent
    data object RequestFinish : CaptureEvent
    data object CancelFinish : CaptureEvent
    data object ConfirmFinish : CaptureEvent
    data object Pack : CaptureEvent
    data object NewSession : CaptureEvent
    data object RequestReset : CaptureEvent
    data object CancelReset : CaptureEvent
    data object ConfirmReset : CaptureEvent
    data object HostPaused : CaptureEvent
}
