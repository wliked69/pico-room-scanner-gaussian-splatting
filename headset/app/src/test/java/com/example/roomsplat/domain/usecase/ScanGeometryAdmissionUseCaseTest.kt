package com.example.roomsplat.domain.usecase

import org.junit.Assert.*
import org.junit.Test
import com.example.roomsplat.domain.usecase.ScanGeometryAdmissionUseCase.Event.*

class ScanGeometryAdmissionUseCaseTest {
    @Test fun delayedPreviousRoomEventsCannotEnterNewSubscription() {
        val use=ScanGeometryAdmissionUseCase<String>();val old=use.begin()
        assertTrue(use.event("old-wall",old,ADDED));use.reset();val next=use.begin()
        for(event in listOf(ADDED,UPDATED,LOADED,REMOVED)) assertFalse(use.event("old-wall",old,event))
        assertFalse(use.acceptsSnapshot("old-wall",next))
    }
    @Test fun oldSnapshotLoadedAndStartupReplayCannotReintroduceOldWall() {
        val use=ScanGeometryAdmissionUseCase<String>();val old=use.begin()
        use.event("old",old,ADDED);use.reset();val next=use.begin()
        assertFalse(use.event("old",next,LOADED));assertFalse(use.event("old",next,ADDED))
        assertFalse(use.event("unknown-history",next,LOADED));assertFalse(use.acceptsSnapshot("unknown-history",next))
    }
    @Test fun freshCurrentRoomAndRemeasuredExistingWallRemainAvailable() {
        val use=ScanGeometryAdmissionUseCase<String>();val old=use.begin()
        use.event("wall",old,ADDED);use.reset();val next=use.begin()
        assertTrue(use.event("new-room",next,ADDED));assertTrue(use.event("wall",next,UPDATED))
        assertTrue(use.acceptsSnapshot("wall",next));assertTrue(use.event("wall",next,LOADED))
    }
    @Test fun pauseInvalidatesCallbacksButResumeKeepsSameScanMembership() {
        val use=ScanGeometryAdmissionUseCase<String>();val old=use.begin()
        use.event("wall",old,ADDED);use.pause()
        assertFalse(use.event("wall",old,UPDATED));val next=use.begin()
        assertTrue(use.acceptsSnapshot("wall",next));assertFalse(use.current(old))
    }
    @Test fun removalRequiresFreshAddBeforeSnapshotCanReturn() {
        val use=ScanGeometryAdmissionUseCase<String>();val token=use.begin()
        use.event("wall",token,ADDED);use.event("wall",token,REMOVED)
        assertFalse(use.event("wall",token,LOADED));assertTrue(use.event("wall",token,ADDED))
    }
}
