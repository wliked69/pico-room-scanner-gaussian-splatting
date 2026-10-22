
package com.example.roomsplat.domain.usecase
import com.example.roomsplat.domain.model.HeadPoseEvidence
import com.example.roomsplat.domain.model.Point3
import org.junit.Assert.*
import org.junit.Test

class SurfaceRevealUseCaseTest {
    @Test fun aLateGeometryBatchAppearsInBoundedStepsWithoutLosingProgress() {
        val use=SurfaceRevealUseCase(keyOf={ "${it.x}:${it.y}" })
        val wall=buildList { for(x in 0..19)for(y in 0..19)add(Point3((x-10)*.08f,(y-10)*.08f,-2f)) }
        use.replaceObserved(wall)
        use.update(pose(0),0,maxNewCells=0)
        repeat(4) { step ->
            val t=1_000_000_000L+step*80_000_000L
            assertEquals(100,use.update(pose(t),t,maxNewCells=100).size)
            assertEquals((step+1)*100,use.visibleCount())
        }
        assertEquals(400,use.historyCount())
        assertTrue(use.update(pose(2_000_000_000),2_000_000_000,maxNewCells=100).isEmpty())
    }
    @Test fun aRevealBudgetDoesNotBypassDepthBlocksPauseOrReset() {
        val use=engine();use.replaceObserved(listOf(centre,edge));val key=SurfaceCoverageUseCase()::key
        use.update(pose(0),0,blocked=setOf(key(centre)),maxNewCells=1)
        use.update(pose(1_000_000_000),1_000_000_000,allowReveal=false,maxNewCells=1)
        assertEquals(0,use.visibleCount())
        use.clearMeasuredSurfaces();use.replaceObserved(listOf(centre,edge))
        use.update(pose(2_000_000_000),2_000_000_000,maxNewCells=1)
        assertTrue(use.isVisible(centre));assertFalse(use.isVisible(edge));assertEquals(1,use.historyCount())
    }
    @Test fun fullResetClearsVisibleAndScheduledRegionsBeforeFreshObservation() {
        val use=engine();use.replaceObserved(listOf(centre,edge));use.update(pose(0),0)
        use.clearMeasuredSurfaces();use.update(pose(800_000_000),800_000_000)
        assertEquals(0,use.visibleCount());assertFalse(use.isVisible(centre));assertFalse(use.isVisible(edge))
        use.replaceObserved(listOf(centre,edge));use.update(pose(900_000_000),900_000_000)
        assertTrue(use.isVisible(centre));assertFalse(use.isVisible(edge))
    }
    private val centre=Point3(0f,0f,-2f)
    private val edge=Point3(2.4f,0f,-2f)
    private val behind=Point3(0f,0f,2f)
    private fun engine() = SurfaceRevealUseCase(SurfaceCoverageUseCase()::key)
    private fun pose(now: Long, forward: Point3=Point3(0f,0f,-1f)) = HeadPoseEvidence(now,
        Point3(0f,0f,0f),forward,Point3(1f,0f,0f),Point3(0f,1f,0f),listOf(0f,0f,0f,1f))
    @Test fun frontCentreAppearsBeforeEdges() {
        val use=engine();use.replaceObserved(listOf(centre,edge))
        use.update(pose(0),0)
        assertTrue(use.isVisible(centre));assertFalse(use.isVisible(edge))
        use.update(pose(200_000_000),200_000_000)
        assertFalse(use.isVisible(edge))
        use.update(pose(800_000_000),800_000_000)
        assertTrue(use.isVisible(edge))
    }
    @Test fun rearSurfaceWaitsUntilUserTurnsAndSeenSurfaceStaysVisible() {
        val use=engine();use.replaceObserved(listOf(centre,behind))
        use.update(pose(0),0);use.update(pose(1_000_000_000),1_000_000_000)
        assertFalse(use.isVisible(behind))
        use.update(pose(2_000_000_000,Point3(0f,0f,1f)),2_000_000_000)
        assertTrue(use.isVisible(behind));assertTrue(use.isVisible(centre))
    }
    @Test fun missingOrStalePoseCannotRevealUnknownSurface() {
        val use=engine();use.replaceObserved(listOf(centre))
        use.update(null,0);use.update(pose(0),500_000_000)
        assertFalse(use.isVisible(centre));assertEquals(0,use.visibleCount())
    }
    @Test fun removalDoesNotRetainOldVisibleGeometry() {
        val use=engine();use.replaceObserved(listOf(centre));use.update(pose(0),0)
        use.replaceObserved(emptyList())
        assertFalse(use.isVisible(centre));assertEquals(0,use.visibleCount())
    }
    @Test fun rectangularWallCornerIsRevealedInsteadOfPermanentlyClippedByEllipse() {
        val corner=Point3(2.3f,1.6f,-2f);val use=engine()
        use.replaceObserved(listOf(centre,corner));use.update(pose(0),0)
        assertTrue(use.isVisible(centre));assertFalse(use.isVisible(corner))
        use.update(pose(800_000_000),800_000_000)
        assertTrue(use.isVisible(corner))
    }
    @Test fun revisedPointInSameVoxelUsesCurrentWallLocation() {
        val outside=Point3(2.849f,0f,-2.03f);val inside=Point3(2.831f,0f,-2.03f)
        val key=SurfaceCoverageUseCase()::key
        assertEquals(key(outside),key(inside))
        val use=engine();use.replaceObserved(listOf(outside));use.update(pose(0),0)
        use.replaceObserved(listOf(inside));use.update(pose(100_000_000),100_000_000)
        use.update(pose(1_000_000_000),1_000_000_000)
        assertTrue(use.isVisible(inside))
    }
    @Test fun clearingPhotographicEvidenceKeepsBlueGeometryEvenWithoutFreshPose() {
        val coverage=SurfaceCoverageUseCase();val use=engine()
        coverage.observe(centre);use.replaceObserved(listOf(centre,edge))
        use.update(pose(0),0);use.update(pose(800_000_000),800_000_000)
        fun frame(id:String,x:Float)=com.example.roomsplat.domain.model.SavedFrame("session",id,0,100f,100f,
            pose(0).copy(position=Point3(x,0f,0f)))
        coverage.record(frame("a",0f));coverage.record(frame("b",.4f))
        assertEquals(com.example.roomsplat.domain.model.SurfaceRecord.MULTIPLE_POSITIONS,coverage.status(centre))
        coverage.resetRecords()
        use.update(null,900_000_000)
        assertTrue(use.isVisible(centre));assertTrue(use.isVisible(edge))
        assertEquals(2,use.visibleCount())
        assertEquals(com.example.roomsplat.domain.model.SurfaceRecord.UNRECORDED,coverage.status(centre))
        assertEquals(0,coverage.counts().second);assertEquals(0,coverage.counts().third)
    }

    @Test fun keyedGeometryRefreshRetainsVisibleExtentAndUsesLatestPoint() {
        val use=engine();val key=SurfaceCoverageUseCase()::key
        use.replaceObserved(listOf(centre));use.update(pose(0),0)
        val moved=centre.copy(x=.02f)
        use.replaceObservedKeyed(mapOf(key(moved) to moved))
        assertTrue(use.isVisible(moved));assertEquals(1,use.visibleCount())
        use.replaceObservedKeyed(emptyMap());assertEquals(0,use.visibleCount())
    }

    @Test fun occludedFrontSurfaceNeverRevealsEvenAfterItsOldDueTime() {
        val use=engine();use.replaceObserved(listOf(edge))
        val key=SurfaceCoverageUseCase().key(edge)
        use.update(pose(0),0)
        use.update(pose(800_000_000),800_000_000,setOf(key))
        assertFalse(use.isVisible(edge))
        use.update(pose(900_000_000),900_000_000)
        assertFalse(use.isVisible(edge))
        use.update(pose(1_700_000_000),1_700_000_000)
        assertTrue(use.isVisible(edge))
    }
    @Test fun alreadyRevealedWallSurvivesSustainedMasksAndHeadMovement() {
        val use=engine();use.replaceObserved(listOf(centre));val blocked=setOf(SurfaceCoverageUseCase().key(centre))
        use.update(pose(0),0)
        for(now in listOf(100_000_000L,450_000_000L,800_000_000L,5_000_000_000L)) {
            val moved=pose(now).copy(position=Point3(.4f,0f,0f))
            assertTrue(use.update(moved,now,blocked).isEmpty())
            assertTrue(use.isVisible(centre));assertEquals(1,use.visibleCount())
        }
    }
    @Test fun pausedViewingRetainsSeenCellsButCannotRevealNewCells() {
        val use=engine();use.replaceObserved(listOf(centre,edge));val blocked=setOf(SurfaceCoverageUseCase().key(centre))
        use.update(pose(0),0)
        use.update(pose(2_000_000_000),2_000_000_000,blocked,allowReveal=false)
        assertTrue(use.isVisible(centre));assertFalse(use.isVisible(edge))
    }
    @Test fun temporaryNativeRemovalCanRecoverExactProgressWithoutFreshHeadPose() {
        val use=engine();use.replaceObserved(listOf(centre));use.update(pose(0),0)
        use.replaceObserved(emptyList())
        assertFalse(use.isVisible(centre));assertEquals(0,use.visibleCount())
        assertEquals(1,use.historyCount())
        val key=SurfaceCoverageUseCase().key(centre)
        assertEquals(setOf(key),use.replaceObservedKeyed(mapOf(key to centre)))
        use.update(null,1_000_000_000)
        assertTrue(use.isVisible(centre));assertEquals(1,use.lastObservation.restoredExact)
    }
    @Test fun voxelBoundaryJitterRecoversNearbyDisplayProgress() {
        val a=Point3(.079f,0f,-2f);val b=Point3(.081f,0f,-2f);val use=engine()
        assertNotEquals(SurfaceCoverageUseCase().key(a),SurfaceCoverageUseCase().key(b))
        use.replaceObserved(listOf(a));use.update(pose(0),0);use.update(pose(1_000_000_000),1_000_000_000)
        use.replaceObserved(listOf(b))
        assertTrue(use.isVisible(b));assertFalse(use.isVisible(a))
        assertEquals(1,use.lastObservation.restoredNearby);assertEquals(1,use.historyCount())
    }
    @Test fun negativeCoordinateBoundaryJitterAlsoRecovers() {
        val a=Point3(-.081f,0f,-2f);val b=Point3(-.079f,0f,-2f);val use=engine()
        use.replaceObserved(listOf(a));use.update(pose(0),0);use.update(pose(1_000_000_000),1_000_000_000)
        use.replaceObserved(listOf(b))
        assertTrue(use.isVisible(b));assertEquals(1,use.lastObservation.restoredNearby)
    }
    @Test fun distantReplacementIsNotRevealedByNearbyHistory() {
        val a=Point3(.079f,0f,-2f);val b=Point3(.119f,0f,-2f);val use=engine()
        use.replaceObserved(listOf(a));use.update(pose(0),0);use.update(pose(1_000_000_000),1_000_000_000)
        use.replaceObserved(listOf(b))
        assertFalse(use.isVisible(b));assertEquals(0,use.lastObservation.restoredNearby)
    }
    @Test fun nearbyInheritanceCannotPropagateProgressBeyondOriginalDirectSample() {
        val a=Point3(.079f,0f,-2f);val b=Point3(.081f,0f,-2f);val c=Point3(.111f,0f,-2f);val use=engine()
        use.replaceObserved(listOf(a));use.update(pose(0),0);use.update(pose(1_000_000_000),1_000_000_000)
        use.replaceObserved(listOf(b));assertTrue(use.isVisible(b))
        use.replaceObserved(emptyList());use.replaceObserved(listOf(c))
        assertFalse(use.isVisible(c));assertEquals(1,use.historyCount())
    }
    @Test fun diagonalMatchUsesActualDistanceRatherThanIndependentAxisBounds() {
        val a=Point3(.079f,.079f,-2.001f);val b=Point3(.099f,.099f,-1.981f);val use=engine()
        use.replaceObserved(listOf(a));use.update(pose(0),0);use.update(pose(1_000_000_000),1_000_000_000)
        use.replaceObserved(listOf(b))
        assertFalse(use.isVisible(b))
    }
    @Test fun resetForgetsExactAndNearbyHistoryEvenWhilePaused() {
        val a=Point3(.079f,0f,-2f);val b=Point3(.081f,0f,-2f);val use=engine()
        use.replaceObserved(listOf(a));use.update(pose(0),0);use.update(pose(1_000_000_000),1_000_000_000)
        use.clearMeasuredSurfaces();assertEquals(0,use.historyCount())
        use.replaceObserved(listOf(a,b));use.update(pose(2_000_000_000),2_000_000_000,allowReveal=false)
        assertFalse(use.isVisible(a));assertFalse(use.isVisible(b))
    }
    @Test fun revealHistoryIsBoundedAndEvictionDoesNotDrawAbsentSurfaces() {
        val use=SurfaceRevealUseCase(SurfaceCoverageUseCase()::key,historyCapacity=2)
        val points=listOf(centre,centre.copy(x=.2f),centre.copy(x=.4f))
        use.replaceObserved(points);use.update(pose(0),0);use.update(pose(1_000_000_000),1_000_000_000)
        assertEquals(2,use.historyCount())
        use.replaceObserved(emptyList());assertEquals(0,use.visibleCount())
        use.replaceObserved(points)
        assertFalse(use.isVisible(points[0]));assertTrue(use.isVisible(points[1]));assertTrue(use.isVisible(points[2]))
    }
    @Test fun inheritedDisplayProgressDoesNotInventPhotographicGreenEvidence() {
        val a=Point3(.079f,0f,-2f);val b=Point3(.081f,0f,-2f)
        val coverage=SurfaceCoverageUseCase();val use=engine()
        use.replaceObserved(listOf(a));use.update(pose(0),0);use.update(pose(1_000_000_000),1_000_000_000)
        use.replaceObserved(listOf(b));coverage.replaceObserved(listOf(b))
        assertTrue(use.isVisible(b))
        assertEquals(com.example.roomsplat.domain.model.SurfaceRecord.UNRECORDED,coverage.status(b))
        assertEquals(0,coverage.counts().third)
    }
    @Test fun preparedObservationDoesNotChangePublishedDisplayBeforeCommit() {
        val a=Point3(.079f,0f,-2f);val b=Point3(.081f,0f,-2f);val use=engine();val key=SurfaceCoverageUseCase()::key
        use.replaceObserved(listOf(a));use.update(pose(0),0);use.update(pose(1_000_000_000),1_000_000_000)
        val plan=use.prepareObservedKeyed(mapOf(key(b) to b))
        assertTrue(use.isVisible(a));assertFalse(use.isVisible(b))
        assertTrue(plan.commit());assertTrue(use.isVisible(b));assertFalse(use.isVisible(a))
    }
    @Test fun resetRejectsPreparedOldGeometryAndRevealHistory() {
        val use=engine();val key=SurfaceCoverageUseCase()::key
        use.replaceObserved(listOf(centre));use.update(pose(0),0)
        val plan=use.prepareObservedKeyed(mapOf(key(centre) to centre))
        use.clearMeasuredSurfaces()
        assertFalse(plan.commit());assertEquals(0,use.visibleCount());assertEquals(0,use.historyCount())
    }
    @Test fun newerObservationCommitInvalidatesOlderPreparedPlan() {
        val use=engine();val key=SurfaceCoverageUseCase()::key
        use.replaceObserved(listOf(centre));use.update(pose(0),0)
        val old=use.prepareObservedKeyed(mapOf(key(centre) to centre))
        val next=use.prepareObservedKeyed(emptyMap())
        assertTrue(next.commit());assertFalse(old.commit());assertEquals(0,use.visibleCount())
    }
    @Test fun changedRevealEvidenceInvalidatesAPlanPreparedAgainstOlderState() {
        val use=engine();val key=SurfaceCoverageUseCase()::key
        use.replaceObserved(listOf(centre))
        val old=use.prepareObservedKeyed(mapOf(key(centre) to centre))
        use.update(pose(0),0)
        assertFalse(old.commit());assertTrue(use.isVisible(centre))
    }
}
