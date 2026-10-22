package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshDisplayStabilityUseCaseTest {
    private val engine = MeshDisplayStabilityUseCase()
    private val wall = (0..5).map { Point3(it*.2f, 1f, -2f) }
    @Test fun firstSurfaceIsDisplayedImmediately() { assertTrue(engine.shouldReplace(emptyList(), wall, 0, 1)) }
    @Test fun smallTrackingNoiseAndRetessellationKeepSnapshot() {
        val noisy = wall.flatMap { listOf(it.copy(x=it.x+.03f, z=it.z-.02f), it.copy(y=it.y+.02f)) }
        assertFalse(engine.shouldReplace(wall, noisy, 0, 3_000_000_000))
    }
    @Test fun significantNewSurfaceIsAcceptedAfterHold() {
        val more = wall + (0..5).map { Point3(it*.2f, 2f, -2f) } + (0..5).map { Point3(it*.2f, 0f, -2f) }
        assertFalse(engine.shouldReplace(wall, more, 0, 1_000_000_000))
        assertTrue(engine.shouldReplace(wall, more, 0, 3_000_000_000))
    }
    @Test fun largeWorldCorrectionIsAcceptedAndEmptyUpdateIgnored() {
        assertTrue(engine.shouldReplace(wall, wall.map { it.copy(x=it.x+.5f) }, 0, 3_000_000_000))
        assertFalse(engine.shouldReplace(wall, emptyList(), 0, 3_000_000_000))
    }
    @Test fun nearbyHoleFillIsAcceptedBeforeHoldWithoutNewFarVoxels() {
        val middle=wall+Point3(.4f,1.1f,-2f)
        assertTrue(engine.decide(wall,middle,0,1_000_000_000,1f,1.05f)==MeshDisplayStabilityUseCase.Decision.ACCEPT)
        assertTrue(engine.decide(wall,middle,0,2_100_000_000,1f,1.05f)==MeshDisplayStabilityUseCase.Decision.ACCEPT)
    }
    @Test fun unchangedAreaRetessellationStillDoesNotCauseJitter() {
        val noisy=wall.flatMap { listOf(it.copy(x=it.x+.03f,z=it.z-.02f),it.copy(y=it.y+.02f)) }
        assertTrue(engine.decide(wall,noisy,0,3_000_000_000,2f,2f)==MeshDisplayStabilityUseCase.Decision.IGNORE)
    }
    @Test fun singleNewSurfaceEventCanBeReconsideredAfterHoldExpires() {
        val more=wall+(0..5).map { Point3(it*.2f,2f,-2f) }
        assertTrue(engine.decide(wall,more,0,500_000_000)==MeshDisplayStabilityUseCase.Decision.DEFER)
        assertTrue(engine.decide(wall,more,0,2_100_000_000)==MeshDisplayStabilityUseCase.Decision.ACCEPT)
    }

    @Test fun smallAreaNoiseDoesNotBypassTheExistingHold() {
        assertTrue(engine.decide(wall,wall,0,100_000_000,1f,1.005f)==MeshDisplayStabilityUseCase.Decision.DEFER)
    }
    @Test fun invalidAreaDoesNotBypassTheExistingHold() {
        assertTrue(engine.decide(wall,wall,0,100_000_000,1f,Float.NaN)==MeshDisplayStabilityUseCase.Decision.DEFER)
    }

    @Test fun largerAreaWithDriftingDisplayedSurfaceStillWaitsForHold() {
        val drift=wall.map { it.copy(x=it.x+.03f) }+Point3(2f,2f,-2f)
        assertTrue(engine.decide(wall,drift,0,100_000_000,1f,1.1f)==MeshDisplayStabilityUseCase.Decision.DEFER)
    }
    @Test fun stableCentresCannotHideMovingExistingVertices() {
        val before=listOf(Point3(0f,0f,-2f),Point3(1f,0f,-2f),Point3(0f,1f,-2f))
        val moved=before.map { it.copy(z=it.z+.02f) }
        assertTrue(engine.decide(wall,wall+Point3(2f,2f,-2f),0,100_000_000,1f,1.1f,before,moved)==MeshDisplayStabilityUseCase.Decision.DEFER)
    }
    @Test fun additiveAreaWithRetainedVerticesAndCentresCanAppearEarly() {
        val before=listOf(Point3(0f,0f,-2f),Point3(1f,0f,-2f),Point3(0f,1f,-2f))
        val more=before+Point3(2f,0f,-2f)
        assertTrue(engine.decide(wall,wall+Point3(2f,2f,-2f),0,100_000_000,1f,1.1f,before,more)==MeshDisplayStabilityUseCase.Decision.ACCEPT)
    }

    @Test fun areaNoiseWithMovingExistingVerticesDoesNotBecomeValidAfterHoldExpires() {
        val before=wall.toList();val moved=wall.map { it.copy(z=it.z+.02f) }
        assertTrue(engine.decide(wall,moved,0,3_000_000_000,1f,1.02f,before,moved)==MeshDisplayStabilityUseCase.Decision.IGNORE)
    }
    @Test fun stableRetessellationCanFillAHoleWithoutMovingExistingVertices() {
        val before=listOf(Point3(0f,0f,-2f),Point3(1f,0f,-2f),Point3(0f,1f,-2f))
        val retriangulated=wall.map { it.copy(y=it.y+.03f) }
        assertTrue(engine.decide(wall,retriangulated,0,3_000_000_000,1f,1.1f,before,before+Point3(.2f,.2f,-2f))==MeshDisplayStabilityUseCase.Decision.ACCEPT)
    }
}
