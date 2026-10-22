
package com.example.roomsplat.domain.usecase
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CopyYuv420UseCaseTest {
    private fun plane(size:Int,row:Int,stride:Int,start:Int)=CopyYuv420UseCase.Plane(
        ByteBuffer.allocateDirect(size).apply { for(i in 0 until size)put(i,(start+i).toByte());position(1) },row,stride)
    private fun reference(w:Int,h:Int,l:Int,t:Int,y:CopyYuv420UseCase.Plane,u:CopyYuv420UseCase.Plane,v:CopyYuv420UseCase.Plane):ByteArray {
        val out=ByteArray(w*h*3/2)
        for(r in 0 until h)for(c in 0 until w)out[r*w+c]=y.buffer.get((t+r)*y.rowStride+(l+c)*y.pixelStride)
        var at=w*h
        for(r in 0 until h/2)for(c in 0 until w/2){out[at++]=v.buffer.get((t/2+r)*v.rowStride+(l/2+c)*v.pixelStride);out[at++]=u.buffer.get((t/2+r)*u.rowStride+(l/2+c)*u.pixelStride)}
        return out
    }
    @Test fun planarRowsWithPaddingMatchOriginalPixelsAndDoNotMoveSourceCursors() {
        val y=plane(40,10,1,1);val u=plane(12,6,1,70);val v=plane(12,6,1,100)
        val out=ByteArray(48);CopyYuv420UseCase().copy(8,4,0,0,y,u,v,out)
        assertArrayEquals(reference(8,4,0,0,y,u,v),out)
        assertEquals(1,y.buffer.position());assertEquals(1,u.buffer.position());assertEquals(1,v.buffer.position())
    }
    @Test fun interleavedChromaAndTruncatedLastPaddingRowAreSafe() {
        val y=plane(32,8,1,1);val u=plane(15,8,2,40);val v=plane(15,8,2,80)
        val out=ByteArray(48);CopyYuv420UseCase().copy(8,4,0,0,y,u,v,out)
        assertArrayEquals(reference(8,4,0,0,y,u,v),out)
    }
    @Test fun croppedAndUnusualPixelStrideRemainIdentical() {
        val y=plane(192,24,2,1);val u=plane(64,8,2,40);val v=plane(64,8,2,80)
        val out=ByteArray(24);CopyYuv420UseCase().copy(4,4,3,1,y,u,v,out)
        assertArrayEquals(reference(4,4,3,1,y,u,v),out)
    }
    @Test fun reusedOutputHasNoPixelsFromPreviousFrame() {
        val use=CopyYuv420UseCase();val out=ByteArray(24)
        val y=plane(16,4,1,1);val u=plane(4,2,1,20);val v=plane(4,2,1,40)
        use.copy(4,4,0,0,y,u,v,out)
        for(i in 0 until 16)y.buffer.put(i,0)
        use.copy(4,4,0,0,y,u,v,out)
        assertArrayEquals(reference(4,4,0,0,y,u,v),out)
    }
}
