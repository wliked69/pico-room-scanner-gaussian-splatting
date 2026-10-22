package com.example.roomsplat.domain.usecase

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FindRecoverableSessionUseCaseTest {
    @get:Rule val folder=TemporaryFolder()
    private val engine=FindRecoverableSessionUseCase()
    private fun session(name:String):File = File(folder.root,"sessions/$name").apply {
        mkdirs(); File(this,"frames.jsonl").writeText("{}")
    }
    @Test fun resetMarkerPreventsRestoringOlderUnfinishedScan() {
        session("session-older")
        File(folder.root,"active-session.txt").writeText("")
        assertNull(engine.find(folder.root))
    }
    @Test fun explicitNewScanCanRecoverAfterReset() {
        session("session-older"); val current=session("session-current")
        File(folder.root,"active-session.txt").writeText(current.name)
        assertEquals(current.canonicalFile,engine.find(folder.root)?.canonicalFile)
    }
    @Test fun exportedScanAndUnsafeMarkerAreNotRecovered() {
        val exported=session("session-exported"); File(exported,"exported.txt").writeText("zip")
        File(folder.root,"active-session.txt").writeText(exported.name)
        assertNull(engine.find(folder.root))
        File(folder.root,"active-session.txt").writeText("../session-outside")
        assertNull(engine.find(folder.root))
    }
    @Test fun legacyScanCanStillRecoverBeforeFirstReset() {
        val old=session("session-legacy")
        assertEquals(old.canonicalFile,engine.find(folder.root)?.canonicalFile)
    }
}
