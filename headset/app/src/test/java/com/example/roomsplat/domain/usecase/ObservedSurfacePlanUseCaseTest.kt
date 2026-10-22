package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import org.junit.Assert.*
import org.junit.Test

class ObservedSurfacePlanUseCaseTest {
    @Test fun sharedCellKeepsEveryOwnerAndTheLastPointLikePreviousAssociation() {
        val a=Point3(0f,1f,-2f);val b=a.copy(x=.02f);val c=Point3(1f,1f,-2f)
        val plan=ObservedSurfacePlanUseCase<String>().build(listOf(
            ObservedSurfacePlanUseCase.Anchor("a",listOf(a,c),listOf("shared","other")),
            ObservedSurfacePlanUseCase.Anchor("b",listOf(b),listOf("shared"))))
        assertEquals(b,plan.points["shared"])
        assertEquals(setOf("a","b"),plan.owners["shared"])
        assertEquals(setOf("a"),plan.owners["other"])
    }
    @Test fun aNewSnapshotDoesNotRetainRemovedOwnersOrMutatePreviousSnapshot() {
        val use=ObservedSurfacePlanUseCase<String>();val p=Point3(0f,0f,-2f)
        val old=use.build(listOf(ObservedSurfacePlanUseCase.Anchor("old",listOf(p),listOf("key"))))
        val empty=use.build(emptyList())
        assertTrue(empty.points.isEmpty());assertTrue(empty.owners.isEmpty())
        assertEquals(setOf("old"),old.owners["key"])
    }
    @Test(expected=IllegalArgumentException::class) fun mismatchedGeometryAndKeysAreRejected() {
        ObservedSurfacePlanUseCase<String>().build(listOf(
            ObservedSurfacePlanUseCase.Anchor("bad",listOf(Point3(0f,0f,0f)),emptyList())))
    }
}
