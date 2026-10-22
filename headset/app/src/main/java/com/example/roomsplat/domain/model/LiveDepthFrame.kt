package com.example.roomsplat.domain.model

/** Immutable app-owned copy of camera-forward Z depth in metres, top row first.
 * Pose is accepted only across a stable acquisition window; SDK exposes no depth timestamp.
 */
class LiveDepthFrame(val pose:HeadPoseEvidence,val receivedNanos:Long,
    val width:Int,val height:Int,metres:FloatArray,
    val acquisitionStartedNanos:Long=receivedNanos) {
    private val metres=metres.copyOf()
    init { require(width>0 && height>0 && metres.size==width*height) }
    fun at(x:Int,y:Int):Float=metres[y*width+x]

    /** Only queried pixels are prepared, shared by every query for this immutable frame. */
    val bands:DepthBands by lazy { DepthBands.build(this) }

    class DepthBands private constructor(private val frame:LiveDepthFrame) {
        private val width=frame.width
        private val low=FloatArray(width*frame.height)
        private val high=FloatArray(low.size)
        private val supported=BooleanArray(low.size)
        private val computed=BooleanArray(low.size)
        private val samples=FloatArray(9)
        @Volatile var preparedPixels=0
            private set

        // Photo and reveal queries share a frame. Publish each immutable result under this lock,
        // and protect the reused scratch array rather than allocating one per projected point.
        @Synchronized fun supported(x:Int,y:Int):Boolean {
            val index=y*width+x
            if(!computed[index]) {
                prepare(x,y,index)
                computed[index]=true;preparedPixels++
            }
            return supported[index]
        }
        // Consumers call supported first; a prepared pixel never changes afterwards.
        fun near(x:Int,y:Int)=low[y*width+x]
        fun far(x:Int,y:Int)=high[y*width+x]
        private fun prepare(x:Int,y:Int,index:Int) {
            if(x !in 1 until width-1 || y !in 1 until frame.height-1)return
            var count=0
            for(row in y-1..y+1)for(col in x-1..x+1) {
                val v=frame.at(col,row)
                if(v.isFinite() && v in .25f..8f)samples[count++]=v
            }
            if(count<6)return
            java.util.Arrays.sort(samples,0,count)
            val median=samples[count/2];val centre=frame.at(x,y)
            // A balanced doorway edge or minority distant centre is uncertain, not a wall.
            if(!centre.isFinite() || centre !in .25f..8f || kotlin.math.abs(centre-median)>.10f)return
            var support=0;var near=Float.POSITIVE_INFINITY;var far=0f
            for(i in 0 until count)if(kotlin.math.abs(samples[i]-median)<=.10f) {
                support++;near=minOf(near,samples[i]);far=maxOf(far,samples[i])
            }
            if(support>=5) { supported[index]=true;low[index]=near;high[index]=far }
        }
        companion object {
            fun build(frame:LiveDepthFrame)=DepthBands(frame)
        }
    }
}
