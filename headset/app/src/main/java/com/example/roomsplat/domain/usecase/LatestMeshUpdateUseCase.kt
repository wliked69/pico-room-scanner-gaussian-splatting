package com.example.roomsplat.domain.usecase

/** Main-owned mailbox; revealed surfaces get priority with bounded fairness for older updates. */
class LatestMeshUpdateUseCase<K> {
    data class Ticket<K>(val key: K,val revision: Long,val geometryChanged: Boolean,val generation:Long=0)
    private var generation=0L
    private var nextRevision=0L
    private val revisions=mutableMapOf<K,Long>()
    private val pending=linkedSetOf<K>()
    private val geometry=mutableSetOf<K>()
    private val urgent=linkedSetOf<K>()
    private var urgentStreak=0
    fun request(key:K,geometryChanged:Boolean=false,prioritize:Boolean=false) {
        revisions[key]=++nextRevision
        if(geometryChanged)geometry.add(key)
        pending.add(key)
        if(prioritize)urgent.add(key)
    }
    fun take():Ticket<K>? {
        urgent.retainAll(pending)
        val ordinary=pending.firstOrNull { it !in urgent }
        val key=if(urgent.isNotEmpty() && (urgentStreak<2 || ordinary==null)) {
            urgentStreak++;urgent.first()
        } else {
            urgentStreak=0;ordinary?:pending.firstOrNull()?:return null
        }
        pending.remove(key);urgent.remove(key)
        return Ticket(key,revisions.getValue(key),key in geometry,generation)
    }
    fun isCurrent(ticket:Ticket<K>):Boolean = ticket.generation==generation && revisions[ticket.key]==ticket.revision
    fun complete(ticket:Ticket<K>) {
        if(ticket.generation!=generation)return
        if(isCurrent(ticket))geometry.remove(ticket.key) else if(ticket.key in revisions)pending.add(ticket.key)
    }
    fun retry(ticket:Ticket<K>) { if(ticket.generation==generation && ticket.key in revisions)pending.add(ticket.key) }
    fun remove(key:K) { revisions.remove(key);pending.remove(key);geometry.remove(key);urgent.remove(key) }
    fun clear() { generation++;revisions.clear();pending.clear();geometry.clear();urgent.clear();urgentStreak=0 }
    fun size():Int=pending.size
}
