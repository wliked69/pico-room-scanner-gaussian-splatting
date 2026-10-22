package com.example.roomsplat.domain.usecase

import java.io.File
import java.nio.file.Files

/** An explicit empty marker means reset, rather than restoring an older unfinished scan. */
class FindRecoverableSessionUseCase {
    fun find(storage: File, marker: File = File(storage,"active-session.txt")): File? {
        val sessions=File(storage,"sessions")
        val candidates = if (marker.exists()) {
            val name=ScanSessionStateUseCase.readOrEmpty(marker)
            if (!name.matches(Regex("session-[A-Za-z0-9._-]+"))) return null
            listOf(File(sessions,name))
        } else sessions.listFiles().orEmpty().toList()
        return candidates.filter {
            !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile==sessions.canonicalFile &&
            it.isDirectory && File(it,"frames.jsonl").isFile && !File(it,"exported.txt").exists()
        }.maxByOrNull { it.lastModified() }
    }
}
