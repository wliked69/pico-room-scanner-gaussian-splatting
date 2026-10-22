package com.example.roomsplat.ui.capture

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.roomsplat.data.repository.CameraCaptureRepository
import com.example.roomsplat.data.repository.CaptureRepository
import com.example.roomsplat.data.repository.HeadPoseCache
import com.example.roomsplat.domain.model.CapturePhase
import com.example.roomsplat.domain.usecase.CanExportUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class CaptureViewModel(
    private val repository: CaptureRepository,
    private val canExport: CanExportUseCase = CanExportUseCase(),
    val headPoseCache: HeadPoseCache = HeadPoseCache(),
    private val report: (String) -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow(CaptureUiState())
    val state = mutable.asStateFlow()
    val savedFrames = repository.savedFrames
    val captureSnapshots = repository.snapshot
    private val commands = Mutex()
    private var pendingCommands = 0

    init {
        viewModelScope.launch { repository.snapshot.collect { capture -> mutable.update { it.copy(capture = capture) } } }
    }

    fun onEvent(event: CaptureEvent) {
        report("UI_EVENT event=$event busy=${state.value.busy} reset_confirmation=${state.value.showResetConfirmation}")
        if (event == CaptureEvent.Pause || event == CaptureEvent.HostPaused) repository.requestPause()
        if (event == CaptureEvent.CancelReset) {
            mutable.update { it.copy(showResetConfirmation = false) }
            return
        }
        if (event == CaptureEvent.RequestReset) {
            if (!state.value.busy) mutable.update { it.copy(showResetConfirmation = true, showFinishConfirmation = false, error = "") }
            return
        }
        if (event == CaptureEvent.CancelFinish) {
            mutable.update { it.copy(showFinishConfirmation = false) }
            return
        }
        if (event == CaptureEvent.RequestFinish) {
            if (!canExport(state.value.capture.frames)) {
                mutable.update { it.copy(error = "至少采集 3 张照片；房间建议 200 张以上。") }
            } else mutable.update { it.copy(showFinishConfirmation = true, error = "") }
            return
        }
        if (state.value.busy && event !in setOf(CaptureEvent.HostPaused, CaptureEvent.Pause)) return
        // Accept confirmation at the click boundary, before dialog dismissal or other events.
        if (event == CaptureEvent.ConfirmReset) {
            if (!state.value.showResetConfirmation) {
                mutable.update { it.copy(error = "请先确认重置当前扫描。") }
                return
            }
            mutable.update { it.copy(showResetConfirmation = false, showFinishConfirmation = false) }
        }
        pendingCommands++
        mutable.update { it.copy(busy = true, error = "") }
        viewModelScope.launch {
            commands.withLock {
                try {
                    when (event) {
                        is CaptureEvent.PermissionResult -> {
                            mutable.update { it.copy(permissionGranted = event.granted) }
                            withTimeout(20_000) { repository.check(event.granted) }
                        }
                        CaptureEvent.Start -> withTimeout(20_000) { repository.start() }
                        CaptureEvent.Pause, CaptureEvent.HostPaused -> withTimeout(5_000) { repository.pause() }
                        CaptureEvent.Resume -> withTimeout(20_000) { repository.resume() }
                        CaptureEvent.ConfirmFinish -> {
                            check(canExport(state.value.capture.frames)) { "照片不足，继续采集。" }
                            mutable.update { it.copy(showFinishConfirmation = false) }
                            repository.finish()
                        }
                        CaptureEvent.Pack -> repository.pack()
                        CaptureEvent.NewSession -> repository.newSession()
                        CaptureEvent.ConfirmReset -> {
                            repository.resetCurrentSession()
                        }
                        else -> Unit
                    }
                    report("UI_COMMAND_COMPLETE event=$event phase=${repository.snapshot.value.phase} frames=${repository.snapshot.value.frames}")
                } catch (error: TimeoutCancellationException) {
                    report("UI_COMMAND_TIMEOUT event=$event")
                    mutable.update { it.copy(error = if (event in setOf(CaptureEvent.Pause, CaptureEvent.HostPaused))
                        "拍照已停止，相机释放较慢。请稍后重试。" else "相机启动超时，检查设备权限后重试。") }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    report("UI_COMMAND_FAILED event=$event error=${error.javaClass.simpleName}: ${error.message}")
                    mutable.update { it.copy(error = error.message ?: "操作未完成，照片保留。") }
                } finally {
                    pendingCommands--
                    mutable.update { it.copy(busy = pendingCommands > 0) }
                }
            }
        }
    }

    override fun onCleared() { repository.close() }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val cache = HeadPoseCache()
                return CaptureViewModel(CameraCaptureRepository(context.applicationContext, cache), headPoseCache = cache,
                    report = { android.util.Log.i("RoomSplatUi", it) }) as T
            }
        }
    }
}
