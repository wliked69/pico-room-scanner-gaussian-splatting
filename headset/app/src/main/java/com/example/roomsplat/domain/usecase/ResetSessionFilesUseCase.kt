package com.example.roomsplat.domain.usecase

import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Detach only a direct capture session, then purge without following symbolic links. */
class ResetSessionFilesUseCase {
    fun detach(storage: File, session: File): File {
        val sessions=File(storage,"sessions")
        require(!Files.isSymbolicLink(sessions.toPath()) && sessions.canonicalFile.parentFile==storage.canonicalFile)
        val parent = sessions.canonicalFile
        require(!Files.isSymbolicLink(session.toPath()) && session.canonicalFile.parentFile == parent &&
            session.name.startsWith("session-")) { "拒绝重置非采集目录。" }
        check(session.isDirectory) { "当前采集目录不存在。" }
        val discarded = File(storage,"discarded").apply { check(mkdirs() || isDirectory) }
        require(!Files.isSymbolicLink(discarded.toPath()) && discarded.canonicalFile.parentFile == storage.canonicalFile)
        val detached = File(discarded,"reset-"+UUID.randomUUID().toString())
        check(session.renameTo(detached)) { "无法清空当前会话，照片保留，请重试。" }
        return detached
    }
    fun purge(storage: File, detached: File) {
        val discarded=File(storage,"discarded")
        require(!Files.isSymbolicLink(discarded.toPath()) && discarded.canonicalFile.parentFile==storage.canonicalFile)
        require(!Files.isSymbolicLink(detached.toPath()) &&
            detached.canonicalFile.parentFile == File(storage,"discarded").canonicalFile &&
            detached.name.startsWith("reset-")) { "拒绝清理非重置目录。" }
        Files.walk(detached.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
