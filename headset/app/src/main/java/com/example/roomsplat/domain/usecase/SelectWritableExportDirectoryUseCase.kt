package com.example.roomsplat.domain.usecase

import java.io.File
import java.io.IOException
import java.util.UUID

/** Scoped-storage ownership can make an ADB-restored exports directory read-only to the app. */
class SelectWritableExportDirectoryUseCase {
    fun select(storage:File,probe:(File)->Unit = ::verifyWritable):File {
        val root=storage.canonicalFile
        require(root.isDirectory) { "扫描存储目录不存在。" }
        var lastError:Exception?=null
        val names=listOf("exports","exports-app","exports-app-${UUID.randomUUID().toString().take(8)}")
        for(name in names) {
            val directory=File(root,name).canonicalFile
            require(directory.parentFile==root) { "导出目录超出扫描存储范围。" }
            try {
                if(!directory.isDirectory && !directory.mkdirs())throw IOException("无法建立导出目录。")
                probe(directory)
                return directory
            } catch(error:IOException) { lastError=error }
            catch(error:SecurityException) { lastError=error }
        }
        throw IOException("没有可写的导出目录；原始照片已保留。",lastError)
    }
    private fun verifyWritable(directory:File) {
        val temporary=File.createTempFile(".roomsplat-write-",".probe",directory)
        if(!temporary.delete())throw IOException("无法清理导出目录写入检查。")
    }
}
