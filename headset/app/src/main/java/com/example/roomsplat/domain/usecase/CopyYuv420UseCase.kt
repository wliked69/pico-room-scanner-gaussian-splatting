
package com.example.roomsplat.domain.usecase

import java.nio.ByteBuffer

/** Copy real YUV planes in rows; preserve crop, padding, pixel stride and VU ordering. Handler-thread owned. */
class CopyYuv420UseCase {
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)
    private var uRow=ByteArray(0)
    private var vRow=ByteArray(0)
    private var yRow=ByteArray(0)
    fun copy(width:Int,height:Int,left:Int,top:Int,y:Plane,u:Plane,v:Plane,out:ByteArray) {
        require(width>0 && height>0 && width%2==0 && height%2==0 && left>=0 && top>=0)
        require(out.size>=width*height*3/2)
        val yBuffer=y.buffer.duplicate()
        if (y.pixelStride==1) {
            for (r in 0 until height) {
                yBuffer.position((top+r)*y.rowStride+left)
                yBuffer.get(out,r*width,width)
            }
        } else {
            val n=(width-1)*y.pixelStride+1
            if(yRow.size<n)yRow=ByteArray(n)
            for(r in 0 until height) {
                yBuffer.position((top+r)*y.rowStride+left*y.pixelStride)
                yBuffer.get(yRow,0,n)
                for(c in 0 until width)out[r*width+c]=yRow[c*y.pixelStride]
            }
        }
        val columns=width/2
        val uBytes=(columns-1)*u.pixelStride+1
        val vBytes=(columns-1)*v.pixelStride+1
        if(uRow.size<uBytes)uRow=ByteArray(uBytes)
        if(vRow.size<vBytes)vRow=ByteArray(vBytes)
        val ub=u.buffer.duplicate();val vb=v.buffer.duplicate()
        var offset=width*height
        for(r in 0 until height/2) {
            ub.position((top/2+r)*u.rowStride+(left/2)*u.pixelStride)
            vb.position((top/2+r)*v.rowStride+(left/2)*v.pixelStride)
            ub.get(uRow,0,uBytes);vb.get(vRow,0,vBytes)
            for(c in 0 until columns) { out[offset++]=vRow[c*v.pixelStride];out[offset++]=uRow[c*u.pixelStride] }
        }
    }
}
