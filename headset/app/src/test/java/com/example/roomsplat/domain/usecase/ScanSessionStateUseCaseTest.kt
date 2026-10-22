package com.example.roomsplat.domain.usecase

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanSessionStateUseCaseTest {
    @get:Rule val folder=TemporaryFolder()
    @Test fun externalPermissionDeniedMeansNoRecoveryInsteadOfResetFailure() {
        val legacy=File(folder.root,"legacy")
        assertEquals("",ScanSessionStateUseCase.readOrEmpty(legacy) { throw IOException("EACCES") })
        assertEquals("",ScanSessionStateUseCase.readOrEmpty(legacy) { throw SecurityException("denied") })
    }
    @Test fun privateStateOverridesOldRoomAndRepeatedResetWrites() {
        val legacy=File(folder.root,"active-session.txt").apply { writeText("session-old") }
        val marker=File(folder.root,"private/active-session.txt")
        val use=ScanSessionStateUseCase(marker)
        assertEquals(legacy,use.currentMarker(legacy))
        use.write("session-new")
        assertEquals(marker,use.currentMarker(legacy));assertEquals("session-new",marker.readText())
        use.write("");use.write("")
        assertEquals("",marker.readText());assertEquals("session-old",legacy.readText())
        assertEquals(listOf("active-session.txt"),marker.parentFile!!.list()!!.toList())
    }
    @Test fun unreadableLegacyDoesNotRecoverAnOlderRoomByFallback() {
        val storage=folder.root
        File(storage,"sessions/session-old/frames.jsonl").apply { parentFile!!.mkdirs();writeText("{}") }
        val invalid=File(storage,"active-session.txt").apply { mkdirs() }
        assertNull(FindRecoverableSessionUseCase().find(storage,invalid))
    }
    @Test fun deletedSessionPrivateMarkerDoesNotRestoreAnotherRoom() {
        val storage=folder.root
        File(storage,"sessions/session-other/frames.jsonl").apply { parentFile!!.mkdirs();writeText("{}") }
        val marker=File(storage,"private/active-session.txt")
        ScanSessionStateUseCase(marker).write("session-deleted")
        assertNull(FindRecoverableSessionUseCase().find(storage,marker))
    }
    @Test fun invalidNamesCannotReplaceGoodPrivateState() {
        val marker=File(folder.root,"private/active-session.txt");val use=ScanSessionStateUseCase(marker)
        use.write("session-safe")
        try { use.write("../bad");fail() } catch (_:IllegalArgumentException) {}
        assertEquals("session-safe",marker.readText())
    }
}
