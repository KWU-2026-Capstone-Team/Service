package com.verivid.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.verivid.app.data.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verivid.app.RecordingResult
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Screen { Launcher, Home, Game, CaptureGuide, CaptureReview, Analyzing, Result, Details, History, Help }
internal enum class Risk { Low, Medium, High }

@Composable
fun VeriVidApp(recordingResult: RecordingResult?, onPrepareCapture: () -> Unit, onPlayRecording: (String) -> Unit) {
    val controller: AnalysisController = viewModel()
    val games: GameController = viewModel()
    val analysis by controller.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.selectDocument(uri)
    }
    var imageNotice by remember { mutableStateOf(false) }
    var uploadConsent by remember { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf(Screen.Launcher) }
    var largeView by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(recordingResult?.path) { recordingResult?.let(controller::selectRecording) }
    LaunchedEffect(analysis.phase, analysis.detail?.summary?.id) {
        if (screen == Screen.Game) return@LaunchedEffect
        when (analysis.phase) {
            AnalysisPhase.READY -> screen = Screen.CaptureReview
            AnalysisPhase.PREPARING, AnalysisPhase.RUNNING, AnalysisPhase.ERROR -> screen = Screen.Analyzing
            AnalysisPhase.COMPLETED -> screen = Screen.Result
            AnalysisPhase.IDLE -> if (screen in listOf(Screen.Result, Screen.Details, Screen.Analyzing, Screen.CaptureReview)) screen = Screen.Home
        }
    }
    BackHandler(enabled = screen != Screen.Launcher && screen != Screen.Game) {
        screen = when (screen) { Screen.Details -> Screen.Result; Screen.Home, Screen.History, Screen.Help -> Screen.Launcher; else -> Screen.Home }
    }
    CompositionLocalProvider(LocalLargeView provides largeView) {
        MaterialTheme(typography = veriVidTypography(largeView)) {
            Scaffold(
                containerColor = AppBackground,
                topBar = {
                    if (screen == Screen.Game) Unit
                    else if (screen == Screen.Home) HomeHeader(largeView) { largeView = it }
                    else AppHeader(largeView) { largeView = it }
                },
                bottomBar = {
                    if (screen == Screen.Game) Unit
                    else if (screen == Screen.Home) HomeNavigation(
                        onHome = { screen = Screen.Launcher },
                        onHistory = { screen = Screen.History },
                        onHelp = { screen = Screen.Help }
                    ) else BottomNavigation(
                        current = when (screen) { Screen.Launcher, Screen.Home -> Screen.Launcher; Screen.History -> Screen.History; Screen.Help -> Screen.Help; else -> null },
                        onNavigate = { screen = it }
                    )
                }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (screen) {
                        Screen.Launcher -> FeatureHub(
                            onAnalysis = { screen = when (analysis.phase) {
                                AnalysisPhase.RUNNING, AnalysisPhase.PREPARING -> Screen.Analyzing
                                AnalysisPhase.READY -> Screen.CaptureReview
                                else -> Screen.Home
                            } },
                            onGame = { games.showSetup(); screen = Screen.Game }
                        )
                        Screen.Game -> GameExperience(games) { games.showSetup(); screen = Screen.Launcher }
                        Screen.Home -> HomeScreen(
                            onStartCapture = { screen = if (analysis.phase in listOf(AnalysisPhase.RUNNING, AnalysisPhase.PREPARING)) Screen.Analyzing else Screen.CaptureGuide },
                            onSelectVideo = { if (analysis.phase in listOf(AnalysisPhase.RUNNING, AnalysisPhase.PREPARING)) screen = Screen.Analyzing else picker.launch(arrayOf("video/*")) },
                            onSelectImage = { imageNotice = true },
                            onOpenHistory = { screen = Screen.History }
                        )
                        Screen.CaptureGuide -> CaptureGuideScreen({ screen = Screen.Home }, onPrepareCapture)
                        Screen.CaptureReview -> SelectedVideoScreen(
                            analysis.video, { screen = Screen.Home },
                            onPlay = { analysis.video?.file?.path?.let(onPlayRecording) },
                            onAnalyze = { uploadConsent = true },
                            onRetry = { screen = Screen.CaptureGuide }
                        )
                        Screen.Analyzing -> ConnectedAnalysisScreen(
                            analysis, controller.endpoint,
                            { screen = Screen.Home }, { controller.returnToReady(); screen = if (analysis.video != null) Screen.CaptureReview else Screen.Home }
                        )
                        Screen.Result -> analysis.detail?.takeIf { it.result != null }?.let { detail ->
                            ConnectedResultScreen(detail, { screen = Screen.Home }, { screen = Screen.Details },
                                onPlay = analysis.video?.takeIf { analysis.videoAnalysisId == detail.summary.id }?.let { video -> { onPlayRecording(video.file.path) } })
                        }
                        Screen.Details -> analysis.detail?.let { detail -> ConnectedDetailsScreen(detail, controller) { screen = Screen.Result } }
                        Screen.History -> ConnectedHistoryScreen(analysis, controller::refreshHistory, controller::open)
                        Screen.Help -> HelpScreen()
                    }
                }
            }
        }
    }
    if (imageNotice) AlertDialog(onDismissRequest = { imageNotice = false },
        title = { Text("이미지 분석 준비 중") },
        text = { Text("팀의 현재 모델 API는 영상만 지원해요. 사진을 임의로 영상으로 바꿔 분석하지 않아요. 동영상을 선택해주세요.") },
        confirmButton = { TextButton(onClick = { imageNotice = false }) { Text("확인") } })
    if (uploadConsent) AlertDialog(onDismissRequest = { uploadConsent = false },
        title = { Text("이 영상을 분석 서버로 보낼까요?") },
        text = { Text("선택한 영상이 ${controller.endpoint} 서버로 전송돼요. 알림·대화·개인정보가 포함되지 않았는지 먼저 확인해주세요. 서버는 분석 종료 후 원본을 삭제하고 결과를 기본 24시간 보관해요.") },
        confirmButton = { TextButton(onClick = { uploadConsent = false; controller.analyze() }) { Text("전송하고 분석") } },
        dismissButton = { TextButton(onClick = { uploadConsent = false }) { Text("취소") } })
}

@Composable
private fun AppHeader(large: Boolean, onLargeChanged: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = uiSpace(20, 24), vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("VeriVid", color = NavyDark, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp)
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).toggleable(value = large, role = Role.Switch, onValueChange = onLargeChanged)
                .heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("크게 보기", color = if (large) Accent else Muted, style = MaterialTheme.typography.labelMedium)
            Box(
                Modifier.size(30.dp, 18.dp).clip(CircleShape).background(if (large) Accent else Color(0xFFC9CFD8)).padding(3.dp),
                contentAlignment = if (large) Alignment.CenterEnd else Alignment.CenterStart
            ) { Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White)) }
        }
    }
}

@Composable
private fun BottomNavigation(current: Screen?, onNavigate: (Screen) -> Unit) {
    Column(Modifier.background(Color.White).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(color = Line, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().height(uiSpace(56, 68))) {
            listOf(
                Triple(Screen.Launcher, "홈", VeriIcons.Home),
                Triple(Screen.History, "분석 기록", VeriIcons.History),
                Triple(Screen.Help, "도움말", VeriIcons.HelpOutline)
            ).forEach { (screen, label, icon) ->
                val selected = current == screen
                val color = if (selected) Accent else Muted
                Column(
                    Modifier.weight(1f).fillMaxHeight().selectable(selected = selected, role = Role.Tab, onClick = { onNavigate(screen) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
                ) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(uiSpace(20, 23)))
                    Text(label, color = color, fontSize = if (LocalLargeView.current) 13.sp else 11.sp, lineHeight = if (LocalLargeView.current) 18.sp else 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onStartCapture: () -> Unit, onSelectVideo: () -> Unit, onSelectImage: () -> Unit, onOpenHistory: () -> Unit) {
    HomeWorkspace(onStartCapture, onSelectVideo, onSelectImage, onOpenHistory)
}

@Composable
private fun CaptureGuideScreen(onBack: () -> Unit, onPrepare: () -> Unit) {
    LazyColumn(contentPadding = screenPadding(), verticalArrangement = Arrangement.spacedBy(uiSpace(14, 20))) {
        item { ScreenNavigation("화면 녹화", onBack) }
        item {
            Text("보고 있는 영상을\n잠깐 녹화할게요", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(10.dp))
            Body("다른 앱에서 영상을 연 뒤,\n화면 오른쪽의 VeriVid 버튼을 눌러주세요.")
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NavySoft).padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconTile(VeriIcons.ScreenShare, size = 44, background = Color.White)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("다른 앱에서도 이어서", style = MaterialTheme.typography.titleMedium)
                    Text("화면 오른쪽 VeriVid 버튼으로\n녹화를 시작하고 멈출 수 있어요.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            AppCard {
                GuideStep(1, "녹화 권한을 허용해요", "Android 안내를 따라주세요.")
                GuideStep(2, "확인할 영상을 열어요", "다른 앱으로 이동해도 괜찮아요.")
                GuideStep(3, "VeriVid 버튼을 눌러요", "녹화 시작과 중지를 선택해요.")
            }
        }
        item {
            PrimaryButton("화면 녹화 준비", VeriIcons.ScreenShare, onPrepare)
            Spacer(Modifier.height(12.dp))
            PrivacyNote("녹화만으로 전송되지 않아요. 영상을 확인하고 분석에 동의하면 서버로 보내요.")
        }
    }
}

internal data class RiskInfo(val color: Color, val soft: Color, val lamp: Color, val label: String, val title: String, val description: String)
internal fun riskInfo(risk: Risk) = when (risk) {
    Risk.Low -> RiskInfo(Success, SuccessSoft, Color(0xFF3ACB91), "큰 조작 신호 없음", "큰 조작 신호는\n발견되지 않았어요", "현재 분석에서는 눈에 띄는\n조작 신호가 발견되지 않았어요.")
    Risk.Medium -> RiskInfo(Warning, WarningSoft, Color(0xFFF4C354), "추가 확인 필요", "확실하지 않은\n부분이 있어요", "분석 결과를 신중하게 해석해주세요.\n다른 출처도 함께 확인해주세요.")
    Risk.High -> RiskInfo(Danger, DangerSoft, Color(0xFFF1737B), "다시 확인 필요", "다시 확인이 필요해요", "모델이 조작 의심 신호를 찾았어요.\n중요한 결정은 잠시 멈춰주세요.")
}

@Composable
internal fun TrafficLights(current: Risk) {
    val stages = listOf(Risk.Low, Risk.Medium, Risk.High)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(uiSpace(8, 10))) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(NavyDark).padding(vertical = uiSpace(10, 14)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            stages.forEach { risk ->
                val info = riskInfo(risk)
                val selected = current == risk
                // Use the same equal-width columns as the labels below, not evenly spaced fixed-size lamps.
                Box(Modifier.weight(1f).height(uiSpace(52, 64)).semantics { contentDescription = "${info.label}${if (selected) ", 현재 상태" else ", 비활성"}" }, contentAlignment = Alignment.Center) {
                    if (selected) Box(Modifier.size(uiSpace(52, 64)).clip(CircleShape).background(info.lamp.copy(alpha = .15f)))
                    Box(
                        Modifier.size(if (selected) uiSpace(40, 48) else uiSpace(34, 42)).clip(CircleShape)
                            .background(if (selected) info.lamp else Color(0xFF34445A))
                            .border(1.dp, if (selected) Color.White.copy(alpha = .35f) else Color.White.copy(alpha = .07f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(when (risk) { Risk.Low -> VeriIcons.Check; Risk.Medium -> VeriIcons.Remove; Risk.High -> VeriIcons.PriorityHigh }, null, tint = if (selected) NavyDark else Color(0xFF68788E), modifier = Modifier.size(uiSpace(20, 24)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            stages.forEach { risk ->
                val selected = current == risk
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (risk) { Risk.Low -> "큰 조작\n신호 없음"; Risk.Medium -> "추가 확인\n필요"; Risk.High -> "다시 확인\n필요" },
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center, fontSize = if (LocalLargeView.current) 15.sp else 13.sp,
                        lineHeight = if (LocalLargeView.current) 22.sp else 19.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) riskInfo(risk).color else Muted
                    )
                    if (selected) Text("현재 상태", color = riskInfo(risk).color, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
internal fun ActionCard(risk: Risk, onContact: () -> Unit, onSources: () -> Unit) {
    AppCard {
        SectionTitle("지금 이렇게 해보세요")
        when (risk) {
            Risk.High -> {
                Advice(1, "중요한 결정은 잠시 멈추세요.")
                Advice(2, "원래 알고 있던 번호로 직접 전화해보세요.")
                Advice(3, "가족이나 다른 사람에게 한 번 더 확인하세요.")
                PrimaryButton("가족에게 확인하기", VeriIcons.Call, onContact)
                SecondaryButton("다른 출처 확인하기", VeriIcons.Link, onSources)
            }
            Risk.Medium -> {
                Advice(1, "이 영상만으로 결론 내리지 마세요.")
                Advice(2, "얼굴이 보이는 장면을 다시 확인하세요.")
                Advice(3, "공식 계정이나 다른 출처와 비교하세요.")
                SecondaryButton("다른 출처와 비교하기", VeriIcons.Link, onSources)
            }
            Risk.Low -> {
                Body("큰 조작 신호는 없지만, 중요한 내용이라면 원본 출처도 함께 확인해주세요.")
                SecondaryButton("원본 출처 확인하기", VeriIcons.Link, onSources)
            }
        }
        Text("AI 분석만으로 영상의 진위를 확정할 수는 없어요.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HelpScreen() {
    var open by rememberSaveable { mutableStateOf(0) }
    val faqs = listOf(
        "이 앱은 무엇을 하나요?" to "AI로 영상의 조작 의심 신호를 확인하거나, 판별 게임으로 직접 구분해볼 수 있어요. 현재 모델은 사진 분석을 지원하지 않아요.",
        "결과 색상은 무슨 뜻인가요?" to "초록은 ‘큰 조작 신호 없음’, 노랑은 ‘추가 확인 필요’, 빨강은 ‘다시 확인 필요’예요. 색상 옆 설명도 함께 읽어주세요.",
        "AI 결과를 100% 믿어도 되나요?" to "아니요. 중요한 내용은 원본 출처와 당사자에게 한 번 더 확인해주세요.",
        "딥페이크가 의심되면 어떻게 하나요?" to "중요한 결정을 잠시 멈추고, 원래 알고 있던 번호로 당사자에게 직접 연락하세요."
    )
    LazyColumn(contentPadding = screenPadding(16, 24), verticalArrangement = Arrangement.spacedBy(uiSpace(10, 12))) {
        item {
            IconTile(VeriIcons.HelpOutline, size = 52)
            Spacer(Modifier.height(20.dp))
            Text("궁금한 점이\n있으신가요?", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Body("VeriVid 사용법을 쉽게 알려드릴게요.")
            Spacer(Modifier.height(12.dp))
        }
        items(faqs.indices.toList()) { index ->
            Card(onClick = { open = if (open == index) -1 else index }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = SurfaceSoft)) {
                Column(Modifier.padding(uiSpace(16, 20))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(faqs[index].first, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(VeriIcons.ExpandMore, null, tint = Quiet, modifier = Modifier.size(20.dp).rotate(if (open == index) 180f else 0f))
                    }
                    AnimatedVisibility(open == index) {
                        Text(faqs[index].second, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            AppCard(containerColor = NavySoft) {
                Icon(VeriIcons.Security, null, tint = Accent, modifier = Modifier.size(24.dp))
                Text("급하게 돈을 요구하나요?", style = MaterialTheme.typography.titleMedium)
                Body("먼저 대화를 멈추고, 원래 알고 있던 번호로 가족에게 직접 확인하세요.")
            }
        }
    }
}

@Composable
internal fun AppCard(containerColor: Color = SurfaceSoft, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(uiSpace(20, 24)), colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = Ink), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.fillMaxWidth().padding(uiSpace(16, 20)), verticalArrangement = Arrangement.spacedBy(uiSpace(12, 16)), horizontalAlignment = Alignment.Start, content = content)
    }
}

@Composable
internal fun PrimaryButton(text: String, icon: ImageVector?, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = uiSpace(48, 64)),
        shape = RoundedCornerShape(uiSpace(24, 28)), colors = ButtonDefaults.buttonColors(containerColor = Accent),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = uiSpace(12, 15)), elevation = ButtonDefaults.buttonElevation(0.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun SecondaryButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = uiSpace(48, 64)),
        shape = RoundedCornerShape(uiSpace(24, 28)), border = BorderStroke(1.dp, Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Ink),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = uiSpace(12, 15))
    ) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun ScreenNavigation(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(VeriIcons.ArrowBack, "뒤로", modifier = Modifier.size(21.dp)) }
        Text(title, style = MaterialTheme.typography.titleSmall, color = Muted)
    }
}

@Composable
internal fun IconTile(icon: ImageVector, size: Int = 40, background: Color = NavySoft) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * .27f).dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (background == SurfaceSoft) Navy else Accent, modifier = Modifier.size((size * .48f).dp))
    }
}

@Composable
internal fun StatusLabel(label: String, icon: ImageVector, color: Color, background: Color) {
    Row(Modifier.clip(CircleShape).background(background).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable internal fun Eyebrow(text: String) = Text(text, color = Muted, style = MaterialTheme.typography.labelMedium)
@Composable internal fun SectionTitle(text: String) = Text(text, color = Ink, style = MaterialTheme.typography.titleLarge)
@Composable internal fun Body(text: String) = Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium)

@Composable
internal fun PrivacyNote(text: String, icon: ImageVector = VeriIcons.Lock) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = Muted, modifier = Modifier.padding(top = 3.dp).size(14.dp))
        Text(text, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GuideStep(number: Int, title: String, description: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Text(number.toString(), color = Accent, style = MaterialTheme.typography.titleSmall) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
internal fun Advice(number: Int, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 1.dp).size(26.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Text(number.toString(), color = Accent, style = MaterialTheme.typography.labelMedium) }
        Text(text, color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun ScoreBar(label: String, score: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("$score / 100", style = MaterialTheme.typography.titleSmall)
        }
        LinearProgressIndicator(progress = { score / 100f }, color = Accent, trackColor = Color(0xFFE3E8F0), modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
    }
}

private fun formatDuration(durationMs: Long): String {
    val total = durationMs / 1000
    return String.format(Locale.KOREA, "%02d:%02d", total / 60, total % 60)
}
