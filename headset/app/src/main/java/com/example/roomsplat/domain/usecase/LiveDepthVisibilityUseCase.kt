package com.example.roomsplat.domain.usecase

/** Drawing only: retained scan/photo evidence is never deleted by occlusion. Main-thread owner. */
class LiveDepthVisibilityUseCase {
    private data class Candidate(val first:Long,var last:Long,var samples:Int)
    private val candidates=mutableMapOf<String,Candidate>()
    private val hidden=mutableSetOf<String>()
    private var lastFrame=Long.MIN_VALUE
    fun hiddenKeys():Set<String> = hidden.toSet()
    fun clear() { hidden.clear();pause();lastFrame=Long.MIN_VALUE }
    fun pause() { candidates.clear() }
    fun retain(keys:Set<String>) { hidden.retainAll(keys);candidates.keys.retainAll(keys) }

    fun update(evidence:Map<String,LiveDepthAdmissionUseCase.Visibility>,frameNanos:Long):Set<String> {
        if(frameNanos<=lastFrame)return emptySet()
        lastFrame=frameNanos
        val changed=mutableSetOf<String>()
        candidates.keys.retainAll(evidence.keys)
        evidence.forEach { (key,state)->
            when(state) {
                LiveDepthAdmissionUseCase.Visibility.OCCLUDED -> {
                    val prior=candidates[key]?.takeIf { frameNanos-it.last<=1_500_000_000L }
                    val candidate=prior?:Candidate(frameNanos,frameNanos,0).also { candidates[key]=it }
                    candidate.last=frameNanos;candidate.samples++
                    if(candidate.samples>=2 && frameNanos-candidate.first>=250_000_000L && hidden.add(key))changed.add(key)
                }
                LiveDepthAdmissionUseCase.Visibility.MATCHED,LiveDepthAdmissionUseCase.Visibility.CLEAR -> {
                    candidates.remove(key)
                    if(hidden.remove(key))changed.add(key)
                }
                LiveDepthAdmissionUseCase.Visibility.UNKNOWN -> candidates.remove(key)
            }
        }
        return changed
    }
}
