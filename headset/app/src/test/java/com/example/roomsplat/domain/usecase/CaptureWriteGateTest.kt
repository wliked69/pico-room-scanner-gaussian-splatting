
package com.example.roomsplat.domain.usecase
import org.junit.Assert.*
import org.junit.Test

class CaptureWriteGateTest {
    @Test fun pauseImmediatelyRejectsImagesAlreadyQueuedOnCameraThread() {
        val gate = CaptureWriteGate(); val token = gate.begin()
        assertTrue(gate.allows(token))
        gate.pause()
        repeat(1000) { assertFalse(gate.allows(token)) }
    }
    @Test fun lateCameraOpenCannotUndoPause() {
        val gate = CaptureWriteGate(); val opening = gate.begin()
        gate.pause()
        assertFalse(gate.allows(opening))
    }
    @Test fun resumeRejectsCallbacksFromPreviousRecordingSegment() {
        val gate = CaptureWriteGate(); val old = gate.begin()
        gate.pause(); val current = gate.begin()
        assertFalse(gate.allows(old)); assertTrue(gate.allows(current))
    }
}
