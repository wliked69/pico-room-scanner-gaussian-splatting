
package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3

/** Do not attach controls to stale, zero, or unreachable tracking positions. */
class SelectPanelAnchorUseCase {
    enum class Source { LEFT_HAND, LEFT_CONTROLLER, HEAD }
    data class Sample(val position: Point3, val receivedNanos: Long)
    fun select(now: Long, head: Point3?, hand: Sample?, controller: Sample?, followHand: Boolean): Source {
        if (!followHand || head == null) return Source.HEAD
        fun usable(sample: Sample?) = sample != null && now >= sample.receivedNanos &&
            now - sample.receivedNanos <= 500_000_000L &&
            sample.position.x.isFinite() && sample.position.y.isFinite() && sample.position.z.isFinite() &&
            sample.position != Point3(0f, 0f, 0f) &&
            (sample.position - head).length() in .15f..1.5f
        return when {
            usable(hand) -> Source.LEFT_HAND
            usable(controller) -> Source.LEFT_CONTROLLER
            else -> Source.HEAD
        }
    }
}
