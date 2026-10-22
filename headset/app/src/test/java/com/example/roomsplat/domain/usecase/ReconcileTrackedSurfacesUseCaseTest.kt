package com.example.roomsplat.domain.usecase

import org.junit.Assert.*
import org.junit.Test

class ReconcileTrackedSurfacesUseCaseTest {
    @Test fun snapshotCannotRestoreAnAnchorUpdatedOrRemovedAfterLoadBegan() {
        assertFalse(use.acceptLoaded("removed",mapOf("removed" to 11L),10))
        assertFalse(use.acceptLoaded("updated",mapOf("updated" to 10L),10))
    }
    @Test fun freshSnapshotAcceptsOlderEventsOrPreviouslyUnknownAnchors() {
        assertTrue(use.acceptLoaded("old",mapOf("old" to 1L),10))
        assertTrue(use.acceptLoaded("new",emptyMap(),10))
    }
    private val use=ReconcileTrackedSurfacesUseCase<String>()
    @Test fun oldAnchorMissingFromResumedSnapshotIsRemoved() {
        assertEquals(setOf("old"),use.removed(setOf("old","kept"),setOf("kept"),mapOf("old" to 1L),10))
    }
    @Test fun liveEventArrivingDuringSnapshotLoadProtectsItsAnchor() {
        assertEquals(emptySet<String>(),use.removed(setOf("live"),setOf("another"),mapOf("live" to 11L),10))
    }
    @Test fun emptyPausedProviderDoesNotEraseCachedRoom() {
        assertEquals(emptySet<String>(),use.removed(setOf("wall"),emptySet(),emptyMap(),10))
    }
}
