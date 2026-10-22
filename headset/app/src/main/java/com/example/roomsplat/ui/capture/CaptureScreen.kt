package com.example.roomsplat.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.example.roomsplat.ui.theme.RoomSplatColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.roomsplat.domain.model.CapturePhase
import com.example.roomsplat.domain.model.CoverageSummary
import com.example.roomsplat.ui.capture.components.CaptureAction
import com.example.roomsplat.ui.capture.components.CapturePanel
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

@Composable
fun CaptureScreen(modifier: Modifier = Modifier, suppliedViewModel: CaptureViewModel? = null,
    coverage: CoverageSummary? = null, coverageVisible: Boolean = true, onCoverageToggle: (Boolean) -> Unit = {},
    panelStatus: String = "", followHand: Boolean = true, onFollowHandToggle: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) { CaptureViewModel.factory(context.applicationContext) }
    val vm: CaptureViewModel = suppliedViewModel ?: viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onEvent(CaptureEvent.PermissionResult(it))
    }
    DisposableEffect(owner) {
        fun checkPermission() = vm.onEvent(CaptureEvent.PermissionResult(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) vm.onEvent(CaptureEvent.HostPaused)
            if (event == Lifecycle.Event.ON_RESUME) checkPermission()
        }
        owner.lifecycle.addObserver(observer)
        checkPermission()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    CaptureContent(state, vm::onEvent, { permission.launch(Manifest.permission.CAMERA) }, modifier, coverage, coverageVisible, onCoverageToggle, panelStatus, followHand, onFollowHandToggle)
}


@Composable
internal fun CaptureContent(state: CaptureUiState, onEvent: (CaptureEvent) -> Unit, requestPermission: () -> Unit, modifier: Modifier = Modifier,
    coverage: CoverageSummary? = null, coverageVisible: Boolean = true, onCoverageToggle: (Boolean) -> Unit = {},
    panelStatus: String = "", followHand: Boolean = true, onFollowHandToggle: (Boolean) -> Unit = {}) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (panelStatus.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column {
                Text(panelStatus, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.titleMedium)
                Text("跟随左手", color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodyMedium)
            }
            com.pico.spatial.ui.design.Switch(checked = followHand, onCheckedChange = onFollowHandToggle)
        }
        // AttachmentPanel owns this confirmation, so it shares the panel's hit testing and anchor.
        if (state.showResetConfirmation || state.showFinishConfirmation) {
            val reset = state.showResetConfirmation
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (reset) "重置当前扫描？" else "结束这次采集？",
                    color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.headlineLarge)
                Text(if (reset) "清空 ${state.capture.frames} 张照片、网格和覆盖记录，再生成蓝色网格。已打包的 ZIP 和 Windows 数据保留。"
                    else "已保存 ${state.capture.frames} 张照片。结束后可以打包并交给 Windows 重建。",
                    color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodyLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CaptureAction(if (reset) "取消" else "继续拍摄", !state.busy, {
                    onEvent(if (reset) CaptureEvent.CancelReset else CaptureEvent.CancelFinish)
                })
                CaptureAction(if (reset) "确认重置" else "结束采集", !state.busy, {
                    onEvent(if (reset) CaptureEvent.ConfirmReset else CaptureEvent.ConfirmFinish)
                }, true)
            }
        } else {
            if (state.error.isNotEmpty()) Text(state.error, color = RoomSplatColors.Error,
                style = PicoTheme.typography.bodyLarge)
            CapturePanel(state.capture, "", Modifier.weight(1f), coverage, coverageVisible, onCoverageToggle)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                when (state.capture.phase) {
                    CapturePhase.RECORDING -> {
                        CaptureAction("暂停", true, { onEvent(CaptureEvent.Pause) })
                        CaptureAction("完成采集", !state.busy, { onEvent(CaptureEvent.RequestFinish) }, true)
                    }
                    CapturePhase.PAUSED -> {
                        CaptureAction("完成采集", !state.busy, { onEvent(CaptureEvent.RequestFinish) })
                        CaptureAction("继续采集", !state.busy, { onEvent(CaptureEvent.Resume) }, true)
                    }
                    CapturePhase.READY -> CaptureAction("开始采集", !state.busy, { onEvent(CaptureEvent.Start) }, true)
                    CapturePhase.CAPTURED -> CaptureAction("打包数据", !state.busy, { onEvent(CaptureEvent.Pack) }, true)
                    CapturePhase.EXPORTED -> CaptureAction("扫描另一个房间", !state.busy, { onEvent(CaptureEvent.NewSession) }, true)
                    CapturePhase.UNAVAILABLE -> CaptureAction("授予权限 / 重新检查", !state.busy, {
                        if (!state.permissionGranted) requestPermission() else onEvent(CaptureEvent.PermissionResult(true))
                    }, true)
                    CapturePhase.ERROR -> {
                        if (state.capture.sessionPath.isNotEmpty()) CaptureAction("重新打开相机", !state.busy, { onEvent(CaptureEvent.Resume) })
                        if (state.capture.frames >= 3) CaptureAction("保留照片并打包", !state.busy, { onEvent(CaptureEvent.Pack) }, true)
                        else if (state.capture.sessionPath.isEmpty()) CaptureAction("重新检查相机", !state.busy,
                            { onEvent(CaptureEvent.PermissionResult(state.permissionGranted)) }, true)
                    }
                    else -> Unit
                }
                CaptureAction(if (state.busy) "处理中…" else "重置扫描数据",
                    !state.busy && state.capture.phase !in setOf(CapturePhase.CHECKING, CapturePhase.PACKING),
                    { onEvent(CaptureEvent.RequestReset) })
            }
        }
    }
}
