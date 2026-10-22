package com.example.roomsplat.domain.usecase

import org.junit.Assert.*
import org.junit.Test

class ActiveSceneOwnerUseCaseTest {
    @Test fun takingOverRetiresPreviousSceneExactlyOnce() {
        val owners=ActiveSceneOwnerUseCase<Any>();val first=Any();val next=Any()
        val retired=mutableListOf<Any>()
        owners.claim(first,retired::add);assertTrue(retired.isEmpty())
        owners.claim(first,retired::add);assertTrue(retired.isEmpty())
        owners.claim(next,retired::add);assertEquals(listOf(first),retired)
        owners.claim(next,retired::add);assertEquals(1,retired.size)
    }
    @Test fun previousCloseDuringTakeoverCannotReleaseSuccessor() {
        val owners=ActiveSceneOwnerUseCase<Any>();val first=Any();val next=Any();val third=Any()
        owners.claim(first) { fail("No prior owner") }
        owners.claim(next) { old -> assertSame(first,old);owners.release(old) }
        owners.claim(third) { old -> assertSame(next,old) }
    }
    @Test fun lateOldCompositionDisposalDoesNotForgetActiveScene() {
        val owners=ActiveSceneOwnerUseCase<Any>();val first=Any();val next=Any();val third=Any()
        owners.claim(first) { fail("No prior owner") }
        owners.claim(next) { }
        owners.release(first)
        owners.claim(third) { old -> assertSame(next,old) }
    }
    @Test fun releasingCurrentOwnerAvoidsRetiringItAgain() {
        val owners=ActiveSceneOwnerUseCase<Any>();val first=Any();val next=Any()
        owners.claim(first) { fail("No prior owner") };owners.release(first)
        owners.claim(next) { fail("Released scene must not be retired again") }
    }
}
