package com.example.roomsplat.ui.coverage

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.roomsplat.ecs.coverage.CoverageSceneController
import com.example.roomsplat.ecs.coverage.HandPanelController
import com.example.roomsplat.ui.capture.CaptureScreen
import com.example.roomsplat.ui.capture.CaptureViewModel
import com.pico.spatial.ui.foundation.content.SpatialView
import android.content.pm.ApplicationInfo
import java.io.File

@Composable
fun CoverageStage() {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) { CaptureViewModel.factory(context.applicationContext) }
    val vm: CaptureViewModel = viewModel(factory = factory)
    val controller = remember(vm) { CoverageSceneController(vm.headPoseCache, vm.savedFrames, vm.captureSnapshots,
        if(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0)
            File(context.getExternalFilesDir(null),"RoomSplat/diagnostics") else null,context.applicationContext) }
    val summary by controller.summary.collectAsStateWithLifecycle()
    var showCoverage by rememberSaveable { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(controller, showCoverage) { controller.setVisible(showCoverage) }
    var followHand by rememberSaveable { mutableStateOf(true) }
    val panelController = remember(vm) { HandPanelController(vm.headPoseCache) }
    val panelStatus by panelController.status.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(followHand) { panelController.setFollowHand(followHand) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { controller.pause(); panelController.pause() }
            if (event == Lifecycle.Event.ON_RESUME) { controller.start(); panelController.start() }
            if (event == Lifecycle.Event.ON_DESTROY) { controller.close(); panelController.close() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.close()
            panelController.close()
        }
    }
    SpatialView(
        initial = { content, attachments ->
            controller.attach(content)
            attachments.entity("capture_controls")?.let { panelController.attach(content, it) }
        },
        attachments = {
            AttachmentPanel("capture_controls") {
                CaptureScreen(Modifier.width(640.dp).height(520.dp), suppliedViewModel = vm,
                    coverage = summary, coverageVisible = showCoverage,
                    onCoverageToggle = { showCoverage = it },
                    panelStatus = panelStatus, followHand = followHand,
                    onFollowHandToggle = { followHand = it; panelController.setFollowHand(it) })
            }
        },
    )
}
