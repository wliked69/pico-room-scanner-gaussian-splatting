package com.example.roomsplat.domain.usecase

import java.util.concurrent.atomic.AtomicLong

/** An immediate pause invalidates both queued images and a pending camera-open operation. */
class CaptureWriteGate {
    private val generation = AtomicLong(0)
    @Volatile private var enabled = false
    @Synchronized fun begin(): Long {
        val token = generation.incrementAndGet()
        enabled = true
        return token
    }
    @Synchronized fun pause() { enabled = false; generation.incrementAndGet() }
    fun allows(token: Long): Boolean = enabled && generation.get() == token
}
