package com.example.roomsplat

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.example.roomsplat.ui.coverage.CoverageStage
import com.example.roomsplat.ui.theme.RoomSplatTheme

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            RoomSplatTheme {
                CoverageStage()
            }
        }
    }
