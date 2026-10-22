package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.HeadPoseEvidence
import com.example.roomsplat.domain.model.LiveDepthFrame
import com.example.roomsplat.domain.model.Point3
import kotlin.math.abs
import kotlin.math.floor

/** Admission of UNSEEN measured cells, never a draw-time mask of retained scan progress. */
class LiveDepthAdmissionUseCase {
    enum class Visibility { MATCHED, OCCLUDED, CLEAR, UNKNOWN }
    fun usable(frame:LiveDepthFrame?,pose:HeadPoseEvidence,now:Long,minAcquisitionNanos:Long=Long.MIN_VALUE):Boolean =
        frame!=null && now-frame.receivedNanos in 0..250_000_000L &&
            frame.acquisitionStartedNanos>=minAcquisitionNanos &&
            now-pose.receivedNanos in 0..100_000_000L && posesAgree(frame.pose,pose)

    fun blocked(points:Map<String,Point3>,frame:LiveDepthFrame?,pose:HeadPoseEvidence,now:Long,
        minAcquisitionNanos:Long=Long.MIN_VALUE):Set<String> {
        if(!usable(frame,pose,now,minAcquisitionNanos))return points.keys
        return buildSet { points.forEach { (key,point)->if(!matches(point,frame!!))add(key) } }
    }

    fun matches(point:Point3,frame:LiveDepthFrame):Boolean {
        return visibility(point,frame)==Visibility.MATCHED
    }

    /** Positive occlusion requires a coherent foreground patch, not just a failed match. */
    fun visibility(point:Point3,frame:LiveDepthFrame):Visibility {
        val delta=point-frame.pose.position
        val z=delta.dot(frame.pose.forward)
        if(!z.isFinite() || z !in .25f..8f)return Visibility.UNKNOWN
        // Public fixed FOV: 109 horizontal / 90 vertical. Alignment probe selects Z, not range.
        val x=delta.dot(frame.pose.right)/(z*1.4019483f)
        val y=delta.dot(frame.pose.up)/z
        if(!x.isFinite() || !y.isFinite() || abs(x)>=1f || abs(y)>=1f)return Visibility.UNKNOWN
        val px=floor((x+1f)*.5f*frame.width).toInt().coerceIn(0,frame.width-1)
        val py=floor((1f-y)*.5f*frame.height).toInt().coerceIn(0,frame.height-1)
        val bands=frame.bands
        if(!bands.supported(px,py))return Visibility.UNKNOWN
        val near=bands.near(px,py);val far=bands.far(px,py)
        if(z>=near-.20f && z<=far+.20f)return Visibility.MATCHED
        if(near>=.45f && far<z-.35f)return Visibility.OCCLUDED
        if(near>z+.20f)return Visibility.CLEAR
        return Visibility.UNKNOWN
    }

    fun drawUsable(frame:LiveDepthFrame?,pose:HeadPoseEvidence,now:Long,minAcquisitionNanos:Long=Long.MIN_VALUE):Boolean =
        usable(frame,pose,now,minAcquisitionNanos) && (frame!!.pose.position-pose.position).length()<=.01f &&
            frame.pose.forward.dot(pose.forward)>=.9999619f && frame.pose.up.dot(pose.up)>=.9999619f

    /** A saved frame can arrive just before or just after the latest depth callback. */
    fun photoMatches(points:Map<String,Point3>,depth:LiveDepthFrame?,photo:HeadPoseEvidence?,received:Long,
        minAcquisitionNanos:Long=Long.MIN_VALUE):Set<String> {
        if(depth==null || photo==null || abs(received-depth.receivedNanos)>150_000_000L ||
            depth.acquisitionStartedNanos<minAcquisitionNanos ||
            !posesAgree(depth.pose,photo))return emptySet()
        return buildSet { points.forEach { (key,p)->if(matches(p,depth))add(key) } }
    }

    companion object {
        fun posesAgree(a:HeadPoseEvidence,b:HeadPoseEvidence):Boolean =
            (a.position-b.position).length()<=.02f &&
                a.forward.dot(b.forward)>=.9993908f && a.up.dot(b.up)>=.9993908f
    }
}
