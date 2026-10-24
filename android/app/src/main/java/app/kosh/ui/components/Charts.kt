package app.kosh.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kosh.ui.theme.LocalMoneyColors

data class Slice(val value: Long, val color: Color, val label: String)

/** Animated donut. [description] is read by screen readers instead of the drawing. */
@Composable
fun DonutChart(
    slices: List<Slice>,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    thickness: Dp = 22.dp,
    center: @Composable () -> Unit = {},
) {
    val total = slices.sumOf { it.value.coerceAtLeast(0) }.toFloat()
    val progress = remember(slices) { Animatable(0f) }
    LaunchedEffect(slices) { progress.animateTo(1f, spring(stiffness = Spring.StiffnessLow)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = thickness.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (total <= 0f) return@Canvas
            var start = -90f
            val gap = if (slices.size > 1) 1.5f else 0f
            for (s in slices) {
                if (s.value <= 0) continue
                val sweep = 360f * (s.value / total) * progress.value
                drawArc(
                    s.color, start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        center()
    }
}

data class BarPoint(val label: String, val income: Long, val expense: Long)

/** Paired income/expense bars per period. */
@Composable
fun IncomeExpenseBars(points: List<BarPoint>, description: String, modifier: Modifier = Modifier, height: Dp = 160.dp) {
    val colors = LocalMoneyColors.current
    val max = points.maxOfOrNull { maxOf(it.income, it.expense) }?.coerceAtLeast(1) ?: 1
    val progress = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { progress.animateTo(1f, spring(stiffness = Spring.StiffnessLow)) }
    Column(modifier.semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            for (p in points) {
                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                    Bar(p.income.toFloat() / max * progress.value, colors.income)
                    Box(Modifier.width(3.dp))
                    Bar(p.expense.toFloat() / max * progress.value, colors.expense)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            for (p in points) {
                Text(
                    p.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Canvas(Modifier.width(10.dp).fillMaxHeight()) {
        val h = size.height * fraction.coerceIn(0f, 1f)
        if (h > 0f) drawRoundRect(color, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(6f, 6f))
    }
}

/** Progress bar with a marker showing where spending should be by today. */
@Composable
fun BudgetBar(fraction: Float, pace: Float?, modifier: Modifier = Modifier, over: Boolean = false, warn: Boolean = false) {
    val m = LocalMoneyColors.current
    val fill = when {
        over -> MaterialTheme.colorScheme.error
        warn -> m.warning
        else -> MaterialTheme.colorScheme.primary
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val marker = MaterialTheme.colorScheme.onSurface
    val anim = remember { Animatable(0f) }
    LaunchedEffect(fraction) { anim.animateTo(fraction.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow)) }
    Canvas(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        drawRect(track)
        drawRect(fill, size = Size(size.width * anim.value, size.height))
        if (pace != null && pace in 0f..1f) {
            val x = size.width * pace
            drawLine(marker, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        }
    }
}

/** Horizontal share bar, e.g. needs / wants / savings. */
@Composable
fun StackedBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
        drawRect(track)
        var x = 0f
        for ((f, c) in parts) {
            val w = size.width * f.coerceIn(0f, 1f)
            drawRect(c, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}
