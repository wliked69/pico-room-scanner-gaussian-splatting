package com.example.roomsplat.domain.usecase

import java.io.File
import java.io.IOException
import java.util.UUID

/** App-private state is authoritative; USB-owned external markers are legacy hints only. */
class ScanSessionStateUseCase(private val marker: File) {
    fun currentMarker(legacy: File): File = if (marker.exists()) marker else legacy

    fun write(name: String) {
        require(name.isEmpty() || name.matches(Regex("session-[A-Za-z0-9._-]+")))
        check(marker.parentFile?.isDirectory == true || marker.parentFile?.mkdirs() == true) { "无法建立扫描状态目录。" }
        val partial = File(marker.parentFile, "${marker.name}.partial-${UUID.randomUUID()}")
        try {
            partial.writeText(name)
            check(partial.renameTo(marker)) { "无法保存扫描会话状态。" }
        } finally { partial.delete() }
    }

    companion object {
        fun readOrEmpty(file: File, read: (File) -> String = { it.readText() }): String =
            try { read(file).trim() } catch (_: IOException) { "" } catch (_: SecurityException) { "" }
    }
}
