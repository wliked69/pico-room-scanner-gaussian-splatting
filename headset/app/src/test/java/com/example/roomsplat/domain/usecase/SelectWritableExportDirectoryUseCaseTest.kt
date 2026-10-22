package com.example.roomsplat.domain.usecase

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SelectWritableExportDirectoryUseCaseTest {
    @get:Rule val temporary=TemporaryFolder()
    private val selector=SelectWritableExportDirectoryUseCase()
    @Test fun existingWritableExportsAndArchivesAreRetained() {
        val directory=File(temporary.root,"exports").apply { mkdirs() }
        val archive=File(directory,"keep.zip").apply { writeText("existing archive") }
        assertEquals(directory.canonicalFile,selector.select(temporary.root))
        assertEquals("existing archive",archive.readText())
        assertEquals(listOf("keep.zip"),directory.list()!!.toList())
    }
    @Test fun deniedLegacyOwnershipUsesNewAppDirectoryWithoutChangingArchive() {
        val legacy=File(temporary.root,"exports").apply { mkdirs() }
        val archive=File(legacy,"keep.zip").apply { writeText("keep") }
        val selected=selector.select(temporary.root) { dir ->
            if(dir.name=="exports")throw IOException("EACCES")
            File(dir,"write-check").apply { writeText("writable");delete() }
        }
        assertEquals("exports-app",selected.name)
        assertEquals("keep",archive.readText())
    }
    @Test fun staleFallbackOwnershipGetsFreshDirectory() {
        val selected=selector.select(temporary.root) { dir ->
            if(dir.name in setOf("exports","exports-app"))throw IOException("EACCES")
        }
        assertTrue(selected.name.matches(Regex("exports-app-[0-9a-f]{8}")))
        assertEquals(temporary.root.canonicalFile,selected.parentFile)
    }
    @Test fun pathCollisionDoesNotDeleteExistingFile() {
        val collision=File(temporary.root,"exports").apply { writeText("keep file") }
        assertEquals("exports-app",selector.select(temporary.root).name)
        assertEquals("keep file",collision.readText())
    }
    @Test fun unavailableStorageReportsFailureAndPreservesPhotos() {
        val photo=File(temporary.root,"photo.jpg").apply { writeText("original") }
        try { selector.select(temporary.root) { throw IOException("ENOSPC or EACCES") };fail("Expected storage failure") }
        catch(_:IOException) { assertEquals("original",photo.readText()) }
    }
}
