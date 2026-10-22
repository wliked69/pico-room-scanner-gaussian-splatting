package com.example.roomsplat.data.repository

import com.example.roomsplat.domain.model.HeadPoseEvidence
import com.example.roomsplat.domain.usecase.LiveDepthAdmissionUseCase

/** App-received HMD samples; these are explicitly not calibrated RGB poses. */
class HeadPoseCache {
    private val samples = ArrayDeque<HeadPoseEvidence>()
    @Synchronized fun add(sample: HeadPoseEvidence) {
        samples.addLast(sample)
        while (samples.size > 120) samples.removeFirst()
    }
    @Synchronized fun nearest(receivedNanos: Long): HeadPoseEvidence? =
        samples.minByOrNull { kotlin.math.abs(it.receivedNanos - receivedNanos) }
            ?.takeIf { kotlin.math.abs(it.receivedNanos - receivedNanos) <= 60_000_000L }
    @Synchronized fun clear() { samples.clear() }

    /** Require history spanning acquisition plus sensor lag; do not guess a moving-frame pose. */
    @Synchronized fun stableSince(since:Long,now:Long):HeadPoseEvidence? {
        val latest=samples.lastOrNull()?:return null
        if(now-latest.receivedNanos !in 0..60_000_000L)return null
        val start=samples.lastOrNull { it.receivedNanos<=since }?:return null
        if(since-start.receivedNanos>60_000_000L)return null
        val window=samples.filter { it.receivedNanos>=start.receivedNanos }
        if(window.zipWithNext().any { (a,b)->b.receivedNanos-a.receivedNanos>60_000_000L })return null
        return latest.takeIf { window.all { LiveDepthAdmissionUseCase.posesAgree(it,latest) } }
    }
}
