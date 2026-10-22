package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class VerifiedCoverageProgressUseCaseTest {
    private val point=Point3(0f,0f,-2f)
    @Test fun geometryUpdatesAloneDoNotIncreaseDiscoveredOrPhotographedNumbers() {
        val raw=SurfaceCoverageUseCase();val progress=VerifiedCoverageProgressUseCase()
        repeat(100) { raw.observe(Point3(it*.1f,0f,-2f)) }
        assertEquals(100,raw.counts().first);assertEquals(Triple(0,0,0),progress.counts())
        progress.discover(mapOf("wall" to point))
        repeat(50) { progress.discover(mapOf("remesh-$it" to Point3(.01f,0f,-2f))) }
        assertEquals(Triple(1,0,0),progress.counts())
    }
    @Test fun repeatedSavedPhotosCannotInflateOneVerifiedRegion() {
        val progress=VerifiedCoverageProgressUseCase();progress.discover(mapOf("wall" to point))
        repeat(100) { progress.recordSaved(mapOf("alias" to (Point3(.01f,0f,-2f) to SurfaceRecord.ONE_POSITION))) }
        assertEquals(Triple(1,1,0),progress.counts())
        progress.recordSaved(mapOf("wall" to (point to SurfaceRecord.MULTIPLE_POSITIONS)))
        assertEquals(Triple(1,1,1),progress.counts())
    }
    @Test fun unknownOrUnverifiedPhotographedPointsCannotEnterProgress() {
        val progress=VerifiedCoverageProgressUseCase()
        progress.recordSaved(mapOf("unseen" to (point to SurfaceRecord.MULTIPLE_POSITIONS)))
        assertEquals(Triple(0,0,0),progress.counts())
        progress.discover(mapOf("wall" to point))
        progress.recordSaved(mapOf("far" to (Point3(2f,0f,-2f) to SurfaceRecord.ONE_POSITION)))
        assertEquals(Triple(1,0,0),progress.counts())
    }
    @Test fun nearbyAliasesCannotPropagateAcrossAnEntireWall() {
        val progress=VerifiedCoverageProgressUseCase();progress.discover(mapOf("a" to point))
        progress.discover(mapOf("b" to Point3(.025f,0f,-2f)))
        progress.discover(mapOf("c" to Point3(.05f,0f,-2f)))
        assertEquals(2,progress.counts().first)
    }
    @Test fun rediscoveryWhilePausedCannotInflateCountsOrCreatePhotoEvidence() {
        val progress=VerifiedCoverageProgressUseCase();progress.discover(mapOf("wall" to point))
        progress.recordSaved(mapOf("wall" to (point to SurfaceRecord.ONE_POSITION)))
        repeat(100) { progress.discover(mapOf("wall" to point)) }
        assertEquals(Triple(1,1,0),progress.counts())
        progress.resetPhotos();assertEquals(Triple(1,0,0),progress.counts())
        progress.clear();assertEquals(Triple(0,0,0),progress.counts())
    }
    @Test fun registryIsBoundedAndDoesNotRecycleExistingProgressIntoNewDiscoveries() {
        val progress=VerifiedCoverageProgressUseCase(2)
        for(i in 0..10)progress.discover(mapOf("$i" to Point3(i.toFloat(),0f,-2f)))
        assertEquals(2,progress.counts().first)
        assertEquals(2,progress.records().size)
    }
}
