package com.example.roomsplat.platform

import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import android.content.pm.PackageManager
import com.example.roomsplat.data.repository.LiveDepthRepository

class LaunchActivity : SpatialLaunchActivity() {
    private var requestedSpatialData=false
    override fun onResume() {
        super.onResume()
        if(!requestedSpatialData && checkSelfPermission(LiveDepthRepository.SPATIAL_DATA)!=PackageManager.PERMISSION_GRANTED) {
            requestedSpatialData=true
            requestPermissions(arrayOf(LiveDepthRepository.SPATIAL_DATA),4209)
        }
    }
}
