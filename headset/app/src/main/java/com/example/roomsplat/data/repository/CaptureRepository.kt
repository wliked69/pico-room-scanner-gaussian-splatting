package com.example.roomsplat.data.repository

import com.example.roomsplat.domain.model.CaptureSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import com.example.roomsplat.domain.model.SavedFrame

interface CaptureRepository {
    val snapshot: StateFlow<CaptureSnapshot>
    val savedFrames: SharedFlow<SavedFrame>
    suspend fun check(permissionGranted: Boolean)
    suspend fun start()
    fun requestPause()
    suspend fun pause()
    suspend fun resume()
    suspend fun finish()
    suspend fun pack()
    suspend fun newSession()
    suspend fun resetCurrentSession()
    fun close()
}
