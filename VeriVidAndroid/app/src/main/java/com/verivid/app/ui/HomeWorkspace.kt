package com.verivid.app.ui

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Isolated to Home: no shared theme, route, data or recording changes.
private object FocusPalette {
    val background = Color(0xFFF8F9FC)
    val surface = Color.White
    val header = Color(0xFF303F91)
    val headerLower = Color(0xFF4355B2)
    val introSecondary = Color(0xFFE4E9FF)
    val tray = Color(0xFFF6F7FA)
    val ink = Color(0xFF1E2533)
    // Kept darker than the reference gray for small Korean text contrast.
    val secondary = Color(0xFF626E82)
    val brand = Color(0xFF4355B2)
    val pressed = Color(0xFFF1F3FE)
    val line = Color(0xFFE6E9F0)
    val buttonLine = Color(0xFFDFE4F3)
}

private object HomeGeometry {
    val button = 100.dp
    val buttonLarge = 112.dp
    val overlap = 32.dp
    val overlapLarge = 36.dp
    val panelRadius = 24.dp
    val quickRadius = 16.dp
}

private fun homeType(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = height.sp,
    fontWeight = weight, letterSpacing = (-0.2).sp,
    localeList = LocaleList("ko-KR"), lineBreak = LineBreak.Heading
)

private fun focusTypography(large: Boolean) = Typography(
    // Brand / intro / primary action / section / body / caption / quick action / nav.
    titleLarge = homeType(if (large) 26 else 23, if (large) 34 else 30, FontWeight.SemiBold).copy(letterSpacing = (-0.6).sp),
    headlineSmall = homeType(if (large) 23 else 19, if (large) 34 else 27, FontWeight.Medium),
    titleMedium = homeType(if (large) 25 else 21, if (large) 35 else 29, FontWeight.Medium),
    titleSmall = homeType(if (large) 20 else 16, if (large) 28 else 24, FontWeight.Medium),
    bodyMedium = homeType(if (large) 18 else 14, if (large) 28 else 22),
    bodySmall = homeType(if (large) 17 else 13, if (large) 26 else 20),
    labelLarge = homeType(if (large) 19 else 15, if (large) 28 else 22, FontWeight.Medium),
    labelMedium = homeType(if (large) 14 else 12, if (large) 20 else 18, FontWeight.Medium).copy(letterSpacing = 0.sp)
)

@Composable
private fun FocusTheme(large: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(primary = FocusPalette.brand, onPrimary = Color.White, surfaceTint = Color.Transparent),
        typography = focusTypography(large), content = content
    )
}

private fun homeVector(name: String, viewport: Float, path: String, width: Float) = ImageVector.Builder(
    name, viewport.dp, viewport.dp, viewport, viewport
).apply {
    addPath(
        pathData = PathParser().parsePathString(path).toNodes(), fill = null,
        stroke = SolidColor(Color.Black), strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
    )
}.build()

// Home-only icon family: 24-unit grid, rounded 1.65-unit strokes.
// Other screens retain their existing assets.
private object FocusIcons {
    val settings = homeVector("FocusSettings", 24f,
        "M4 7H8 M12 7H20 M4 17H12 M16 17H20 " +
            "M12 7A2 2 0 1 0 8 7A2 2 0 1 0 12 7 " +
            "M16 17A2 2 0 1 0 12 17A2 2 0 1 0 16 17", 1.65f)
    val video = homeVector("FocusVideo", 24f,
        "M6 4.5H18A2 2 0 0 1 20 6.5V17.5A2 2 0 0 1 18 19.5H6A2 2 0 0 1 4 17.5V6.5A2 2 0 0 1 6 4.5Z M10 8.5L15 12L10 15.5Z", 1.65f)
    val image = homeVector("FocusImage", 24f,
        "M6 4.5H18A2 2 0 0 1 20 6.5V17.5A2 2 0 0 1 18 19.5H6A2 2 0 0 1 4 17.5V6.5A2 2 0 0 1 6 4.5Z " +
            "M4 16L9 11L14 16L17 13L20 16 M16.5 8.5H16.51", 1.65f)
    val history = homeVector("FocusHistory", 24f,
        "M14 4H6A1.5 1.5 0 0 0 4.5 5.5V18.5A1.5 1.5 0 0 0 6 20H18A1.5 1.5 0 0 0 19.5 18.5V9.5L14 4Z " +
            "M14 4V9.5H19.5 M8.5 13H15.5 M8.5 16.5H13.5", 1.65f)
    val home = homeVector("FocusHome", 24f,
        "M3.5 10.5L12 3.5L20.5 10.5 M5.5 9V19A1 1 0 0 0 6.5 20H9.5V14H14.5V20H17.5A1 1 0 0 0 18.5 19V9", 1.8f)
    val help = homeVector("FocusHelp", 24f,
        "M21 12A9 9 0 1 0 3 12A9 9 0 1 0 21 12 " +
            "M9.5 9A2.5 2.5 0 0 1 14.5 9C14.5 11 12 11 12 13 M12 16H12.01", 1.65f)
    val chevron = homeVector("FocusChevron", 24f, "M9 6L15 12L9 18", 1.65f)
}

// Original open frame with a soft-vertex V. Separate optical weights keep the
// monogram prominent without making the enclosing frame heavy. Bounds are
// symmetric around (20,20); no tick/status badge or borrowed brand mark.
private val VeriFrame = ImageVector.Builder("VeriFrame", 40.dp, 40.dp, 40f, 40f).apply {
    addPath(
        pathData = PathParser().parsePathString(
            "M12 5H7.5A2.5 2.5 0 0 0 5 7.5V12 M28 5H32.5A2.5 2.5 0 0 1 35 7.5V12 " +
                "M5 28V32.5A2.5 2.5 0 0 0 7.5 35H12 M28 35H32.5A2.5 2.5 0 0 0 35 32.5V28"
        ).toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeAlpha = .8f,
        strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
    )
    addPath(
        pathData = PathParser().parsePathString("M11.5 14L18.9 25.5Q20 27.1 21.1 25.5L28.5 14").toNodes(),
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2.65f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
    )
}.build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeHeader(large: Boolean, onLargeChanged: (Boolean) -> Unit) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    // Home-only system chrome, restored when navigating to an existing screen.
    val applyHomeChrome = {
        (view.context as? Activity)?.window?.let { window ->
            window.statusBarColor = FocusPalette.header.toArgb()
            window.navigationBarColor = FocusPalette.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = true
            }
        }
    }
    SideEffect { applyHomeChrome() }
    // Apply after the navigation frame too: the outgoing screen's effect cleanup
    // can otherwise restore light status icons over the newly entered Home.
    LaunchedEffect(view, large, showSettings) {
        withFrameNanos { }
        applyHomeChrome()
    }
    DisposableEffect(view) {
        onDispose {
            (view.context as? Activity)?.window?.let { window ->
                window.statusBarColor = Color.White.toArgb()
                window.navigationBarColor = Color.White.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
            }
        }
    }
    FocusTheme(large) {
        BoxWithConstraints(Modifier.fillMaxWidth().background(FocusPalette.header)) {
            val edge = if (maxWidth < 360.dp) 20.dp else 24.dp
            Row(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.Center)
                    .padding(horizontal = edge).heightIn(min = 60.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text("VeriVid", color = Color.White,
                    style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { showSettings = true }, modifier = Modifier.size(48.dp)) {
                    Icon(FocusIcons.settings, "보기 설정", tint = Color.White, modifier = Modifier.size(21.dp))
                }
            }
        }
        if (showSettings) {
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color.White, contentColor = FocusPalette.ink
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("보기 설정", style = MaterialTheme.typography.titleLarge)
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = large, role = Role.Switch, onValueChange = onLargeChanged)
                            .heightIn(min = 64.dp).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("크게 보기", style = MaterialTheme.typography.titleMedium)
                            Text("글자와 버튼을 크게 보여줘요.", color = FocusPalette.secondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = large, onCheckedChange = null)
                    }
                    HorizontalDivider(color = FocusPalette.line)
                    Text("분석에 동의한 영상만 팀 분석 서버로 전송해요.\nAI 결과는 진위의 확정 판정이 아니에요.", color = FocusPalette.secondary, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showSettings = false }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                        Text("완료", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeWorkspace(onStartCapture: () -> Unit, onSelectVideo: () -> Unit, onSelectImage: () -> Unit, onOpenHistory: () -> Unit) {
    val large = LocalLargeView.current
    val density = LocalDensity.current
    var introHeightPx by remember { mutableIntStateOf(0) }
    FocusTheme(large) {
        BoxWithConstraints(Modifier.fillMaxSize().background(FocusPalette.background)) {
            val edge = if (maxWidth < 360.dp) 20.dp else 24.dp
            val short = maxHeight < 500.dp
            val buttonSize = if (large) HomeGeometry.buttonLarge else HomeGeometry.button
            val overlap = if (large) HomeGeometry.overlapLarge else HomeGeometry.overlap
            val cardTop = buttonSize - overlap
            val brandDepth = introHeightPx + with(density) { (cardTop + HomeGeometry.panelRadius).toPx() }
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                    .drawWithCache {
                        // Two blue tones only; the neutral surface is separate.
                        // Painted on scroll content, so backdrop and panel move together.
                        val brandWash = Brush.verticalGradient(
                            listOf(FocusPalette.header, FocusPalette.headerLower), endY = brandDepth.coerceAtLeast(1f)
                        )
                        onDrawBehind { drawRect(brandWash, size = Size(size.width, brandDepth)) }
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    Modifier.fillMaxWidth().onSizeChanged { introHeightPx = it.height }
                        .padding(horizontal = edge).padding(top = 8.dp, bottom = if (short) 16.dp else 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("영상 속 의심 신호 확인", color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center)
                    Text("확인할 방법을 선택하세요.", color = FocusPalette.introSecondary,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }

                // The launch control straddles the backdrop and the white surface.
                // It is a sibling of Surface: never clipped, and only ONE hit target.
                Box(Modifier.fillMaxWidth().padding(horizontal = edge).semantics { isTraversalGroup = true }) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = cardTop),
                        shape = RoundedCornerShape(HomeGeometry.panelRadius),
                        color = FocusPalette.surface, border = BorderStroke(1.dp, FocusPalette.line.copy(alpha = .65f)),
                        shadowElevation = 0.dp, tonalElevation = 0.dp
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                                .padding(top = overlap + 18.dp, bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("화면 녹화 시작", color = FocusPalette.ink,
                                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Text("다른 앱의 영상을 녹화해 확인해요.", color = FocusPalette.secondary,
                                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(24.dp))
                            FileInputActions(onSelectVideo, onSelectImage)
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(color = FocusPalette.line)
                            HistoryShortcut(onOpenHistory)
                        }
                    }
                    RecordFocusButton(large, onStartCapture, Modifier.align(Alignment.TopCenter))
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = edge + 4.dp)
                        .padding(top = 16.dp, bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    Icon(FocusIcons.help, null, tint = FocusPalette.secondary, modifier = Modifier.size(15.dp))
                    Text("AI 분석 · 중요한 내용은 출처도 확인하세요.", color = FocusPalette.secondary,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RecordFocusButton(large: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, tween(140), label = "Record press scale")
    val color by animateColorAsState(if (pressed) FocusPalette.pressed else FocusPalette.surface, tween(120), label = "Record press colour")
    Surface(
        onClick = onClick, interactionSource = source,
        modifier = modifier.size(if (large) HomeGeometry.buttonLarge else HomeGeometry.button)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { contentDescription = "화면 녹화 시작"; traversalIndex = -1f },
        shape = CircleShape, color = color, contentColor = FocusPalette.brand,
        border = BorderStroke(1.dp, FocusPalette.buttonLine),
        shadowElevation = if (pressed) 0.dp else 2.dp, tonalElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(VeriFrame, null, modifier = Modifier.size(42.dp))
        }
    }
}

@Composable
private fun FileInputActions(onSelectVideo: () -> Unit, onSelectImage: () -> Unit) {
    Surface(shape = RoundedCornerShape(HomeGeometry.quickRadius), color = FocusPalette.tray) {
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically
        ) {
            FileInputAction("동영상 선택", FocusIcons.video, Modifier.weight(1f), onSelectVideo)
            Box(Modifier.width(1.dp).height(24.dp).background(FocusPalette.line))
            FileInputAction("이미지 선택", FocusIcons.image, Modifier.weight(1f), onSelectImage)
        }
    }
}

@Composable
private fun FileInputAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(HomeGeometry.quickRadius), color = Color.Transparent
    ) {
        Column(
            Modifier.heightIn(min = 76.dp).padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
        ) {
            Icon(icon, null, tint = FocusPalette.brand, modifier = Modifier.size(22.dp))
            Text(label, color = FocusPalette.ink, style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HistoryShortcut(onOpenHistory: () -> Unit) {
    Surface(onClick = onOpenHistory, shape = RoundedCornerShape(14.dp), color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(FocusIcons.history, null, tint = FocusPalette.secondary, modifier = Modifier.size(21.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("분석 기록 보기", color = FocusPalette.ink, style = MaterialTheme.typography.titleSmall)
                Text("지난 확인 결과", color = FocusPalette.secondary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(FocusIcons.chevron, null, tint = FocusPalette.secondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun HomeNavigation(onHome: () -> Unit, onHistory: () -> Unit, onHelp: () -> Unit) {
    val large = LocalLargeView.current
    FocusTheme(large) {
        Column(
            Modifier.fillMaxWidth().background(FocusPalette.surface)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            HorizontalDivider(color = FocusPalette.line)
            Row(Modifier.fillMaxWidth()) {
                listOf(
                    Triple("홈", FocusIcons.home, onHome),
                    Triple("분석 기록", FocusIcons.history, onHistory),
                    Triple("도움말", FocusIcons.help, onHelp)
                ).forEachIndexed { index, (label, icon, onClick) ->
                    val selected = index == 0
                    val color = if (selected) FocusPalette.brand else FocusPalette.secondary
                    Column(
                        Modifier.weight(1f)
                            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                            .heightIn(min = if (large) 68.dp else 56.dp)
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                        Text(label, color = color, style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
