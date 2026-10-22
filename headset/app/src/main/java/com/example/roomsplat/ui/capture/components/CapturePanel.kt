package com.example.roomsplat.ui.capture.components

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.roomsplat.ui.theme.RoomSplatColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.roomsplat.domain.model.CapturePhase
import com.example.roomsplat.domain.model.CaptureSnapshot
import com.example.roomsplat.domain.model.CoverageSummary
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.Switch

@Composable
fun CapturePanel(capture: CaptureSnapshot, error: String, modifier: Modifier = Modifier,
    coverage: CoverageSummary? = null, coverageVisible: Boolean = true, onCoverageToggle: (Boolean) -> Unit = {}) {
    val title = when (capture.phase) {
        CapturePhase.CHECKING -> "检查采集能力"
        CapturePhase.READY -> "✓ 可以开始采集"
        CapturePhase.RECORDING -> "● 正在采集"
        CapturePhase.PAUSED -> "Ⅱ 已暂停"
        CapturePhase.CAPTURED -> "采集已结束"
        CapturePhase.PACKING -> "正在打包"
        CapturePhase.EXPORTED -> "✓ 数据包已保存"
        CapturePhase.UNAVAILABLE -> "! 采集未就绪"
        CapturePhase.ERROR -> "! 处理未完成"
    }
    Column(modifier = modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ROOM SPLAT · 房间采集", color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.titleMedium)
        Text(title, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.headlineLarge)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column {
                    val seconds = capture.activeMillis / 1000
                    Text("%02d:%02d".format(seconds / 60, seconds % 60), color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.displaySmall)
                    Text("有效采集时长", color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodyLarge)
                }
                Column {
                    Text("${capture.frames}", color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.displaySmall)
                    Text("已保存照片", color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodyLarge)
                }
            }
            // Domain visualization: segments describe saved frames over time, never room coverage.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                capture.segments.takeLast(12).forEach { count ->
                    Box(Modifier.weight(count.coerceAtLeast(1).toFloat()).height(12.dp)
                        .clip(RoundedCornerShape(4.dp)).background(RoomSplatColors.Passable))
                }
            }
            if (capture.phase in setOf(CapturePhase.CHECKING, CapturePhase.PACKING)) CircularProgressIndicator()
            HorizontalDivider()
            if (coverage != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("覆盖参考", color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.titleMedium)
                    Switch(checked = coverageVisible, onCheckedChange = onCoverageToggle)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("蓝 待拍", color = RoomSplatColors.Interaction, style = PicoTheme.typography.bodyMedium)
                    Text("黄 需补拍", color = RoomSplatColors.Alert, style = PicoTheme.typography.bodyMedium)
                    Text("绿 双视角", color = RoomSplatColors.Passable, style = PicoTheme.typography.bodyMedium)
                }
                Text("${coverage.recordedCells}/${coverage.observedCells} 已发现区域已拍过 · 多站位 ${coverage.multiPositionCells}",
                    color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodyMedium)
                Text("绿色表示两个站位已记录；重建质量仍需 Windows 检查。", color = PicoTheme.colorScheme.labelTertiary, style = PicoTheme.typography.bodySmall)
            }
            val hint = if (capture.phase == CapturePhase.RECORDING && coverage != null) coverage.message else capture.message
            Text(error.ifEmpty { hint.ifEmpty { "缓慢平移并保持相邻画面重叠。站稳后操作。" } },
                color = if (error.isNotEmpty() || capture.phase in setOf(CapturePhase.ERROR, CapturePhase.UNAVAILABLE)) RoomSplatColors.Error else PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyLarge)
            if (capture.archivePath.isNotEmpty()) Text(capture.archivePath, color = PicoTheme.colorScheme.labelTertiary, style = PicoTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun CaptureAction(text: String, enabled: Boolean, onClick: () -> Unit, primary: Boolean = false) {
    Button(onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 120.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) RoomSplatColors.Interaction else PicoTheme.colorScheme.fillSecondary,
            contentColor = if (primary) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary,
        )) {
        // design-style: inherited-content-color Button
        Text(text)
    }
}
