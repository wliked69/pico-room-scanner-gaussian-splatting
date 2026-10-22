package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import kotlin.math.sqrt

/** Bake native local geometry into a fixed display owner; never move an old mesh while preparing its successor. */
class BakeMeshDisplayFrameUseCase {
    fun build(points: List<Point3>,origin: Point3,rotation: List<Float>): List<Point3> {
        require(rotation.size==4 && rotation.all { it.isFinite() })
        val norm=sqrt(rotation.sumOf { it.toDouble()*it }.toFloat())
        require(norm>.000001f)
        val x=rotation[0]/norm;val y=rotation[1]/norm;val z=rotation[2]/norm;val w=rotation[3]/norm
        val xx=x*x;val yy=y*y;val zz=z*z;val xy=x*y;val xz=x*z;val yz=y*z;val wx=w*x;val wy=w*y;val wz=w*z
        return points.map { p -> Point3(
            origin.x+(1-2*(yy+zz))*p.x+2*(xy-wz)*p.y+2*(xz+wy)*p.z,
            origin.y+2*(xy+wz)*p.x+(1-2*(xx+zz))*p.y+2*(yz-wx)*p.z,
            origin.z+2*(xz-wy)*p.x+2*(yz+wx)*p.y+(1-2*(xx+yy))*p.z) }
    }
}
