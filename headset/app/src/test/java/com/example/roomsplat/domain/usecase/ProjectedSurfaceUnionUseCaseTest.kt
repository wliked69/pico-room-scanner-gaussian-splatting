package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.usecase.RegularPlaneMeshUseCase.UV
import org.junit.Assert.*
import org.junit.Test

class ProjectedSurfaceUnionUseCaseTest {
    private val use=ProjectedSurfaceUnionUseCase()
    private val triangle=listOf(UV(0.0,0.0),UV(1.0,0.0),UV(0.0,1.0))
    @Test fun adjoiningFacesCoverACompleteTriangle() {
        val a=listOf(UV(0.0,0.0),UV(1.0,0.0),UV(.5,.5))
        val b=listOf(UV(0.0,0.0),UV(.5,.5),UV(0.0,1.0))
        assertTrue(use.covers(triangle,listOf(a,b)))
        assertTrue(use.covers(triangle,listOf(b.reversed(),a.reversed())))
    }
    @Test fun overlappingOwnersDoNotDoubleCountSupportAcrossAnUncoveredArea() {
        val partial=listOf(UV(0.0,0.0),UV(.8,0.0),UV(0.0,.8))
        assertFalse(use.covers(triangle,listOf(partial,partial)))
    }
    @Test fun smallOpeningRemainsUncovered() {
        fun rect(x0:Double,y0:Double,x1:Double,y1:Double)=listOf(
            listOf(UV(x0,y0),UV(x1,y0),UV(x1,y1)),listOf(UV(x0,y0),UV(x1,y1),UV(x0,y1)))
        val faces=rect(0.0,0.0,.30,1.0)+rect(.31,0.0,1.0,1.0)+
            rect(.30,0.0,.31,.30)+rect(.30,.31,.31,1.0)
        assertFalse(use.covers(triangle,faces))
    }
}
