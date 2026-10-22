package com.example.roomsplat.data.repository

import com.example.roomsplat.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class HeadPoseCacheTest {
    private fun pose(ms:Long,x:Float=0f,forward:Point3=Point3(0f,0f,-1f))=
        HeadPoseEvidence(ms*1_000_000,Point3(x,0f,0f),forward,Point3(1f,0f,0f),Point3(0f,1f,0f),emptyList())
    private fun history()=HeadPoseCache().apply { for(t in 0L..200L step 10L)add(pose(t)) }
    @Test fun stableCompleteWindowProducesLatestPose() {
        assertEquals(pose(200),history().stableSince(50_000_000,200_000_000))
    }
    @Test fun missingHistoryAndStaleTrackingDoNotProduceSensorPose() {
        val cache=HeadPoseCache().apply { add(pose(200)) }
        assertNull(cache.stableSince(50_000_000,200_000_000))
        assertNull(history().stableSince(50_000_000,261_000_000))
    }
    @Test fun motionAndTrackingGapWithinAcquisitionWindowRejectPose() {
        val moved=history().apply { add(pose(210,.03f)) }
        assertNull(moved.stableSince(50_000_000,210_000_000))
        val gap=HeadPoseCache().apply { add(pose(0));add(pose(150));add(pose(200)) }
        assertNull(gap.stableSince(0,200_000_000))
        val turned=history().apply { add(pose(210,forward=Point3(.06f,0f,-.9982f))) }
        assertNull(turned.stableSince(50_000_000,210_000_000))
    }
    @Test fun smallSensorJitterStillAllowsAdmission() {
        val cache=HeadPoseCache().apply { for(t in 0L..200L step 10L)add(pose(t,if(t%20==0L).003f else 0f)) }
        assertNotNull(cache.stableSince(50_000_000,200_000_000))
    }
}
