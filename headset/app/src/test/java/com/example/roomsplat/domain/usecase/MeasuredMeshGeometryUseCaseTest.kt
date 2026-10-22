
package com.example.roomsplat.domain.usecase
import com.example.roomsplat.domain.model.Point3
import org.junit.Assert.*
import org.junit.Test

class MeasuredMeshGeometryUseCaseTest {
    private val use=MeasuredMeshGeometryUseCase()
    @Test fun boundsEncloseOffsetWallInsteadOfAssumingAnchorOriginIsCentre() {
        val points=listOf(Point3(2f,1f,-3f),Point3(5f,4f,-3f),Point3(2f,4f,-3f))
        val box=use.bounds(points)
        assertEquals(3.5f,box.centre.x,.0001f);assertEquals(2.5f,box.centre.y,.0001f)
        assertEquals(-3f,box.centre.z,.0001f)
        points.forEach { p ->
            assertTrue(kotlin.math.abs(p.x-box.centre.x)<=box.halfExtent.x)
            assertTrue(kotlin.math.abs(p.y-box.centre.y)<=box.halfExtent.y)
            assertTrue(kotlin.math.abs(p.z-box.centre.z)<=box.halfExtent.z)
        }
        assertTrue(box.halfExtent.z>0f)
    }
    @Test fun retessellatingSameWallDoesNotPretendToAddArea() {
        val p=listOf(Point3(0f,0f,0f),Point3(2f,0f,0f),Point3(2f,2f,0f),Point3(0f,2f,0f),Point3(1f,1f,0f))
        val old=use.area(p,listOf(0,1,2,0,2,3))
        val fine=use.area(p,listOf(0,1,4,1,2,4,2,3,4,3,0,4))
        assertEquals(4f,old,.0001f);assertEquals(old,fine,.0001f)
    }
    @Test fun fillingMeasuredGapAddsAreaWithoutInventingAPlane() {
        val p=listOf(Point3(0f,0f,0f),Point3(1f,0f,0f),Point3(1f,1f,0f),Point3(0f,1f,0f))
        assertEquals(.5f,use.area(p,listOf(0,1,2)),.0001f)
        assertEquals(1f,use.area(p,listOf(0,1,2,0,2,3)),.0001f)
    }
}
