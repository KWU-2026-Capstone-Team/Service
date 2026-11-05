package com.verivid.app.ui

import android.app.Activity
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.verivid.app.data.*
import java.util.Locale

private object Forensic {
    val background = Color(0xFF0D1726)
    val surface = Color(0xFF152237)
    val line = Color(0xFF29394E)
    val text = Color(0xFFF1F5FC)
    val secondary = Color(0xFFADBBD0)
    val cyan = Color(0xFF77E0E9)
    val green = Color(0xFF9CDEBE)
    val rose = Color(0xFFF6A6B3)
}

@Composable
internal fun GameExperience(controller: GameController, onExit: () -> Unit) {
    val state by controller.state.collectAsState()
    var leaving by remember { mutableStateOf(false) }
    val datasetUnavailable = state.phase == GamePhase.ERROR && state.failure?.code == "GAME_DATASET_NOT_READY"
    val back = {
        when {
            datasetUnavailable -> controller.showSetup()
            state.phase == GamePhase.SETUP || state.phase == GamePhase.RESULT -> onExit()
            else -> leaving = true
        }
    }
    BackHandler(onBack = back)
    GameChrome()
    Column(Modifier.fillMaxSize().background(Forensic.background)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(VeriIcons.ArrowBack, if (datasetUnavailable) "게임 설정으로" else "뒤로 가기", tint = Forensic.text) }
            Column(Modifier.weight(1f)) {
                Text("DEEPFAKE", color = Forensic.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp)
                Text("FORENSICS", color = Forensic.cyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            }
            Text("VeriVid", color = Forensic.secondary, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
        }
        when (state.phase) {
            GamePhase.SETUP -> GameSetup(state, controller)
            GamePhase.LOADING -> GameLoading("게임을 준비하고 있어요", "서버의 문제와 영상을 불러오는 중이에요.")
            GamePhase.WATCHING, GamePhase.SUBMITTING -> GamePlay(state, controller)
            GamePhase.REVEALING -> GameRevealScreen(state, controller::next)
            GamePhase.RESULT -> GameReportScreen(state.report, controller::showSetup)
            GamePhase.ERROR, GamePhase.MEDIA_ERROR -> GameFailure(state, controller)
        }
    }
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("게임에서 나갈까요?") },
        text = { Text("제출한 답변은 서버에 저장돼요. 게임 선택 화면에서 이어할 수 있어요.") },
        confirmButton = { TextButton(onClick = { leaving = false; onExit() }) { Text("나가기") } },
        dismissButton = { TextButton(onClick = { leaving = false }) { Text("계속하기") } })
}

@Composable
private fun GameChrome() {
    val view = LocalView.current
    val apply = {
        (view.context as? Activity)?.window?.let { window ->
            window.statusBarColor = Forensic.background.toArgb(); window.navigationBarColor = Forensic.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        }
    }
    SideEffect { apply() }
    LaunchedEffect(view) { withFrameNanos { }; apply() }
    DisposableEffect(view) { onDispose {
        (view.context as? Activity)?.window?.let { window ->
            window.statusBarColor = Color.White.toArgb(); window.navigationBarColor = Color.White.toArgb()
            WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        }
    } }
}

@Composable
private fun GameSetup(state: GameState, controller: GameController) {
    var difficulty by rememberSaveable { mutableStateOf(GameDifficulty.all) }
    var rounds by rememberSaveable { mutableIntStateOf(5) }
    LaunchedEffect(Unit) { controller.refreshHistory() }
    LazyColumn(contentPadding = PaddingValues(24.dp, 12.dp, 24.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            GameTitle("당신의 눈은\n구분할 수 있을까요?")
            Spacer(Modifier.height(10.dp))
            GameBody("짧은 영상을 보고 원본인지 조작인지 판단해보세요. 답변 뒤 해설과 AI 결과를 확인할 수 있어요.")
        }
        state.activeId?.let { id -> item {
            GamePanel {
                GameSection("진행 중인 게임이 있어요")
                GameButton("이어서 플레이", onClick = { controller.resume(id) })
            }
        } }
        item {
            GameSection("난이도")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GameDifficulty.values().forEach { level ->
                    GameChoice(level.label, level == difficulty, Modifier.weight(1f)) { difficulty = level }
                }
            }
        }
        item {
            GameSection("라운드 수")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15).forEach { count -> GameChoice("${count}문제", rounds == count, Modifier.weight(1f)) { rounds = count } }
            }
        }
        item {
            GameButton("판별 시작", onClick = { controller.create(rounds, difficulty) })
            Spacer(Modifier.height(10.dp))
            GameBody("시간 제한 없음 · 음소거 재생 · 다시 보기 1회\n선택한 난이도의 영상이 부족하면 다른 설정을 안내해요.")
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("최근 게임", color = Forensic.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = controller::refreshHistory) { Text("새로고침", color = Forensic.cyan) }
            }
            if (state.historyLoading) LinearProgressIndicator(color = Forensic.cyan, trackColor = Forensic.line, modifier = Modifier.fillMaxWidth())
            if (state.historyError != null) GameBody(state.historyError)
            else if (state.history.isEmpty() && !state.historyLoading) GameBody("아직 플레이한 게임이 없어요.")
        }
        items(state.history.take(5), key = { it.id }) { game ->
            Surface(onClick = { controller.resume(game.id) }, color = Forensic.surface, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${game.totalRounds}문제 · ${GameDifficulty.values().find { it.name == game.difficulty }?.label ?: game.difficulty}", color = Forensic.text, style = MaterialTheme.typography.titleSmall)
                        GameBody(if (game.status == "COMPLETED") "${game.score}점 · 결과 보기" else "${game.answeredRounds}/${game.totalRounds} 진행 · 이어하기")
                    }
                    Icon(VeriIcons.ChevronRight, "게임 열기", tint = Forensic.secondary)
                }
            }
        }
        item { GameBody("AI도 정답을 틀릴 수 있어요. 게임의 정답은 데이터셋의 원본·조작 기록을 기준으로 해요.") }
    }
}

@Composable
private fun GamePlay(state: GameState, controller: GameController) {
    val game = state.game ?: return; val round = state.round ?: return
    var confidence by rememberSaveable(game.id, round.number) { mutableStateOf(GameConfidence.MEDIUM) }
    val submitting = state.phase == GamePhase.SUBMITTING
    Column(Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ROUND ${round.number.toString().padStart(2, '0')} / ${game.totalRounds.toString().padStart(2, '0')}", color = Forensic.cyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${game.score}점", color = Forensic.text, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { game.answeredRounds.toFloat() / game.totalRounds }, color = Forensic.cyan,
                trackColor = Forensic.line, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
        item {
            GameSection("원본일까요, 조작일까요?")
            Spacer(Modifier.height(12.dp))
            NativeGameVideo(state, controller)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.playbackEnded) "재생 완료 · 지금 답변할 수 있어요" else "음소거 재생 · 시간 제한 없음", color = Forensic.secondary,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = controller::replay, enabled = state.videoReady && state.replayCount == 0 && !submitting) {
                    Text(if (state.replayCount == 0) "다시 보기 1회" else "다시 보기 사용", color = if (state.replayCount == 0) Forensic.cyan else Forensic.secondary)
                }
            }
        }
        item {
            Text("얼마나 확신하나요?", color = Forensic.secondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(GameConfidence.LOW to "잘 모르겠어요", GameConfidence.MEDIUM to "어느 정도", GameConfidence.HIGH to "확신해요").forEach { (value, label) ->
                    GameChoice(label, confidence == value, Modifier.weight(1f), enabled = !submitting) { confidence = value }
                }
            }
        }
        item { if (submitting) GameBody("서버가 답변을 확인하고 있어요…") else if (!state.videoReady) GameBody("영상을 재생할 준비를 하고 있어요…") }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GameAnswerButton("REAL", "원본 영상", Forensic.green, Modifier.weight(1f), state.videoReady && !submitting) { controller.answer(GameVerdict.REAL, confidence) }
        GameAnswerButton("FAKE", "조작 영상", Forensic.rose, Modifier.weight(1f), state.videoReady && !submitting) { controller.answer(GameVerdict.FAKE, confidence) }
    }
    }
}

@Composable
private fun NativeGameVideo(state: GameState, controller: GameController) {
    val video = state.video ?: return
    val owner = LocalLifecycleOwner.current
    val holder = remember(video.path) { arrayOfNulls<VideoView>(1) }
    val current by rememberUpdatedState(state)
    DisposableEffect(owner, video.path) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) holder[0]?.pause()
            if (event == Lifecycle.Event.ON_RESUME && current.phase == GamePhase.WATCHING && !current.playbackEnded) holder[0]?.start()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); holder[0]?.stopPlayback(); holder[0] = null }
    }
    key(video.path) {
        AndroidView(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp))
            .background(Color.Black).semantics { contentDescription = "판별할 영상" },
            factory = { context ->
                FrameLayout(context).apply {
                    val player = VideoView(context).apply {
                        holder[0] = this
                        contentDescription = "판별할 영상"
                        tag = state.replaySignal
                        setOnPreparedListener { media -> media.setVolume(0f, 0f); controller.mediaPrepared(video.path); start() }
                        setOnCompletionListener { controller.mediaEnded(video.path) }
                        setOnErrorListener { _, _, _ -> controller.mediaFailed(video.path); true }
                        setVideoURI(Uri.fromFile(video))
                    }
                    addView(player, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                }
            },
            update = {
                holder[0]?.let { player ->
                    if (state.phase != GamePhase.WATCHING) player.pause()
                    else if (player.tag != state.replaySignal) { player.tag = state.replaySignal; player.seekTo(0); player.start() }
                }
            })
    }
}

@Composable
private fun GameRevealScreen(state: GameState, onNext: () -> Unit) {
    val reveal = state.reveal ?: return
    val positive = reveal.userCorrect == true
    Column(Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(24.dp, 12.dp, 24.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("ROUND ${reveal.round.toString().padStart(2, '0')} · RESULT", color = Forensic.cyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Icon(if (positive) VeriIcons.CheckCircle else VeriIcons.Info, null,
                tint = if (positive) Forensic.green else Forensic.rose, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            GameTitle(if (reveal.skipped) "이번 영상은\n건너뛰었어요" else if (positive) "정답이에요" else "다시 살펴볼까요?")
            Spacer(Modifier.height(10.dp))
            GameBody(if (reveal.skipped) "재생하지 못한 문제는 오답과 별도로 기록해요." else "+${reveal.points}점 · ${reveal.streak}연속 정답")
        }
        item {
            GamePanel {
                GameSection("데이터셋 정답 · ${reveal.groundTruth.name}")
                GameBody("나의 답변 · ${reveal.answer?.name ?: "건너뛰기"}")
                GameBody(reveal.explanation)
                if (reveal.cues.isNotEmpty()) {
                    GameSection("팀에서 제공한 판별 단서")
                    reveal.cues.forEach { GameBody("• $it") }
                }
                if (reveal.tips.isNotEmpty()) {
                    GameSection("다른 영상에도 참고할 요령")
                    reveal.tips.forEach { GameBody("• $it") }
                }
            }
        }
        item {
            GamePanel {
                GameSection("같은 영상의 AI 판단")
                if (reveal.ai == null) GameBody("실제 모델 평가값이 없는 영상이에요. AI 점수를 임의로 만들지 않아요.")
                else {
                    Text("${reveal.ai.verdict.name} · ${if (reveal.aiCorrect == true) "정답" else "오답"}", color = Forensic.cyan, style = MaterialTheme.typography.titleMedium)
                    GameBody("AI 판단도 데이터셋 정답과 다를 수 있어요.")
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp)) {
        GameButton(if (state.game?.status == "COMPLETED") "전체 결과 보기" else "다음 라운드", onClick = onNext)
    }
    }
}

@Composable
private fun GameReportScreen(report: GameReport?, onAgain: () -> Unit) {
    report ?: return
    LazyColumn(contentPadding = PaddingValues(24.dp, 12.dp, 24.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("TRAINING REPORT", color = Forensic.cyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp)); GameTitle("판별 훈련을\n마쳤어요")
        }
        item {
            GamePanel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(report.grade, color = Forensic.cyan, fontSize = 48.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${report.score}점", color = Forensic.text, style = MaterialTheme.typography.headlineMedium)
                }
                GameBody("나의 정답률 ${percent(report.userAccuracy)} · ${report.userCorrect}/${report.playedRounds}문제")
                GameBody("AI 정답률 ${percent(report.aiAccuracy)} · ${report.aiCorrect?.toString() ?: "—"}/${report.aiEvaluatedRounds}문제")
                GameBody("최대 ${report.maxStreak}연속 · 평균 ${report.averageSeconds?.let { String.format(Locale.KOREA, "%.1f초", it) } ?: "기록 없음"}")
                GameBody("전체 ${report.totalRounds}문제 · 플레이 ${report.playedRounds} · 건너뜀 ${report.skippedRounds}")
            }
        }
        item { GameBody("AI 비교는 실제 평가값이 있는 ${report.aiEvaluatedRounds}문제 기준이에요. 이 게임의 정답률은 모델 전체 성능이나 판별 능력 향상의 증거가 아니에요.") }
        item { GameButton("다시 도전하기", onClick = onAgain) }
        item { GameSection("라운드별 기록") }
        items(report.rounds, key = { it.round }) { reveal ->
            GamePanel {
                Text("${reveal.round}번 · ${if (reveal.skipped) "건너뜀" else if (reveal.userCorrect == true) "정답" else "오답"}", color = Forensic.text, style = MaterialTheme.typography.titleSmall)
                GameBody("나 ${reveal.answer?.name ?: "—"} / 정답 ${reveal.groundTruth.name} / AI ${reveal.ai?.verdict?.name ?: "미평가"}")
                GameBody(reveal.explanation)
            }
        }
    }
}

@Composable
private fun GameFailure(state: GameState, controller: GameController) {
    LazyColumn(contentPadding = PaddingValues(24.dp, 20.dp, 24.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Icon(VeriIcons.Info, null, tint = Forensic.rose, modifier = Modifier.size(36.dp)); Spacer(Modifier.height(16.dp)); GameTitle("잠시 확인이\n필요해요") }
        item { GameBody(state.failure?.message ?: "게임을 불러오지 못했어요."); Spacer(Modifier.height(8.dp)); Text(state.failure?.code ?: "", color = Forensic.secondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        if (state.phase == GamePhase.MEDIA_ERROR) {
            item { GameButton("영상 다시 불러오기", onClick = controller::retryVideo) }
            item { GameButton("재생 불가로 건너뛰기", outlined = true, onClick = controller::skipUnavailableVideo) }
            item { GameBody("이 문제는 오답이 아닌 재생 실패로 별도 기록돼요.") }
        } else {
            if (state.game != null || state.failure?.code == "ANSWER_PENDING") item { GameButton("진행 상태 확인 · 재시도", onClick = controller::retry) }
            item { GameButton("게임 설정으로", outlined = true, onClick = controller::showSetup) }
        }
    }
}

@Composable
private fun GameLoading(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(36.dp)); GameTitle(title)
        LinearProgressIndicator(color = Forensic.cyan, trackColor = Forensic.line, modifier = Modifier.fillMaxWidth())
        GameBody(body)
    }
}

@Composable
private fun GamePanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Forensic.surface, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun GameTitle(text: String) = Text(text, color = Forensic.text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
@Composable private fun GameSection(text: String) = Text(text, color = Forensic.text, style = MaterialTheme.typography.titleMedium)
@Composable private fun GameBody(text: String) = Text(text, color = Forensic.secondary, style = MaterialTheme.typography.bodySmall)
@Composable
private fun GameButton(text: String, outlined: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (outlined) Forensic.surface else Forensic.cyan,
            contentColor = if (outlined) Forensic.text else Forensic.background), border = if (outlined) BorderStroke(1.dp, Forensic.line) else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) { Text(text, style = MaterialTheme.typography.labelLarge) }
}
@Composable
private fun GameChoice(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp).semantics { this.selected = selected; role = Role.RadioButton }, shape = RoundedCornerShape(12.dp),
        color = if (selected) Forensic.cyan.copy(alpha = .12f) else Forensic.surface, border = BorderStroke(1.dp, if (selected) Forensic.cyan else Forensic.line)) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, color = if (selected) Forensic.cyan else Forensic.secondary, fontSize = if (LocalLargeView.current) 14.sp else 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
@Composable
private fun GameAnswerButton(title: String, subtitle: String, tint: Color, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, color = tint.copy(alpha = if (enabled) .12f else .04f),
        border = BorderStroke(1.dp, tint.copy(alpha = if (enabled) .8f else .2f)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = if (enabled) tint else Forensic.secondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 23.sp)
            Text(subtitle, color = Forensic.secondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
private fun percent(value: Double?) = value?.let { "${(it * 100).toInt()}%" } ?: "—"
