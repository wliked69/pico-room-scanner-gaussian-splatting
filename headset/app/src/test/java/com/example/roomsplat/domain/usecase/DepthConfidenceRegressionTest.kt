package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.*
import com.example.roomsplat.domain.usecase.LiveDepthAdmissionUseCase.Visibility.*
import org.junit.Assert.*
import org.junit.Test

class DepthConfidenceRegressionTest {
    private val now=1_000_000_000L
    private fun pose()=HeadPoseEvidence(now,Point3(0f,0f,0f),Point3(0f,0f,-1f),
        Point3(1f,0f,0f),Point3(0f,1f,0f),emptyList())
    private fun frame(values:FloatArray,start:Long=now)=LiveDepthFrame(pose(),now,160,128,values,start)
    private fun patch(values:List<Float>):FloatArray=FloatArray(160*128) { 0f }.also { a ->
        var i=0;for(y in 63..65)for(x in 79..81)a[y*160+x]=values[i++]
    }
    @Test fun syntheticMinorityFarPixelsCannotAdmitBehindForeground() {
        // Entirely synthetic values: six near samples and three minority far samples.
        val f=frame(patch(listOf(3.4f,1.2f,1.21f,3.42f,1.19f,1.2f,3.38f,1.22f,1.18f)))
        val query=LiveDepthAdmissionUseCase()
        assertEquals(OCCLUDED,query.visibility(Point3(0f,0f,-3.4f),f))
        assertEquals(MATCHED,query.visibility(Point3(0f,0f,-1.2f),f))
        val visible=SurfaceRevealUseCase(keyOf={ "old" })
        visible.replaceObserved(listOf(Point3(0f,0f,-3.4f)))
        val points=mapOf("old" to Point3(0f,0f,-3.4f))
        visible.update(pose(),now,query.blocked(points,f,pose(),now))
        assertEquals(0,visible.visibleCount())
    }
    @Test fun aMinorityDistantCentreAtDoorEdgeIsUnknownInsteadOfClosingTheOpening() {
        val values=MutableList(9) { 1f };values[4]=3f
        val f=frame(patch(values))
        assertEquals(UNKNOWN,LiveDepthAdmissionUseCase().visibility(Point3(0f,0f,-3f),f))
    }
    @Test fun resetRejectsOldAcquisitionEvenWhenCallbackAndHeadPoseAreFresh() {
        val values=FloatArray(160*128) { 2f };val query=LiveDepthAdmissionUseCase()
        val old=frame(values,800_000_000);val fresh=frame(values,950_000_000)
        val points=mapOf("wall" to Point3(0f,0f,-2f));val reset=900_000_000L
        assertTrue(query.usable(old,pose(),now))
        assertEquals(points.keys,query.blocked(points,old,pose(),now,reset))
        assertTrue(query.blocked(points,fresh,pose(),now,reset).isEmpty())
        assertTrue(query.photoMatches(points,old,pose(),now,reset).isEmpty())
        assertFalse(query.drawUsable(old,pose(),now,reset))
    }
    @Test fun preparedFrameCopiesCallerDataAndRetainsOneStableBandMap() {
        val values=FloatArray(160*128) { 2f };val f=frame(values)
        val first=f.bands;values.fill(5f)
        assertSame(first,f.bands);assertEquals(2f,f.at(80,64),0f)
        assertEquals(MATCHED,LiveDepthAdmissionUseCase().visibility(Point3(0f,0f,-2f),f))
    }
    @Test fun concurrentPixelPreparationIsLazyAndDoesNotMixNeighbourhoods() {
        val values=FloatArray(160*128)
        val centres=(0 until 64).map { i -> Triple(3+(i%8)*4,3+(i/8)*4,.5f+i*.08f) }
        centres.forEach { (x,y,z) -> for(row in y-1..y+1)for(col in x-1..x+1)values[row*160+col]=z }
        val bands=frame(values).bands
        assertEquals(0,bands.preparedPixels)
        val executor=java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val jobs=(0 until 8).map { offset -> executor.submit {
                repeat(4) {
                    for(i in centres.indices) {
                        val (x,y,z)=centres[(i+offset)%centres.size]
                        assertTrue(bands.supported(x,y))
                        assertEquals(z,bands.near(x,y),0f);assertEquals(z,bands.far(x,y),0f)
                    }
                    assertFalse(bands.supported(0,0))
                    assertFalse(bands.supported(100,100))
                }
            } }
            jobs.forEach { it.get(10,java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(66,bands.preparedPixels)
        } finally { executor.shutdownNow() }
    }
    @Test fun queryReuseRequiresBothIdenticalFrameAndGeometryAndSurvivesResetInvalidation() {
        val cache=LiveDepthQueryCacheUseCase();val points=mapOf("a" to Point3(0f,0f,-2f))
        val f=frame(FloatArray(160*128) { 2f });val first=cache.evaluate(points,f)
        assertSame(first,cache.evaluate(points,f));assertEquals(1L,cache.hits)
        val changed=mapOf("a" to Point3(0f,0f,-3f));val second=cache.evaluate(changed,f)
        assertNotSame(first,second);assertEquals(setOf("a"),second.blocked)
        cache.clear();assertNotSame(second,cache.evaluate(changed,f))
        val newFrame=frame(FloatArray(160*128) { 3f })
        assertTrue(cache.evaluate(changed,newFrame).blocked.isEmpty())
    }
    @Test fun fasterSpreadStillShowsCentreFirstAndRetainsBlockedEdges() {
        val key=SurfaceCoverageUseCase()::key
        val use=SurfaceRevealUseCase(key,spreadNanos=350_000_000)
        val centre=Point3(0f,0f,-2f);val edge=Point3(2.4f,0f,-2f)
        use.replaceObserved(listOf(centre,edge));use.update(pose().copy(receivedNanos=0),0,maxNewCells=128)
        assertTrue(use.isVisible(centre));assertFalse(use.isVisible(edge))
        use.update(pose().copy(receivedNanos=400_000_000),400_000_000,blocked=setOf(key(edge)),maxNewCells=128)
        assertFalse(use.isVisible(edge))
        use.update(pose().copy(receivedNanos=500_000_000),500_000_000,maxNewCells=128)
        use.update(pose().copy(receivedNanos=900_000_000),900_000_000,maxNewCells=128)
        assertTrue(use.isVisible(edge))
    }
}
