package com.example.roomsplat.domain.usecase

/** Main-thread scene lease. Publish the successor before retiring the predecessor, so a
 * late/reentrant close from the old composition cannot release the new scene's lease.
 */
class ActiveSceneOwnerUseCase<T:Any> {
    private var active:T?=null
    fun claim(owner:T,retire:(T)->Unit) {
        if(active===owner)return
        val previous=active
        active=owner
        previous?.let(retire)
    }
    fun release(owner:T) { if(active===owner)active=null }
}
