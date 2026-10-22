package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import java.util.PriorityQueue

/** Linear subdivision of measured triangles; adds display samples, no new measured detail. */
class SubdivideSurfaceMeshUseCase {
    data class Mesh(val positions: List<Point3>, val indices: List<Int>,val sourceTriangles:List<Int> = emptyList())
    private data class Triangle(val a: Int, val b: Int, val c: Int, val depth: Int, val longestSquared: Float,val source:Int)
    fun build(positions: List<Point3>, indices: List<Int>, edgeM: Float = .12f, maxTriangles: Int = 6000): Mesh {
        require(edgeM > 0 && indices.size % 3 == 0 && indices.all { it in positions.indices })
        require(positions.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        if (indices.isEmpty()) return Mesh(positions, indices)
        val vertices = positions.toMutableList()
        fun triangle(a: Int, b: Int, c: Int, depth: Int,source:Int): Triangle {
            val ab = vertices[a]-vertices[b]; val bc = vertices[b]-vertices[c]; val ca = vertices[c]-vertices[a]
            return Triangle(a,b,c,depth,maxOf(ab.dot(ab),bc.dot(bc),ca.dot(ca)),source)
        }
        val queue = PriorityQueue<Triangle>(compareByDescending<Triangle> { it.longestSquared }.thenBy { it.a }.thenBy { it.b })
        indices.chunked(3).forEachIndexed { source,t -> queue.add(triangle(t[0],t[1],t[2],0,source)) }
        val finished = mutableListOf<Triangle>()
        val midpoints = mutableMapOf<Long, Int>()
        fun midpoint(a: Int, b: Int): Int {
            val key = (minOf(a,b).toLong() shl 32) or maxOf(a,b).toLong()
            return midpoints.getOrPut(key) {
                val x = vertices[a]; val y = vertices[b]
                vertices.add(Point3((x.x+y.x)*.5f,(x.y+y.y)*.5f,(x.z+y.z)*.5f))
                vertices.lastIndex
            }
        }
        val budget = maxOf(indices.size/3, maxTriangles)
        while (queue.isNotEmpty()) {
            val largest = queue.peek() ?: break
            if (largest.longestSquared <= edgeM*edgeM || queue.size + finished.size + 3 > budget) break
            val t = queue.remove()
            if (t.depth >= 5) { finished.add(t); continue }
            val ab = midpoint(t.a,t.b); val bc = midpoint(t.b,t.c); val ca = midpoint(t.c,t.a); val d=t.depth+1
            queue.add(triangle(t.a,ab,ca,d,t.source)); queue.add(triangle(ab,t.b,bc,d,t.source))
            queue.add(triangle(ca,bc,t.c,d,t.source)); queue.add(triangle(ab,bc,ca,d,t.source))
        }
        val triangles=finished+queue
        val refined = triangles.flatMap { listOf(it.a,it.b,it.c) }
        return Mesh(vertices,refined,triangles.map { it.source })
    }
}
