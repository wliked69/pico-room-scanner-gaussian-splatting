package com.example.roomsplat.ui.capture

import com.example.roomsplat.data.repository.CaptureRepository
import com.example.roomsplat.domain.model.CapturePhase
import com.example.roomsplat.domain.model.CaptureSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import com.example.roomsplat.domain.model.SavedFrame
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun resetRequiresConfirmationAndCancelPreservesCurrentPhotos() = runTest(dispatcher) {
        val repo=FakeCaptureRepository(); val vm=CaptureViewModel(repo)
        vm.onEvent(CaptureEvent.Start); advanceUntilIdle()
        vm.onEvent(CaptureEvent.ConfirmReset); advanceUntilIdle()
        assertEquals(5,vm.state.value.capture.frames); assertEquals(0,repo.resetCalls)
        vm.onEvent(CaptureEvent.RequestReset); assertTrue(vm.state.value.showResetConfirmation)
        vm.onEvent(CaptureEvent.CancelReset); assertFalse(vm.state.value.showResetConfirmation)
        assertEquals(5,vm.state.value.capture.frames)
    }
    @Test fun confirmedResetClearsCurrentCountersAndAllowsRestart() = runTest(dispatcher) {
        val repo=FakeCaptureRepository(); val vm=CaptureViewModel(repo)
        vm.onEvent(CaptureEvent.Start); advanceUntilIdle()
        vm.onEvent(CaptureEvent.RequestReset); vm.onEvent(CaptureEvent.ConfirmReset); advanceUntilIdle()
        assertEquals(1,repo.resetCalls); assertEquals(0,vm.state.value.capture.frames)
        assertEquals(CapturePhase.READY,vm.state.value.capture.phase); assertFalse(vm.state.value.busy)
        vm.onEvent(CaptureEvent.Start); advanceUntilIdle()
        assertEquals(CapturePhase.RECORDING,vm.state.value.capture.phase)
    }
    @Test fun resetFailureKeepsCurrentPhotosAndReportsReason() = runTest(dispatcher) {
        val repo=FakeCaptureRepository(); val vm=CaptureViewModel(repo)
        vm.onEvent(CaptureEvent.Start); advanceUntilIdle(); repo.failReset=true
        vm.onEvent(CaptureEvent.RequestReset); vm.onEvent(CaptureEvent.ConfirmReset); advanceUntilIdle()
        assertEquals(5,vm.state.value.capture.frames); assertTrue(vm.state.value.error.contains("reset failed"))
    }

    @Test fun acceptedResetSurvivesDismissalBeforeCommandRuns() = runTest(dispatcher) {
        val repo = FakeCaptureRepository(); val vm = CaptureViewModel(repo)
        vm.onEvent(CaptureEvent.Start); advanceUntilIdle()
        vm.onEvent(CaptureEvent.RequestReset)
        vm.onEvent(CaptureEvent.ConfirmReset)
        assertTrue(vm.state.value.busy)
        vm.onEvent(CaptureEvent.CancelReset)
        vm.onEvent(CaptureEvent.ConfirmReset)
        advanceUntilIdle()
        assertEquals(1, repo.resetCalls)
        assertEquals(0, vm.state.value.capture.frames)
        assertTrue(vm.state.value.error.isEmpty())
    }

    @Test fun permissionDenialIsVisibleAndCannotStart() = runTest(dispatcher) {
        val repo = FakeCaptureRepository()
        val vm = CaptureViewModel(repo)
        vm.onEvent(CaptureEvent.PermissionResult(false))
        advanceUntilIdle()
        assertFalse(vm.state.value.permissionGranted)
        assertEquals(CapturePhase.UNAVAILABLE, vm.state.value.capture.phase)
    }

    @Test fun startThenLifecyclePausePreservesFrames() = runTest(dispatcher) {
        val repo = FakeCaptureRepository()
        val vm = CaptureViewModel(repo)
        vm.onEvent(CaptureEvent.PermissionResult(true)); advanceUntilIdle()
        vm.onEvent(CaptureEvent.Start); advanceUntilIdle()
        assertEquals(CapturePhase.RECORDING, vm.state.value.capture.phase)
        vm.onEvent(CaptureEvent.HostPaused); advanceUntilIdle()
        assertEquals(CapturePhase.PAUSED, vm.state.value.capture.phase)
        assertEquals(5, vm.state.value.capture.frames)
    }

    @Test fun endingTooSmallACaptureIsRejected() = runTest(dispatcher) {
        val repo = FakeCaptureRepository()
        val vm = CaptureViewModel(repo)
        advanceUntilIdle()
        vm.onEvent(CaptureEvent.RequestFinish)
        assertFalse(vm.state.value.showFinishConfirmation)
        assertTrue(vm.state.value.error.contains("至少"))
    }

    @Test fun finishNeedsConfirmationAndCanBeCancelled() = runTest(dispatcher) {
        val repo = FakeCaptureRepository()
        val vm = CaptureViewModel(repo)
        vm.onEvent(CaptureEvent.Start); advanceUntilIdle()
        vm.onEvent(CaptureEvent.RequestFinish)
        assertTrue(vm.state.value.showFinishConfirmation)
        vm.onEvent(CaptureEvent.CancelFinish)
        assertEquals(CapturePhase.RECORDING, vm.state.value.capture.phase)
        assertFalse(vm.state.value.showFinishConfirmation)
        vm.onEvent(CaptureEvent.RequestFinish)
        vm.onEvent(CaptureEvent.ConfirmFinish); advanceUntilIdle()
        assertEquals(CapturePhase.CAPTURED, vm.state.value.capture.phase)
    }

    @Test fun failedPackingRetainsCaptureAndAllowsRetry() = runTest(dispatcher) {
        val repo = FakeCaptureRepository()
        val vm = CaptureViewModel(repo)
        vm.onEvent(CaptureEvent.Start); advanceUntilIdle()
        vm.onEvent(CaptureEvent.ConfirmFinish); advanceUntilIdle()
        repo.failPacking = true
        vm.onEvent(CaptureEvent.Pack); advanceUntilIdle()
        assertEquals(5, vm.state.value.capture.frames)
        assertTrue(vm.state.value.error.contains("disk"))
        repo.failPacking = false
        vm.onEvent(CaptureEvent.Pack); advanceUntilIdle()
        assertEquals(CapturePhase.EXPORTED, vm.state.value.capture.phase)
        assertFalse(vm.state.value.busy)
    }
    @Test fun pauseSignalsImmediatelyWhileStartStillOwnsCommandMutex() = runTest(dispatcher) {
        val repo=FakeCaptureRepository();repo.startCompletion=CompletableDeferred()
        val vm=CaptureViewModel(repo)
        vm.onEvent(CaptureEvent.Start);runCurrent()
        assertTrue(vm.state.value.busy)
        vm.onEvent(CaptureEvent.Pause)
        assertEquals(1,repo.pauseRequests)
        repo.startCompletion!!.complete(Unit);advanceUntilIdle()
        assertEquals(CapturePhase.PAUSED,vm.state.value.capture.phase)
        assertFalse(vm.state.value.busy)
    }
}

private class FakeCaptureRepository : CaptureRepository {
    override val snapshot = MutableStateFlow(CaptureSnapshot())
    override val savedFrames = MutableSharedFlow<SavedFrame>()
    var failPacking = false
    var failReset = false
    var resetCalls = 0
    var pauseRequests = 0
    var startCompletion: CompletableDeferred<Unit>? = null
    override fun requestPause() { pauseRequests++ }
    override suspend fun check(permissionGranted: Boolean) {
        snapshot.value = snapshot.value.copy(phase = if (permissionGranted) CapturePhase.READY else CapturePhase.UNAVAILABLE)
    }
    override suspend fun start() { snapshot.value = snapshot.value.copy(phase = CapturePhase.RECORDING, frames = 5);startCompletion?.await() }
    override suspend fun pause() { snapshot.value = snapshot.value.copy(phase = CapturePhase.PAUSED) }
    override suspend fun resume() { snapshot.value = snapshot.value.copy(phase = CapturePhase.RECORDING) }
    override suspend fun finish() { snapshot.value = snapshot.value.copy(phase = CapturePhase.CAPTURED) }
    override suspend fun pack() {
        if (failPacking) error("disk full")
        snapshot.value = snapshot.value.copy(phase = CapturePhase.EXPORTED, archivePath = "/capture.zip")
    }
    override suspend fun newSession() { snapshot.value = CaptureSnapshot(phase = CapturePhase.READY) }
    override suspend fun resetCurrentSession() {
        if (failReset) error("reset failed")
        resetCalls++
        snapshot.value = CaptureSnapshot(phase = CapturePhase.READY)
    }
    override fun close() = Unit
}
