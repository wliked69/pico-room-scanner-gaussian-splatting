package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class FacePanelAtHeadUseCaseTest {
    private val use=FacePanelAtHeadUseCase()
    private fun front(q: FacePanelAtHeadUseCase.Rotation)=Point3(
        2*(q.x*q.z+q.w*q.y),2*(q.y*q.z-q.w*q.x),1-2*(q.x*q.x+q.y*q.y))
    private fun faces(panel: Point3,head: Point3) {
        val q=use.rotation(panel,head)!!
        val d=head-panel;val f=front(q)
        assertEquals(1f,f.dot(d)/d.length(),.00001f)
        assertEquals(1f,sqrt(q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w),.00001f)
    }
    @Test fun leftAndRightHandsFaceTheActualViewerPosition() {
        val head=Point3(0f,1.6f,0f)
        faces(Point3(-.6f,1.2f,-.5f),head);faces(Point3(.6f,1.2f,-.5f),head)
    }
    @Test fun headTranslationUpdatesDirectionEvenWithoutChangingHeadRotation() {
        val panel=Point3(-.4f,1.2f,-.6f)
        faces(panel,Point3(0f,1.6f,0f));faces(panel,Point3(.5f,1.7f,.2f))
    }
    @Test fun cameraFallbackAlsoPointsToTheCameraCentre() {
        faces(Point3(-.65f,-.28f,-1.4f),Point3(0f,0f,0f))
    }
    @Test fun elevatedAndRearPositionsHaveFiniteCorrectNormals() {
        faces(Point3(0f,0f,0f),Point3(0f,2f,0f))
        faces(Point3(0f,0f,0f),Point3(.2f,1f,-2f))
    }
    @Test fun invalidOrCoincidentPositionsCannotProduceInvalidRotations() {
        assertNull(use.rotation(Point3(0f,0f,0f),Point3(0f,0f,0f)))
        assertNull(use.rotation(Point3(Float.NaN,0f,0f),Point3(0f,1f,1f)))
    }
}
