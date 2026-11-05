package com.verivid.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun FeatureHub(onAnalysis: () -> Unit, onGame: () -> Unit) {
    val large = LocalLargeView.current
    Column(Modifier.fillMaxSize().background(Color(0xFFF7F8FC)).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("확인하고,\n판별하는 힘.", color = Ink, fontSize = if (large) 31.sp else 27.sp,
                lineHeight = if (large) 41.sp else 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp)
            Text("AI로 영상을 살펴보거나,\n직접 판별하며 연습해보세요.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        HubAction("영상 분석", "SCREEN ANALYSIS", "녹화하거나 저장한 영상을\nAI로 확인해요.", "영상 분석 열기", VeriIcons.ScreenShare,
            Color(0xFF4053B2), Color(0xFFE3E8FF), onAnalysis)
        HubAction("판별 게임", "DEEPFAKE FORENSICS", "같은 영상을 보고 판단한 뒤\n나와 AI의 결과를 비교해요.", "판별 게임 열기", VeriIcons.Game,
            Color(0xFF101C2C), Color(0xFF77E0E9), onGame)
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Icon(VeriIcons.Info, null, tint = Muted, modifier = Modifier.padding(top = 2.dp).size(16.dp))
            Text("AI도, 사람도 틀릴 수 있어요.\n중요한 내용은 출처를 함께 확인해주세요.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HubAction(title: String, eyebrow: String, description: String, action: String, icon: ImageVector,
                      background: Color, accent: Color, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = background, contentColor = Color.White)) {
        Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.align(Alignment.TopEnd).size(126.dp)) {
                val x = size.width * .73f; val y = size.height * .46f; val r = size.width * .35f
                drawCircle(accent.copy(alpha = .05f), r, Offset(x, y))
                drawArc(accent.copy(alpha = .14f), -70f, 260f, false,
                    topLeft = Offset(x - r, y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(1.dp.toPx()))
                drawLine(accent.copy(alpha = .18f), Offset(x - r / 2, y), Offset(x + r / 2, y), 1.dp.toPx(), StrokeCap.Round)
            }
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(25.dp))
                    Text(eyebrow, color = accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = .8.sp)
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(description, color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(action, color = accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Icon(VeriIcons.ChevronRight, null, tint = accent, modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}
