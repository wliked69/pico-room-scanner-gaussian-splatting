package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.*
import com.example.roomsplat.domain.usecase.LiveDepthAdmissionUseCase.Visibility.*
import org.junit.Assert.*
import org.junit.Test

class LiveDepthVisibilityUseCaseTest {
    private fun pose(t:Long=0,x:Float=0f)=HeadPoseEvidence(t,Point3(x,0f,0f),Point3(0f,0f,-1f),
        Point3(1f,0f,0f),Point3(0f,1f,0f),emptyList())
    private fun frame(z:Float=2f)=LiveDepthFrame(pose(),0,160,128,FloatArray(160*128) { z })
    private val query=LiveDepthAdmissionUseCase()
    @Test fun completeNearWallPatchHidesFarOldSurfaceButNotOffsetCurrentWall() {
        val f=frame()
        assertEquals(OCCLUDED,query.visibility(Point3(0f,0f,-3f),f))
        assertEquals(MATCHED,query.visibility(Point3(0f,0f,-2.15f),f))
        assertEquals(UNKNOWN,query.visibility(Point3(0f,0f,-2.3f),f))
        assertEquals(CLEAR,query.visibility(Point3(0f,0f,-1f),f))
    }
    @Test fun invalidDepthAndDoorwayDiscontinuityCannotEraseCurrentWall() {
        val values=FloatArray(160*128) { 2f }
        for(y in 63..65)for(x in 79..81)values[y*160+x]=0f
        val f=LiveDepthFrame(pose(),0,160,128,values)
        assertEquals(UNKNOWN,query.visibility(Point3(0f,0f,-3f),f))
        var i=0
        for(y in 63..65)for(x in 79..81)values[y*160+x]=if(i++<4)1f else 2f
        values[64*160+80]=0f // balanced 4/4 doorway edge, no dominant measured surface
        assertEquals(UNKNOWN,query.visibility(Point3(0f,0f,-3f),LiveDepthFrame(pose(),0,160,128,values)))
    }
    @Test fun behindHeadAndFovEdgeAreUnknownNotOccluded() {
        assertEquals(UNKNOWN,query.visibility(Point3(0f,0f,3f),frame()))
        assertEquals(UNKNOWN,query.visibility(Point3(4.19f,0f,-3f),frame()))
    }
    @Test fun drawingNeedsTighterPoseThanNewSurfaceAdmission() {
        assertTrue(query.usable(frame(),pose(x=.015f),0))
        assertFalse(query.drawUsable(frame(),pose(x=.015f),0))
        assertTrue(query.drawUsable(frame(),pose(x=.005f),0))
    }
    @Test fun duplicateFrameCannotConfirmAndTwoIndependentFramesNeedTimeSpan() {
        val mask=LiveDepthVisibilityUseCase();val blocked=mapOf("old" to OCCLUDED)
        mask.update(blocked,0);repeat(10) { mask.update(blocked,0) }
        mask.update(blocked,100_000_000)
        assertTrue(mask.hiddenKeys().isEmpty())
        assertEquals(setOf("old"),mask.update(blocked,300_000_000))
        assertEquals(setOf("old"),mask.hiddenKeys())
    }
    @Test fun staleMissingAndTurningViewKeepKnownWallsAndConfirmedMaskStable() {
        val mask=LiveDepthVisibilityUseCase()
        mask.update(mapOf("old" to OCCLUDED,"wall" to MATCHED),0)
        mask.update(mapOf("old" to OCCLUDED,"wall" to MATCHED),300_000_000)
        mask.pause();mask.update(mapOf("old" to UNKNOWN,"wall" to UNKNOWN),600_000_000)
        assertEquals(setOf("old"),mask.hiddenKeys())
        mask.update(mapOf("old" to MATCHED,"wall" to MATCHED),900_000_000)
        assertTrue(mask.hiddenKeys().isEmpty())
    }
    @Test fun singleOcclusionSpikeOrLongSamplingGapCannotHideWall() {
        val mask=LiveDepthVisibilityUseCase()
        mask.update(mapOf("wall" to OCCLUDED),0)
        mask.update(mapOf("wall" to UNKNOWN),300_000_000)
        mask.update(mapOf("wall" to OCCLUDED),600_000_000)
        assertTrue(mask.hiddenKeys().isEmpty())
        mask.update(mapOf("wall" to OCCLUDED),3_000_000_000)
        assertTrue(mask.hiddenKeys().isEmpty())
    }
    @Test fun drawingMaskDoesNotEraseRetainedProgressAndResetIsComplete() {
        val reveal=SurfaceRevealUseCase(keyOf={ "old" });val p=Point3(0f,0f,-2f)
        reveal.replaceObserved(listOf(p));reveal.update(pose(),0)
        val mask=LiveDepthVisibilityUseCase();mask.update(mapOf("old" to OCCLUDED),0)
        mask.update(mapOf("old" to OCCLUDED),300_000_000)
        assertTrue(reveal.isVisibleKey("old"));assertEquals(1,reveal.historyCount())
        mask.clear();reveal.clearMeasuredSurfaces()
        assertTrue(mask.hiddenKeys().isEmpty());assertEquals(0,reveal.historyCount())
    }
    @Test fun photoEvidenceRequiresTimeAndPoseAgreementAndMeasuredSurface() {
        val points=mapOf("wall" to Point3(0f,0f,-2f),"old" to Point3(0f,0f,-3f))
        assertEquals(setOf("wall"),query.photoMatches(points,frame(),pose(10_000_000),10_000_000))
        assertTrue(query.photoMatches(points,frame(),pose(200_000_000),200_000_000).isEmpty())
        assertTrue(query.photoMatches(points,frame(),pose(x=.1f),0).isEmpty())
    }
}
