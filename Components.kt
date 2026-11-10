package com.sensorhub.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorhub.app.data.Cloud
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SensorHubTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF90CAF9))
    } else {
        lightColorScheme(primary = Color(0xFF1565C0))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun timeStr(ts: Long): String = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault()).format(Date(ts))

fun fmt(v: Float): String = if (v.isFinite()) "%.4f".format(Locale.US, v) else "n/a"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun NavCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PermissionCard(text: String, onGrant: () -> Unit) {
    InfoCard {
        Text("Permission required", fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onGrant) { Text("Grant permission") }
    }
}

@Composable
fun CloudStatus(): State<Boolean> {
    val state = remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val remove = Cloud.listenConnected { state.value = it }
        onDispose { remove() }
    }
    return state
}

private val chartColors = listOf(
    Color(0xFFE53935), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00ACC1), Color(0xFF6D4C41), Color(0xFF757575),
)

/** Simple live line chart: one line per axis, auto-scaled. */
@Composable
fun LineChart(history: List<List<Float>>, modifier: Modifier = Modifier) {
    val axisCount = history.maxOfOrNull { it.size } ?: 0
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Box(modifier.fillMaxWidth().height(160.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(gridColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
            if (history.size < 2 || axisCount == 0) return@Canvas
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (row in history) for (v in row) if (v.isFinite()) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            if (lo == Float.MAX_VALUE) return@Canvas
            if (hi - lo < 1e-6f) {
                hi += 1f
                lo -= 1f
            }
            for (axis in 0 until minOf(axisCount, chartColors.size)) {
                val path = Path()
                var started = false
                history.forEachIndexed { i, row ->
                    val v = row.getOrNull(axis)?.takeIf { it.isFinite() } ?: return@forEachIndexed
                    val x = size.width * i / (history.size - 1)
                    val y = size.height - (v - lo) / (hi - lo) * size.height
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else path.lineTo(x, y)
                }
                drawPath(path, chartColors[axis], style = Stroke(width = 3f))
            }
        }
    }
}

@Composable
fun listPadding() = PaddingValues(16.dp)

@Composable
fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
