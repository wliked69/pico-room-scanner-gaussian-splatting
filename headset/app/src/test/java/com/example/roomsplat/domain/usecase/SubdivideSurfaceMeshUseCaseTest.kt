package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import org.junit.Assert.*
import org.junit.Test

class SubdivideSurfaceMeshUseCaseTest {
    @Test fun refinedFacesRetainTheirOriginalSemanticSourceTriangle() {
        val points=listOf(Point3(0f,0f,0f),Point3(1f,0f,0f),Point3(0f,1f,0f),
            Point3(2f,0f,0f),Point3(3f,0f,0f),Point3(2f,1f,0f))
        val mesh=SubdivideSurfaceMeshUseCase().build(points,listOf(0,1,2,3,4,5))
        assertEquals(mesh.indices.size/3,mesh.sourceTriangles.size)
        assertEquals(setOf(0,1),mesh.sourceTriangles.toSet())
        mesh.indices.chunked(3).forEachIndexed { i,t ->
            val vertices=t.map { mesh.positions[it] }
            assertTrue(if(mesh.sourceTriangles[i]==0)vertices.all { it.x<=1f } else vertices.all { it.x>=2f })
        }
    }
    private val source = listOf(Point3(0f,0f,0f),Point3(1f,0f,0f),Point3(0f,1f,0f))
    private val engine=SubdivideSurfaceMeshUseCase()
    @Test fun fineTrianglesPreserveMeasuredPlaneAndArea() {
        val mesh=engine.build(source,listOf(0,1,2))
        assertTrue(mesh.indices.size>3)
        assertTrue(mesh.positions.all { it.z==0f && it.x>=0 && it.y>=0 && it.x+it.y<=1.00001f })
        var area=0f
        mesh.indices.chunked(3).forEach { t ->
            val a=mesh.positions[t[0]]; val b=mesh.positions[t[1]]; val c=mesh.positions[t[2]]
            area+=kotlin.math.abs((b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x))*.5f
            assertTrue((a-b).length()<=.121f && (b-c).length()<=.121f && (c-a).length()<=.121f)
        }
        assertEquals(.5f,area,.0001f)
    }
    @Test fun triangleBudgetPreventsUnboundedRefinement() {
        val mesh=engine.build(source,listOf(0,1,2),edgeM=.001f,maxTriangles=100)
        assertTrue(mesh.indices.size/3<=100)
        assertTrue(mesh.indices.all { it in mesh.positions.indices })
    }
    @Test fun invalidIndicesAreRejectedBeforeRendering() {
        try { engine.build(source,listOf(0,1,9)); fail("Expected invalid index") }
        catch (_:IllegalArgumentException) {}
    }
}
