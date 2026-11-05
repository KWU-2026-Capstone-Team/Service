package com.verivid.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Original 24px line icons: one stroke weight, rounded ends, no bitmap assets. */
internal object VeriIcons {
    private fun line(name: String, data: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.65f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        )
    }.build()

    val Home = line("Home", "M3.5 10.5L12 3.5L20.5 10.5 M5.5 9V20H10V14H14V20H18.5V9")
    val Game = line("Game", "M7 7H17C19 7 20 9 20.5 12L21.5 17C22 20 19 21 17.5 19L15 16H9L6.5 19C5 21 2 20 2.5 17L3.5 12C4 9 5 7 7 7Z M6 11H10 M8 9V13 M16 10.5H16.1 M18 13H18.1")
    val History = line("History", "M5 3.5H16L19 6.5V20.5H5Z M15.5 3.5V7H19 M8.5 11H15.5 M8.5 15H15.5")
    val Calendar = line("Calendar", "M7 3V7 M17 3V7 M4 5H20A1 1 0 0 1 21 6V20A1 1 0 0 1 20 21H4A1 1 0 0 1 3 20V6A1 1 0 0 1 4 5Z M3 10H21 M8 14H10 M14 14H16 M8 17H10")
    val HelpOutline = line("Help", "M12 21A9 9 0 1 0 12 3A9 9 0 1 0 12 21 M9.5 9A2.5 2.5 0 1 1 13.5 11L12 12.5V13.5 M12 16.8V17")
    val ScreenShare = line("ScreenCapture", "M8 3.5H4A.5.5 0 0 0 3.5 4V8 M16 3.5H20A.5.5 0 0 1 20.5 4V8 M3.5 16V20A.5.5 0 0 0 4 20.5H8 M16 20.5H20A.5.5 0 0 0 20.5 20V16 M9 8L16 12L9 16Z")
    val Videocam = line("Video", "M5 4.5H19A1.5 1.5 0 0 1 20.5 6V18A1.5 1.5 0 0 1 19 19.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5Z M10 8.5L15.5 12L10 15.5Z")
    val Image = line("Photo", "M5 4.5H19A1.5 1.5 0 0 1 20.5 6V18A1.5 1.5 0 0 1 19 19.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5Z M3.5 16L9 10.5L14 15.5L17 12.5L20.5 16 M16 8H16.1")
    val ChevronRight = line("ChevronRight", "M9 6L15 12L9 18")
    val ExpandMore = line("ChevronDown", "M6 9L12 15L18 9")
    val ArrowBack = line("Back", "M10 5L3 12L10 19 M3 12H21")
    val Security = line("Shield", "M12 3L20 6V11C20 16 16.5 19.5 12 21C7.5 19.5 4 16 4 11V6Z M8.5 11.5L11 14L15.5 9.5")
    val Face = line("Face", "M8 3H4A1 1 0 0 0 3 4V8 M16 3H20A1 1 0 0 1 21 4V8 M3 16V20A1 1 0 0 0 4 21H8 M16 21H20A1 1 0 0 0 21 20V16 M8 9V10 M16 9V10 M9 15C10.5 16.5 13.5 16.5 15 15")
    val BarChart = line("Analysis", "M4 20H20 M7 15V8 M12 15V4 M17 15V11")
    val CheckCircle = line("Checked", "M12 21A9 9 0 1 0 12 3A9 9 0 1 0 12 21 M8 12L11 15L16 9")
    val Check = line("Check", "M5 12L9.5 16.5L19 7")
    val Info = line("Info", "M12 21A9 9 0 1 0 12 3A9 9 0 1 0 12 21 M12 11V16 M12 7.8V8")
    val PriorityHigh = line("Attention", "M12 5V14 M12 18.5V19")
    val Remove = line("Pause", "M5 12H19")
    val RadioButtonUnchecked = line("Pending", "M12 20A8 8 0 1 0 12 4A8 8 0 1 0 12 20")
    val PlayArrow = line("Play", "M8 5L19 12L8 19Z")
    val Replay = line("Retry", "M4 9A8 8 0 1 1 4.5 16 M4 4V9H9")
    val Call = line("Phone", "M5 3.5H8L10 8L7.5 10C9 13 11 15 14 16.5L16 14L20.5 16V19A1.5 1.5 0 0 1 19 20.5C11 20.5 3.5 13 3.5 5A1.5 1.5 0 0 1 5 3.5Z")
    val Link = line("Link", "M10 14L14 10 M8.5 15.5L6.5 17.5A3.5 3.5 0 0 1 1.5 12.5L5.5 8.5A3.5 3.5 0 0 1 10.5 8.5 M13.5 15.5A3.5 3.5 0 0 0 18.5 15.5L22.5 11.5A3.5 3.5 0 0 0 17.5 6.5L15.5 8.5")
    val Lock = line("Privacy", "M7 10V7A5 5 0 0 1 17 7V10 M6 10H18A1 1 0 0 1 19 11V20A1 1 0 0 1 18 21H6A1 1 0 0 1 5 20V11A1 1 0 0 1 6 10Z M12 14V17")
    val VolumeUp = line("Audio", "M4 9H8L13 5V19L8 15H4Z M17 8C19 10 19 14 17 16 M20 5C24 9 24 15 20 19")
}
