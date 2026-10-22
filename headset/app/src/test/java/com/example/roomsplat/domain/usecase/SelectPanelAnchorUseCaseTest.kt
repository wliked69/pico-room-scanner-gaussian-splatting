
package com.example.roomsplat.domain.usecase
import com.example.roomsplat.domain.model.Point3
import com.example.roomsplat.domain.usecase.SelectPanelAnchorUseCase.Sample
import com.example.roomsplat.domain.usecase.SelectPanelAnchorUseCase.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectPanelAnchorUseCaseTest {
    private val useCase = SelectPanelAnchorUseCase()
    private val now = 1_000_000_000L
    private val head = Point3(0f, 1.6f, 0f)
    private val hand = Sample(Point3(-.3f, 1.2f, -.5f), now)
    private val controller = Sample(Point3(-.4f, 1.1f, -.4f), now)
    @Test fun validHandHasPriority() {
        assertEquals(Source.LEFT_HAND, useCase.select(now, head, hand, controller, true))
    }
    @Test fun lostOrStaleHandUsesController() {
        assertEquals(Source.LEFT_CONTROLLER, useCase.select(now, head, hand.copy(receivedNanos=1), controller, true))
        assertEquals(Source.LEFT_CONTROLLER, useCase.select(now, head, null, controller, true))
    }
    @Test fun invalidAndUnreachableSamplesCannotStealPanel() {
        listOf(Point3(0f,0f,0f), Point3(Float.NaN,1f,1f), Point3(4f,1f,1f)).forEach {
            assertEquals(Source.HEAD, useCase.select(now, head, Sample(it,now), null, true))
        }
        assertEquals(Source.HEAD, useCase.select(now, head, null, controller.copy(receivedNanos=1), true))
    }
    @Test fun headSwitchAndMissingHeadAlwaysRecoverControls() {
        assertEquals(Source.HEAD, useCase.select(now, head, hand, controller, false))
        assertEquals(Source.HEAD, useCase.select(now, null, hand, controller, true))
    }
}
