package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.HeadPoseEvidence
import com.example.roomsplat.domain.model.Point3
import com.example.roomsplat.domain.model.SavedFrame
import com.example.roomsplat.domain.model.SurfaceRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceCoverageUseCaseTest {
    @Test fun savedPhotoMarksOnlyDepthVerifiedEligibleCells() {
        val engine=SurfaceCoverageUseCase();val far=Point3(.3f,0f,-2f)
        engine.observe(point);engine.observe(far)
        engine.record(frame("verified"),setOf(engine.key(point)))
        assertEquals(SurfaceRecord.ONE_POSITION,engine.status(point))
        assertEquals(SurfaceRecord.UNRECORDED,engine.status(far))
        engine.record(frame("missing-depth"),emptySet())
        assertEquals(1,engine.counts().second)
    }
    @Test fun fullResetDropsGeometryStationsAndFrameIds() {
        val engine=SurfaceCoverageUseCase();engine.observe(point);engine.record(frame("a"));engine.record(frame("b",.6f))
        engine.clearMeasuredSurfaces()
        assertEquals(Triple(0,0,0),engine.counts());assertEquals(emptySet<String>(),engine.lastChangedKeys)
        engine.observe(point)
        assertEquals(SurfaceRecord.UNRECORDED,engine.status(point))
        engine.record(frame("a"))
        assertEquals(SurfaceRecord.ONE_POSITION,engine.status(point))
    }
    private val point = Point3(0f, 0f, -2f)
    private fun frame(id: String, x: Float = 0f, luma: Float = 100f) = SavedFrame("session", id, 1000, luma, 100f,
        HeadPoseEvidence(1000, Point3(x, 0f, 0f), Point3(0f, 0f, -1f), Point3(1f, 0f, 0f), Point3(0f, 1f, 0f), listOf(0f, 0f, 0f, 1f)))
    @Test fun noPoseCannotMarkButDarkPhotoMarksNeedForReshoot() {
        val engine = SurfaceCoverageUseCase(); engine.observe(point)
        engine.record(frame("a").copy(headPose = null))
        assertEquals(SurfaceRecord.UNRECORDED, engine.status(point))
        engine.record(frame("b", luma = 20f))
        assertEquals(SurfaceRecord.ONE_POSITION, engine.status(point))
        assertEquals(1, engine.counts().second)
    }
    @Test fun poorQualityStationsNeverProduceGreen() {
        val engine = SurfaceCoverageUseCase(); engine.observe(point)
        engine.record(frame("a", luma = 20f)); engine.record(frame("b", .6f, 20f)); engine.record(frame("c", -.6f, 20f))
        engine.record(frame("d").copy(detailVariance = 5f))
        assertEquals(SurfaceRecord.ONE_POSITION, engine.status(point))
        assertEquals(0, engine.counts().third)
    }
    @Test fun goodPhotosCanCompletePreviouslyDarkSurface() {
        val engine = SurfaceCoverageUseCase(); engine.observe(point)
        engine.record(frame("dark", luma = 20f))
        engine.record(frame("a")); engine.record(frame("b", .6f)); engine.record(frame("c", -.6f))
        assertEquals(SurfaceRecord.MULTIPLE_POSITIONS, engine.status(point))
    }
    @Test fun stationaryPhotosDoNotBecomeMultiPosition() {
        val engine = SurfaceCoverageUseCase(); engine.observe(point)
        repeat(20) { engine.record(frame("$it")) }
        assertEquals(SurfaceRecord.ONE_POSITION, engine.status(point))
    }
    @Test fun threeSeparatedStationsProduceMultiPositionReference() {
        val engine = SurfaceCoverageUseCase(); engine.observe(point)
        engine.record(frame("a")); engine.record(frame("b", .6f)); engine.record(frame("c", -.6f))
        assertEquals(SurfaceRecord.MULTIPLE_POSITIONS, engine.status(point))
    }
    @Test fun backSurfaceIsNotRecordedThroughNearSurface() {
        val engine = SurfaceCoverageUseCase(); val back = Point3(0f, 0f, -4f)
        engine.observe(point); engine.observe(back); engine.record(frame("a"))
        assertEquals(SurfaceRecord.ONE_POSITION, engine.status(point))
        assertEquals(SurfaceRecord.UNRECORDED, engine.status(back))
    }
    @Test fun stalePoseAndRepeatedFrameAreNotCounted() {
        val engine = SurfaceCoverageUseCase(); engine.observe(point)
        engine.record(frame("a").copy(receivedNanos = 100_000_000))
        assertEquals(SurfaceRecord.UNRECORDED, engine.status(point))
        engine.record(frame("b")); engine.record(frame("b", .6f)); engine.record(frame("b", -.6f))
        assertEquals(SurfaceRecord.ONE_POSITION, engine.status(point))
    }
    @Test fun onePhotoMarksWholeNearLayerWithinProjectionBin() {
        val engine=SurfaceCoverageUseCase(); val adjacent=Point3(0f,0f,-2.09f)
        engine.observe(point); engine.observe(adjacent)
        assertEquals(2,engine.record(frame("a")))
        assertEquals(SurfaceRecord.ONE_POSITION,engine.status(adjacent))
        assertEquals(0,engine.record(frame("b")))
    }
    @Test fun fineCellsSeparateNeighbouringSurfaceSamples() {
        val engine=SurfaceCoverageUseCase(); val neighbour=Point3(.09f,0f,-2f)
        engine.observe(point); engine.observe(neighbour)
        assertEquals(2,engine.counts().first)
        assertEquals(.08f,SurfaceCoverageUseCase.CELL_SIZE_M,.0001f)
    }
    @Test fun resettingAndRemovingGeometryClearsOldClaims() {
        val engine = SurfaceCoverageUseCase(); engine.observe(point); engine.record(frame("a"))
        engine.resetRecords(); assertEquals(SurfaceRecord.UNRECORDED, engine.status(point))
        engine.replaceObserved(emptyList()); assertEquals(0, engine.counts().first)
    }
    @Test fun twoUsableRoomViewsProduceGreenWithoutRequiringThreePerfectImages() {
        val engine=SurfaceCoverageUseCase();engine.observe(point)
        engine.record(frame("a",luma=38f).copy(detailVariance=8f))
        assertEquals(SurfaceRecord.ONE_POSITION,engine.status(point))
        engine.record(frame("b",.3f,38f).copy(detailVariance=8f))
        assertEquals(SurfaceRecord.MULTIPLE_POSITIONS,engine.status(point))
    }
    @Test fun fartherWallAllowsAReasonableSideStep() {
        val far=Point3(0f,0f,-5f);val engine=SurfaceCoverageUseCase();engine.observe(far)
        engine.record(frame("a"));engine.record(frame("b",.4f))
        assertEquals(SurfaceRecord.MULTIPLE_POSITIONS,engine.status(far))
    }
    @Test fun movementWithoutParallaxDoesNotPretendToCoverAnotherView() {
        val engine=SurfaceCoverageUseCase();engine.observe(point)
        engine.record(frame("a"))
        val sameRay=frame("b").let { it.copy(headPose=it.headPose!!.copy(position=Point3(0f,0f,.4f))) }
        engine.record(sameRay)
        assertEquals(SurfaceRecord.ONE_POSITION,engine.status(point))
    }

    @Test fun keyedGeometryRefreshRetainsSavedViewpointsAndRemovesObsoleteCells() {
        val engine=SurfaceCoverageUseCase();val removed=Point3(1f,0f,-2f)
        engine.replaceObserved(listOf(point,removed))
        engine.record(frame("a"));engine.record(frame("b",.4f))
        engine.replaceObservedKeyed(mapOf(engine.key(point) to point))
        assertEquals(SurfaceRecord.MULTIPLE_POSITIONS,engine.status(point))
        assertEquals(Triple(1,1,1),engine.counts())
        engine.resetRecords()
        assertEquals(SurfaceRecord.UNRECORDED,engine.status(point))
    }
}
