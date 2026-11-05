package com.verivid.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verivid.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun ModelResult.risk() = when (trafficLight) {
    TrafficLight.GREEN -> Risk.Low
    TrafficLight.YELLOW -> Risk.Medium
    TrafficLight.RED -> Risk.High
}

@Composable
internal fun SelectedVideoScreen(video: SelectedVideo?, onBack: () -> Unit, onPlay: () -> Unit, onAnalyze: () -> Unit, onRetry: () -> Unit) {
    LazyColumn(contentPadding = screenPadding(), verticalArrangement = Arrangement.spacedBy(uiSpace(14, 20))) {
        item { ScreenNavigation("분석할 영상", onBack) }
        item {
            StatusLabel("영상 준비 완료", VeriIcons.CheckCircle, Success, SuccessSoft)
            Spacer(Modifier.height(12.dp))
            Text("이 영상을\n확인할까요?", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Body("개인정보가 담겨 있지 않은지 먼저 살펴보세요.")
        }
        item {
            AppCard {
                IconTile(VeriIcons.Videocam, 52)
                Text(video?.name ?: "영상을 선택해주세요", style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                video?.let {
                    Text(String.format(Locale.KOREA, "%.1f MB", it.file.length() / 1048576.0) +
                        (it.durationMs?.let { ms -> " · ${timeLabel(ms / 1000.0)}" } ?: ""), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                SecondaryButton("영상 다시 보기", VeriIcons.PlayArrow, onPlay)
            }
        }
        if (video != null) item { PrimaryButton("이 영상 분석하기", VeriIcons.BarChart, onAnalyze) }
        item { TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("다시 녹화하기") } }
        item { PrivacyNote("분석에 동의하면 선택한 영상을 팀 분석 서버로 전송해요. 최대 5분·500MB 영상을 지원해요.") }
    }
}

@Composable
internal fun ConnectedAnalysisScreen(state: AnalysisState, endpoint: String, onBack: () -> Unit, onRetry: () -> Unit) {
    val failed = state.phase == AnalysisPhase.ERROR
    val stage = when (state.stage) {
        "CONNECTING" -> "분석 서버 연결 중"
        "UPLOADING" -> "영상 전송 중"
        "QUEUED" -> "분석 순서를 기다리고 있어요"
        "STARTING", "VALIDATING_VIDEO" -> "영상 형식 확인 중"
        "MODEL_INFERENCE_AND_XAI" -> "AI 분석 및 주목 영역 생성 중"
        "LOADING" -> "분석 결과 불러오는 중"
        else -> if (state.phase == AnalysisPhase.PREPARING) "선택한 영상 준비 중" else "결과 정리 중"
    }
    LazyColumn(contentPadding = screenPadding(), verticalArrangement = Arrangement.spacedBy(uiSpace(16, 24))) {
        item { ScreenNavigation(if (failed) "분석 안내" else "영상 분석", onBack) }
        item {
            IconTile(if (failed) VeriIcons.Info else VeriIcons.BarChart, 56)
            Spacer(Modifier.height(20.dp))
            Text(if (failed) "분석을 마치지\n못했어요" else "영상을\n살펴보고 있어요", style = MaterialTheme.typography.headlineLarge)
        }
        item {
            AppCard {
                Text(state.video?.name ?: state.detail?.summary?.filename ?: "영상 분석", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (failed) {
                    Body(state.failure?.message ?: "서버 상태를 확인해주세요.")
                    Text(state.failure?.code ?: "", style = MaterialTheme.typography.labelSmall, color = Muted)
                } else {
                    Text(stage, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp), color = Accent, trackColor = Line)
                    Body("서버의 실제 처리 상태를 보여드려요. CPU에서는 몇 분 걸릴 수 있어요.")
                }
            }
        }
        if (failed) item { PrimaryButton(if (state.video != null) "영상 확인으로 돌아가기" else "홈으로 돌아가기", VeriIcons.Replay, onRetry) }
        item {
            PrivacyNote(if (failed) "실패한 분석을 정상 결과로 표시하지 않아요." else "이 화면을 나가도 서버 분석은 계속돼요. 분석 기록에서 결과를 확인할 수 있어요.", VeriIcons.Info)
            Spacer(Modifier.height(8.dp))
            Text("분석 서버: $endpoint", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun ConnectedResultScreen(detail: AnalysisDetail, onBack: () -> Unit, onDetails: () -> Unit, onPlay: (() -> Unit)?) {
    val result = detail.result ?: return
    val risk = result.risk(); val info = riskInfo(risk)
    val context = LocalContext.current
    var sources by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = screenPadding(), verticalArrangement = Arrangement.spacedBy(uiSpace(12, 16))) {
        item { ScreenNavigation("분석 결과", onBack) }
        item {
            AppCard(info.soft) {
                StatusLabel(info.label, if (risk == Risk.Low) VeriIcons.CheckCircle else VeriIcons.Info, info.color, Color.White)
                Text(info.title, style = MaterialTheme.typography.headlineMedium)
                Text("행동 신호등", color = Muted, style = MaterialTheme.typography.labelMedium)
                TrafficLights(risk)
                Body(info.description)
            }
        }
        item {
            AppCard {
                SectionTitle(if (risk == Risk.Low) "무엇을 확인했나요?" else "왜 의심스러운가요?")
                Body(when {
                    risk == Risk.Low -> "분석한 얼굴 프레임에서 큰 의심 신호가 나타나지 않았어요. 영상 전체의 진위를 보증하지는 않아요."
                    result.primarySignal == "SPATIAL" -> "얼굴의 경계·색상·질감 특징에 공간 분석 모델이 더 크게 반응했어요. 구체적인 조작 위치나 기법을 확정한 것은 아니에요."
                    result.primarySignal == "TEMPORAL" -> "입 주변 프레임의 시간적 특징에 분석 모델이 더 크게 반응했어요. 실제 조작이나 부자연스러운 움직임을 확정한 것은 아니에요."
                    else -> "얼굴의 외형과 입 주변 프레임을 함께 분석한 결과예요. 원본 출처와 비교해 확인해주세요."
                })
                Text("사용한 얼굴 프레임 ${result.nFaces}개 · 영상 전체를 빠짐없이 검사한 결과는 아니에요.", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (onPlay != null) SecondaryButton("분석한 영상 다시 보기", VeriIcons.PlayArrow, onPlay)
                Text("현재 모델은 의심 구간의 시작·종료 시간을 제공하지 않아요.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { ActionCard(risk,
            onContact = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL)) }.onFailure { sources = true } },
            onSources = { sources = true }) }
        item {
            TextButton(onClick = onDetails, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("자세한 AI 분석 보기", color = Muted) }
        }
        item { PrivacyNote("신호등은 팀 서비스의 시범 정책이에요. AI 분석은 진위의 확정 판정이 아니에요.", VeriIcons.Info) }
    }
    if (sources) AlertDialog(onDismissRequest = { sources = false }, title = { Text("다른 방법으로 확인해주세요") },
        text = { Text("영상을 올린 원본 계정과 게시 날짜를 확인하세요. 공식 홈페이지·다른 보도와 비교하고, 당사자에게는 원래 알고 있던 번호로 연락하세요.") },
        confirmButton = { TextButton(onClick = { sources = false }) { Text("확인") } })
}

@Composable
internal fun ConnectedDetailsScreen(detail: AnalysisDetail, controller: AnalysisController, onBack: () -> Unit) {
    val result = detail.result ?: return
    LazyColumn(contentPadding = screenPadding(), verticalArrangement = Arrangement.spacedBy(uiSpace(12, 16))) {
        item { ScreenNavigation("상세 AI 분석", onBack) }
        item {
            AppCard {
                SectionTitle("모델 의심 신호 점수")
                ScoreBar("종합 · Fused", (result.fused * 100).toInt())
                ScoreBar("얼굴 외형 · Spatial", (result.spatial * 100).toInt())
                ScoreBar("입 주변 시간 특징 · Temporal", (result.temporal * 100).toInt())
                Body("0–100 모델 신호 점수예요. 실제 조작 확률이나 모델의 정확도·신뢰도가 아니에요.")
            }
        }
        item {
            AppCard {
                SectionTitle("분석에 사용한 프레임")
                Text("얼굴 프레임 ${result.nFaces}개 · 반복 패딩 ${result.paddedFrames}개", style = MaterialTheme.typography.bodyMedium)
                if (result.samples.isEmpty()) Body("서버가 프레임 시간을 제공하지 않았어요.")
                else {
                    result.samples.chunked(4).forEach { samples ->
                        Text(samples.joinToString("    ") { timeLabel(it.seconds) }, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Body("표시한 시간은 얼굴을 추출한 시점이에요. 구간별 위험도나 의심 구간이 아니에요. 동일 인물 추적도 지원하지 않아요.")
            }
        }
        item { SectionTitle("모델 주목 영역 · Grad-CAM") }
        if (detail.artifacts.isEmpty()) item { AppCard { Body("서버가 주목 영역 이미지를 제공하지 않았어요. 이미지를 임의로 생성하지 않아요.") } }
        items(detail.artifacts, key = { it.kind }) { artifact ->
            ArtifactPanel(artifact, controller)
        }
        item {
            AppCard {
                Body("히트맵은 대표 얼굴·입 이미지에서 모델이 주목한 영역이에요. 조작의 물증이나 조작 마스크가 아니에요.")
                if (result.warnings.isNotEmpty()) Text("분석 안내: ${result.warnings.joinToString()}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("모델 ${result.modelVersion}\n보정 ${result.calibrationVersion}\n정책 ${result.policyVersion}\n판정 근거 ${result.reason}", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ArtifactPanel(artifact: AnalysisArtifact, controller: AnalysisController) {
    var bitmap by remember(artifact.path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(artifact.path) { mutableStateOf(false) }
    var errorCode by remember(artifact.path) { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(artifact.path, retry) {
        failed = false
        try {
            val bytes = controller.artifact(artifact.path)
            bitmap = withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                check(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: error("Invalid image")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed = true; errorCode = (e as? ApiFailure)?.code ?: e.javaClass.simpleName }
    }
    AppCard {
        Text(when (artifact.kind) {
            "cam_spatial" -> "얼굴 외형 주목 영역"
            "cam_temporal" -> "입 주변 시간 특징 주목 영역"
            "spatial_original" -> "분석한 얼굴 원본"
            "temporal_original" -> "분석한 입 주변 원본"
            else -> artifact.kind
        }, style = MaterialTheme.typography.titleMedium)
        when {
            bitmap != null -> Image(bitmap!!, "서버가 반환한 ${artifact.kind}", modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit)
            failed -> {
                TextButton(onClick = { retry++ }) { Text("이미지를 불러오지 못했어요 · 재시도") }
                Text(errorCode, color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun ConnectedHistoryScreen(state: AnalysisState, onRefresh: () -> Unit, onOpen: (String) -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = state.history.groupBy { dateLabel(it.createdAt) }
    LazyColumn(contentPadding = screenPadding(16, 24), verticalArrangement = Arrangement.spacedBy(uiSpace(10, 14))) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("분석 기록", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("새로고침") }
            }
            Body("날짜를 누르면 그날의 영상이 보여요.")
        }
        if (state.historyLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        if (state.historyError != null) item { AppCard { Body(state.historyError); TextButton(onClick = onRefresh) { Text("다시 불러오기") } } }
        if (!state.historyLoading && state.historyError == null && groups.isEmpty()) item {
            AppCard { Text("아직 분석한 영상이 없어요.", style = MaterialTheme.typography.titleMedium); Body("영상 분석을 마치면 이곳에서 다시 볼 수 있어요.") }
        }
        groups.forEach { (date, entries) ->
            item(key = date) {
                AppCard(Color.White) {
                    Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == date) null else date }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(VeriIcons.Calendar, 36)
                        Spacer(Modifier.width(12.dp))
                        Text(date, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${entries.size}개", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Icon(VeriIcons.ExpandMore, if (expanded == date) "접기" else "펼치기", tint = Muted)
                    }
                    if (expanded == date) entries.forEach { entry ->
                        HorizontalDivider(color = Line)
                        Row(Modifier.fillMaxWidth().clickable { onOpen(entry.id) }.heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(VeriIcons.Videocam, 34)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.filename, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val known = state.detail?.takeIf { it.summary.id == entry.id }?.result
                                Text(known?.let { riskInfo(it.risk()).label } ?: when (entry.status) {
                                    "COMPLETED" -> "분석 완료 · 결과 보기"
                                    "FAILED" -> "분석 실패 · 사유 보기"
                                    "QUEUED" -> "분석 대기 중"
                                    else -> "분석 처리 중"
                                }, color = known?.let { riskInfo(it.risk()).color } ?: Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(VeriIcons.ChevronRight, "분석 열기", tint = Muted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        item { PrivacyNote("현재 기기의 서버 세션 기록 최대 100개를 보여줘요. 결과는 서버 보관 기간 후 만료될 수 있어요.", VeriIcons.Info) }
    }
}

private fun timeLabel(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return String.format(Locale.KOREA, "%02d:%02d", total / 60, total % 60)
}
private fun dateLabel(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd (E)", Locale.KOREA))
}.getOrElse { value.take(10) }
