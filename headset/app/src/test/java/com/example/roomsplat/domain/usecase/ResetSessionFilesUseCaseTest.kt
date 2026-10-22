package com.example.roomsplat.domain.usecase

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResetSessionFilesUseCaseTest {
    @get:Rule val folder=TemporaryFolder()
    private val engine=ResetSessionFilesUseCase()
    @Test fun resetDetachesCurrentPhotosAndPreservesExportedZip() {
        val storage=folder.root
        val current=File(storage,"sessions/session-current").apply { mkdirs() }
        File(current,"images").mkdirs(); File(current,"images/frame.jpg").writeText("photo")
        val zip=File(storage,"exports/keep.zip").apply { checkNotNull(parentFile).mkdirs(); writeText("export") }
        val detached=engine.detach(storage,current)
        assertFalse(current.exists()); assertTrue(File(detached,"images/frame.jpg").exists())
        engine.purge(storage,detached)
        assertFalse(detached.exists()); assertEquals("export",zip.readText())
    }
    @Test fun outsideSessionAndParentDirectoryCannotBeReset() {
        val other=File(folder.root,"other/session-foreign").apply { mkdirs() }
        File(other,"keep").writeText("keep")
        try { engine.detach(folder.root,other); fail("Expected path rejection") } catch (_:IllegalArgumentException) {}
        assertTrue(File(other,"keep").exists())
        val sessions=File(folder.root,"sessions").apply { mkdirs() }
        try { engine.detach(folder.root,sessions); fail("Expected parent rejection") } catch (_:IllegalArgumentException) {}
        assertTrue(sessions.exists())
    }
    @Test fun purgeRejectsAnActiveSession() {
        val active=File(folder.root,"sessions/session-active").apply { mkdirs() }
        File(active,"keep").writeText("keep")
        try { engine.purge(folder.root,active); fail("Expected purge rejection") } catch (_:IllegalArgumentException) {}
        assertEquals("keep",File(active,"keep").readText())
    }
}
