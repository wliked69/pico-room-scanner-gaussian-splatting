package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class BakeMeshDisplayFrameUseCaseTest {
    private val use=BakeMeshDisplayFrameUseCase()
    private val identity=listOf(0f,0f,0f,1f)
    @Test fun nativeOriginShiftCompensatedByLocalVerticesLeavesDisplayedWallFixed() {
        val points=listOf(Point3(0f,1f,-2f),Point3(1f,1f,-2f),Point3(0f,2f,-2f))
        val first=use.build(points,Point3(0f,0f,0f),identity)
        val later=use.build(points.map { it.copy(x=it.x-.5f) },Point3(.5f,0f,0f),identity)
        assertEquals(first,later);assertEquals(Point3(0f,1f,-2f),points[0])
    }
    @Test fun rightHandedRotationAndTranslationAreBakedIntoEveryPoint() {
        val h=sqrt(.5f)
        val p=use.build(listOf(Point3(0f,0f,-1f)),Point3(1f,2f,3f),listOf(0f,h,0f,h)).single()
        assertEquals(0f,p.x,.00001f);assertEquals(2f,p.y,.00001f);assertEquals(3f,p.z,.00001f)
    }
    @Test fun finiteScaledQuaternionIsNormalizedWithoutDistortingGeometry() {
        assertEquals(listOf(Point3(1f,2f,3f)),
            use.build(listOf(Point3(1f,2f,3f)),Point3(0f,0f,0f),listOf(0f,0f,0f,2f)))
    }
    @Test(expected=IllegalArgumentException::class) fun zeroQuaternionIsRejected() {
        use.build(listOf(Point3(0f,0f,0f)),Point3(0f,0f,0f),listOf(0f,0f,0f,0f))
    }
}
