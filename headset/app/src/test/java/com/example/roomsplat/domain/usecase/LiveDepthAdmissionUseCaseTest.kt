package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LiveDepthAdmissionUseCaseTest {
    private val now=1_000_000_000L
    private val query=LiveDepthAdmissionUseCase()
    private fun pose(t:Long=now,position:Point3=Point3(0f,0f,0f),
        forward:Point3=Point3(0f,0f,-1f),right:Point3=Point3(1f,0f,0f),
        up:Point3=Point3(0f,1f,0f))=HeadPoseEvidence(t,position,forward,right,up,listOf(0f,0f,0f,1f))
    private fun frame(z:Float=2f,t:Long=now,p:HeadPoseEvidence=pose())=
        LiveDepthFrame(p,t,160,128,FloatArray(160*128) { z })

    @Test fun measuredWallMatchesButOldGeometryBehindOrInFrontDoesNot() {
        val points=mapOf("wall" to Point3(0f,0f,-2f),"old" to Point3(0f,0f,-3f),"ghost" to Point3(0f,0f,-1f))
        assertEquals(setOf("old","ghost"),query.blocked(points,frame(),pose(),now))
    }
    @Test fun wideViewUsesForwardDepthInsteadOfRadialDistance() {
        assertTrue(query.matches(Point3(2f,1f,-2f),frame()))
        assertFalse(query.matches(Point3(2f,1f,-2f),frame(3f)))
    }
    @Test fun fixedFovAndBehindHeadNeverAdmit() {
        listOf(Point3(0f,0f,2f),Point3(3f,0f,-2f),Point3(0f,2.1f,-2f),
            Point3(Float.NaN,0f,-2f)).forEach { assertFalse(query.matches(it,frame())) }
    }
    @Test fun imageTopIsPositiveUpAndImageRightIsPositiveRight() {
        val values=FloatArray(160*128) { Float.NaN }
        // world(1,.5,-2) maps to x108,y48. Its mirrored pixels contain no depth.
        for(y in 47..49)for(x in 107..109)values[y*160+x]=2f
        val f=LiveDepthFrame(pose(),now,160,128,values)
        assertTrue(query.matches(Point3(1f,.5f,-2f),f))
        assertFalse(query.matches(Point3(-1f,.5f,-2f),f))
        assertFalse(query.matches(Point3(1f,-.5f,-2f),f))
    }
    @Test fun projectionUsesStagePoseRatherThanWorldAxes() {
        val p=pose(position=Point3(4f,1f,3f),forward=Point3(1f,0f,0f),right=Point3(0f,0f,1f))
        assertTrue(query.matches(Point3(6f,1f,3f),frame(p=p)))
        assertFalse(query.matches(Point3(4f,1f,1f),frame(p=p)))
    }
    @Test fun isolatedPixelCannotCertifySurfaceButCoherentNeighbourhoodCan() {
        val values=FloatArray(160*128) { 0f };values[65*160+81]=2f
        assertFalse(query.matches(Point3(0f,0f,-2f),LiveDepthFrame(pose(),now,160,128,values)))
        for(y in 63..65)for(x in 79..81)values[y*160+x]=2f
        assertTrue(query.matches(Point3(0f,0f,-2f),LiveDepthFrame(pose(),now,160,128,values)))
    }
    @Test fun boundedDisplayToleranceDoesNotAcceptASeparateWall() {
        assertTrue(query.matches(Point3(0f,0f,-2.19f),frame()))
        assertFalse(query.matches(Point3(0f,0f,-2.21f),frame()))
    }
    @Test fun missingStaleAndFutureFrameHoldAllUnseenCells() {
        val points=mapOf("a" to Point3(0f,0f,-2f))
        listOf(null,frame(t=now-251_000_000),frame(t=now+1)).forEach {
            assertEquals(setOf("a"),query.blocked(points,it,pose(),now))
        }
    }
    @Test fun translationYawAndRollInvalidateFramePose() {
        val f=frame()
        assertFalse(query.usable(f,pose(position=Point3(.021f,0f,0f)),now))
        assertFalse(query.usable(f,pose(forward=Point3(.052336f,0f,-.99863f)),now))
        assertFalse(query.usable(f,pose(up=Point3(.052336f,.99863f,0f)),now))
        assertTrue(query.usable(f,pose(position=Point3(.01f,0f,0f)),now))
    }
    @Test fun revealedProgressStaysThroughMismatchAndResetCannotReplayOldGeometry() {
        val points=mapOf("wall" to Point3(0f,0f,-2f),"old" to Point3(.1f,0f,-3f))
        val reveal=SurfaceRevealUseCase(keyOf={ point->points.entries.first { it.value==point }.key })
        reveal.replaceObservedKeyed(points)
        reveal.update(pose(),now,query.blocked(points,frame(),pose(),now))
        assertTrue(reveal.isVisibleKey("wall"));assertFalse(reveal.isVisibleKey("old"))
        val later=now+1_000_000_000
        reveal.update(pose(later),later,points.keys)
        assertTrue(reveal.isVisibleKey("wall"));assertFalse(reveal.isVisibleKey("old"))
        reveal.clearMeasuredSurfaces();reveal.replaceObservedKeyed(points)
        reveal.update(pose(later),later,query.blocked(points,null,pose(later),later))
        assertEquals(0,reveal.visibleCount())
    }
    @Test fun losingDepthCancelsPendingEdgeRevealInsteadOfLeakingItLater() {
        val point=Point3(2f,0f,-2f);val points=mapOf("edge" to point)
        val reveal=SurfaceRevealUseCase(keyOf={ "edge" });reveal.replaceObservedKeyed(points)
        reveal.update(pose(),now,query.blocked(points,frame(),pose(),now))
        assertFalse(reveal.isVisibleKey("edge"))
        val later=now+900_000_000
        reveal.update(pose(later),later,query.blocked(points,null,pose(later),later))
        assertFalse(reveal.isVisibleKey("edge"))
    }
}
