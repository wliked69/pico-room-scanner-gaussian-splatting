package com.example.roomsplat.domain.usecase

/** Bind native events to the subscription that produced them, not their delivery time. */
class ScanGeometryAdmissionUseCase<K> {
    enum class Event { ADDED, UPDATED, LOADED, REMOVED }
    private var generation = 0L
    private var active = false
    private val admitted = mutableSetOf<K>()
    private val previousScan = mutableSetOf<K>()

    fun begin(): Long { generation++; active=true; return generation }
    fun pause() { generation++; active=false }
    fun reset() { previousScan.addAll(admitted); admitted.clear(); pause() }
    fun current(token:Long):Boolean = active && token==generation
    fun acceptsSnapshot(id:K,token:Long):Boolean = current(token) && id in admitted
    fun event(id:K,token:Long,event:Event):Boolean {
        if(!current(token))return false
        return when(event) {
            Event.LOADED -> id in admitted
            Event.ADDED -> if(id in previousScan)false else { admitted.add(id);true }
            Event.UPDATED -> { previousScan.remove(id);admitted.add(id);true }
            Event.REMOVED -> { previousScan.remove(id);admitted.remove(id);true }
        }
    }
}
