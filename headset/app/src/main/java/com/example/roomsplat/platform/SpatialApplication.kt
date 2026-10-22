package com.example.roomsplat.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.example.roomsplat.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
