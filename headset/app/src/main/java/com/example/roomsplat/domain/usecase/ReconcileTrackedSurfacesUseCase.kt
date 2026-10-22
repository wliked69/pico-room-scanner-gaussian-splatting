package com.example.roomsplat.domain.usecase

/** A resumed tracking snapshot may omit anchors removed while the app was not subscribed. */
class ReconcileTrackedSurfacesUseCase<K> {
    fun acceptLoaded(id:K,lastEvents:Map<K,Long>,loadStartedNanos:Long):Boolean =
        (lastEvents[id]?:Long.MIN_VALUE)<loadStartedNanos
    fun removed(cached:Set<K>,loaded:Set<K>,lastEvents:Map<K,Long>,loadStartedNanos:Long):Set<K> {
        if(loaded.isEmpty())return emptySet() // Paused/not-ready providers can return an empty snapshot.
        return cached.filterTo(linkedSetOf()) { id ->
            id !in loaded && (lastEvents[id]?:Long.MIN_VALUE)<loadStartedNanos
        }
    }
}
