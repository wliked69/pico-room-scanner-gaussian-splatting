
package com.example.roomsplat.domain.usecase
import com.example.roomsplat.domain.model.SurfaceRecord
import org.junit.Assert.*
import org.junit.Test
class MeshRenderPlanUseCaseTest {
    @Test fun compactVerticesPreserveTriangleWindingAndColourGroups() {
        val source=listOf(6,4,2,2,4,7,7,4,5)
        val plan=MeshRenderPlanUseCase().build(source,byteArrayOf(0,1,2),8)
        SurfaceRecord.entries.forEachIndexed { i,status ->
            val g=plan.getValue(status)
            assertEquals(source.subList(i*3,i*3+3),g.localIndices.map { g.vertexSources[it] })
            assertEquals(3,g.vertexSources.size)
        }
    }
    @Test fun invisibleGeometryIsNotAccidentallySubmitted() {
        val plan=MeshRenderPlanUseCase().build(listOf(0,1,2,2,1,3),byteArrayOf(-1,2),4)
        assertTrue(plan.getValue(SurfaceRecord.UNRECORDED).localIndices.isEmpty())
        assertEquals(listOf(2,1,3),plan.getValue(SurfaceRecord.MULTIPLE_POSITIONS).globalIndices)
    }
    @Test fun sharedVerticesAreReferencedOnceWithoutChangingIndices() {
        val source=listOf(0,1,2,2,1,3)
        val g=MeshRenderPlanUseCase().build(source,byteArrayOf(0,0),4).getValue(SurfaceRecord.UNRECORDED)
        assertEquals(4,g.vertexSources.size);assertEquals(source,g.localIndices.map { g.vertexSources[it] })
    }
}
