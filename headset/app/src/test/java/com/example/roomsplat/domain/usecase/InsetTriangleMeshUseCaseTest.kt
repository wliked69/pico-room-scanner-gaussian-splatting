package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import org.junit.Assert.*
import org.junit.Test

class InsetTriangleMeshUseCaseTest {
    private val points=listOf(Point3(0f,0f,-2f),Point3(1f,0f,-2f),Point3(1f,1f,-2f),Point3(0f,1f,-2f))
    @Test fun sharedDiagonalHasAVisibleGapRatherThanAJoinedColourBlock() {
        val mesh=InsetTriangleMeshUseCase().build(points,listOf(0,1,2,0,2,3))
        assertEquals(6,mesh.positions.size);assertEquals((0..5).toList(),mesh.indices)
        assertTrue(mesh.positions[0].x>mesh.positions[0].y)
        assertTrue(mesh.positions[3].y>mesh.positions[3].x)
        assertEquals(2,mesh.indices.size/3)
    }
    @Test fun centroidPlaneAndWindingRemainUnchanged() {
        val mesh=InsetTriangleMeshUseCase().build(points,listOf(0,1,2))
        assertTrue(mesh.positions.all { it.z==-2f })
        assertEquals(2f/3f,mesh.positions.sumOf { it.x.toDouble() }.toFloat()/3f,.00001f)
        assertEquals(1f/3f,mesh.positions.sumOf { it.y.toDouble() }.toFloat()/3f,.00001f)
        val a=mesh.positions[1]-mesh.positions[0];val b=mesh.positions[2]-mesh.positions[0]
        assertTrue(a.x*b.y-a.y*b.x>0)
    }
    @Test fun unitScaleReproducesTheMeasuredTriangleAndEmptyInputIsEmpty() {
        val use=InsetTriangleMeshUseCase()
        assertEquals(points.take(3),use.build(points,listOf(0,1,2),1f).positions)
        assertTrue(use.build(emptyList(),emptyList()).positions.isEmpty())
    }
    @Test(expected=IllegalArgumentException::class) fun invalidScaleIsRejected() {
        InsetTriangleMeshUseCase().build(points,listOf(0,1,2),0f)
    }
}
