package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Local +Z is the readable face. Aim that normal at the viewer, keeping the panel upright. */
class FacePanelAtHeadUseCase {
    data class Rotation(val x: Float,val y: Float,val z: Float,val w: Float)
    fun rotation(panel: Point3,head: Point3): Rotation? {
        val d=head-panel
        if(!d.x.isFinite() || !d.y.isFinite() || !d.z.isFinite() || d.dot(d)<.000001f)return null
        val yaw=atan2(d.x.toDouble(),d.z.toDouble())
        val pitch=-atan2(d.y.toDouble(),sqrt((d.x*d.x+d.z*d.z).toDouble()))
        val sy=sin(yaw*.5);val cy=cos(yaw*.5);val sp=sin(pitch*.5);val cp=cos(pitch*.5)
        return Rotation((cy*sp).toFloat(),(sy*cp).toFloat(),(-sy*sp).toFloat(),(cy*cp).toFloat())
    }
}
