package com.example.roomsplat.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pico.spatial.ui.design.PicoTheme

object RoomSplatColors {
    val Error = Color(0xFFE5484D) // design-style: fixed-figma-color design-doc.md error
    val Alert = Color(0xFFDFA329) // design-style: fixed-figma-color design-doc.md alert
    val Passable = Color(0xFF3FA773) // design-style: fixed-figma-color design-doc.md passable
    val Interaction = Color(0xFF5674D8) // design-style: fixed-figma-color design-doc.md interaction
}

@Composable
fun RoomSplatTheme(content: @Composable () -> Unit) {
    PicoTheme(content=content)
}
