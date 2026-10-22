
package com.example.roomsplat.domain.usecase
import org.junit.Assert.*
import org.junit.Test

class LatestMeshUpdateUseCaseTest {
    @Test fun removedAndReaddedAnchorDoesNotReuseAnOldTicket() {
        val box=LatestMeshUpdateUseCase<String>()
        box.request("wall",true);val old=box.take()!!
        box.remove("wall");box.request("wall",true)
        assertFalse(box.isCurrent(old))
        assertTrue(box.isCurrent(box.take()!!))
    }
    @Test fun fullResetRejectsOldWorkEvenWhenSameAnchorIsReloaded() {
        val box=LatestMeshUpdateUseCase<String>()
        box.request("wall",true);val old=box.take()!!
        box.clear();box.request("wall",true)
        val fresh=box.take()!!
        assertFalse(box.isCurrent(old));assertTrue(box.isCurrent(fresh))
        box.retry(old);box.complete(old)
        assertNull(box.take())
        box.complete(fresh);box.request("wall")
        assertFalse(box.take()!!.geometryChanged)
    }
    @Test fun repeatedRequestsCoalesceAndKeepGeometryFlag() {
        val box=LatestMeshUpdateUseCase<String>()
        box.request("wall",true);repeat(10){box.request("wall")}
        assertEquals(1,box.size());val ticket=box.take()!!
        assertTrue(ticket.geometryChanged);assertTrue(box.isCurrent(ticket))
    }
    @Test fun resetOrNewColourInvalidatesAnInFlightPlan() {
        val box=LatestMeshUpdateUseCase<String>();box.request("wall",true)
        val old=box.take()!!;box.request("wall")
        assertFalse(box.isCurrent(old));box.complete(old)
        val fresh=box.take()!!
        assertTrue(fresh.geometryChanged);assertTrue(box.isCurrent(fresh))
    }
    @Test fun pauseRetryDoesNotLoseAConsumedGeometryUpdate() {
        val box=LatestMeshUpdateUseCase<String>();box.request("wall",true)
        val first=box.take()!!;box.retry(first)
        val resumed=box.take()!!;assertTrue(resumed.geometryChanged)
        box.complete(resumed);box.request("wall")
        assertFalse(box.take()!!.geometryChanged)
    }
    @Test fun removingAnchorCannotBeUndoneByLateCompletion() {
        val box=LatestMeshUpdateUseCase<String>();box.request("wall",true);val old=box.take()!!
        box.remove("wall");box.retry(old);box.complete(old)
        assertFalse(box.isCurrent(old));assertNull(box.take())
    }

    @Test fun newlyRevealedSurfaceRunsBeforeAnOlderColourUpdate() {
        val box=LatestMeshUpdateUseCase<String>()
        box.request("old");box.request("new",geometryChanged=true,prioritize=true)
        assertEquals("new",box.take()!!.key)
        assertEquals("old",box.take()!!.key)
    }
    @Test fun continuousUrgentWorkCannotStarveTheOrdinaryQueue() {
        val box=LatestMeshUpdateUseCase<String>();box.request("old")
        repeat(4){box.request("visible$it",prioritize=true)}
        assertEquals("visible0",box.take()!!.key)
        assertEquals("visible1",box.take()!!.key)
        assertEquals("old",box.take()!!.key)
    }
    @Test fun removingAnUrgentAnchorCannotResurrectIt() {
        val box=LatestMeshUpdateUseCase<String>()
        box.request("gone",prioritize=true);box.remove("gone");box.request("kept")
        assertEquals("kept",box.take()!!.key);assertNull(box.take())
    }
}
