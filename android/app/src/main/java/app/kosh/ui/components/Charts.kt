package app.kosh.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kosh.core.Money
import app.kosh.ui.theme.LocalMoneyColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

data class Slice(val value: Long, val color: Color, val label: String)

/** Ticks at round numbers (1, 2, 5 × 10ⁿ) so axis labels read naturally. */
internal fun niceTop(max: Long): Long {
    if (max <= 0) return 1
    var mag = 1L
    while (mag * 10 <= max) mag *= 10
    // Steps of 1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10 (×10ⁿ) keep the tallest value in the top fifth of the chart.
    for (m in intArrayOf(10, 12, 15, 20, 25, 30, 40, 50, 60, 80, 100)) if (m * mag / 10 >= max) return m * mag / 10
    return 10 * mag
}

/**
 * Donut with tappable slices. Tapping a slice (or its legend row elsewhere, via [selected]) shows
 * that category in the middle; tapping again goes back to the total. Screen readers get [description].
 */
@Composable
fun DonutChart(
    slices: List<Slice>,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    thickness: Dp = 26.dp,
    selected: Int? = null,
    onSelect: ((Int?) -> Unit)? = null,
    center: @Composable () -> Unit = {},
) {
    val total = slices.sumOf { it.value.coerceAtLeast(0) }.toFloat()
    val progress = remember(slices) { Animatable(0f) }
    LaunchedEffect(slices) { progress.animateTo(1f, spring(stiffness = Spring.StiffnessLow)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier.size(size).semantics { contentDescription = description }
            .pointerInput(slices, onSelect) {
                if (onSelect == null) return@pointerInput
                detectTapGestures { o ->
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val r = hypot(o.x - c.x, o.y - c.y)
                    val outer = this.size.width / 2f
                    if (r < outer - thickness.toPx() * 1.6f || r > outer + 8f || total <= 0f) { onSelect(null); return@detectTapGestures }
                    // Angle from 12 o'clock, clockwise.
                    var a = Math.toDegrees(atan2((o.y - c.y).toDouble(), (o.x - c.x).toDouble())).toFloat() + 90f
                    if (a < 0) a += 360f
                    var start = 0f
                    for ((i, s) in slices.withIndex()) {
                        if (s.value <= 0) continue
                        val sweep = 360f * s.value / total
                        if (a >= start && a < start + sweep) { onSelect(if (selected == i) null else i); return@detectTapGestures }
                        start += sweep
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val stroke = thickness.toPx()
            val pad = stroke * 0.18f
            val inset = stroke / 2 + pad
            val arcSize = Size(this.size.width - stroke - pad * 2, this.size.height - stroke - pad * 2)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (total <= 0f) return@Canvas
            var start = -90f
            val gap = if (slices.count { it.value > 0 } > 1) 2.2f else 0f
            for ((i, s) in slices.withIndex()) {
                if (s.value <= 0) continue
                val sweep = 360f * (s.value / total) * progress.value
                val dim = selected != null && selected != i
                val w = if (selected == i) stroke + pad * 2 else stroke
                drawArc(
                    if (dim) s.color.copy(alpha = 0.28f) else s.color,
                    start + gap / 2, (sweep - gap).coerceAtLeast(0.6f), false, Offset(inset, inset), arcSize,
                    style = Stroke(w, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        center()
    }
}

data class BarPoint(val label: String, val income: Long, val expense: Long)

/**
 * Income and spending side by side per period, on a labelled scale, with the net saved shown
 * as a dot (above the line when you saved, below when you spent more). Tap a period for exact figures.
 */
@Composable
fun IncomeExpenseBars(points: List<BarPoint>, description: String, modifier: Modifier = Modifier, height: Dp = 180.dp, currency: String? = null) {
    val colors = LocalMoneyColors.current
    var picked by remember(points) { mutableStateOf<Int?>(null) }
    val top = niceTop(points.maxOfOrNull { maxOf(it.income, it.expense) } ?: 0)
    val progress = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { progress.animateTo(1f, spring(stiffness = Spring.StiffnessLow)) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = label)
    Column(modifier.semantics { contentDescription = description }) {
        val sel = picked?.let(points::getOrNull) ?: points.lastOrNull()
        if (currency != null && sel != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text(sel.label, style = MaterialTheme.typography.labelMedium, color = label)
                    Text(if (picked == null) "Latest" else "Selected", style = MaterialTheme.typography.labelSmall, color = label)
                }
                ChartFigure("Income", Money.ui(sel.income, currency), colors.income)
                ChartFigure("Spent", Money.ui(sel.expense, currency), colors.expense)
                val net = sel.income - sel.expense
                ChartFigure(if (net >= 0) "Saved" else "Overspent", Money.ui(kotlin.math.abs(net), currency), if (net >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(points) {
                detectTapGestures { o ->
                    val axisW = 40.dp.toPx()
                    if (points.isEmpty() || o.x < axisW) return@detectTapGestures
                    val slot = (this.size.width - axisW) / points.size
                    val i = ((o.x - axisW) / slot).toInt().coerceIn(0, points.size - 1)
                    picked = if (picked == i) null else i
                }
            },
        ) {
            val axisW = 40.dp.toPx()
            val bottomPad = 18.dp.toPx()
            val h = size.height - bottomPad
            val w = size.width - axisW
            // Horizontal grid with compact labels at 0, ½ and the top.
            for (f in listOf(0f, 0.5f, 1f)) {
                val y = h - h * f
                drawLine(grid, Offset(axisW, y), Offset(size.width, y), strokeWidth = if (f == 0f) 2f else 1f,
                    pathEffect = if (f == 0f) null else PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                if (currency != null) {
                    val t = measurer.measure(Money.compact((top * f).toLong(), currency), axisStyle)
                    drawText(t, topLeft = Offset(axisW - t.size.width - 6.dp.toPx(), y - t.size.height / 2f))
                }
            }
            if (points.isEmpty()) return@Canvas
            val slot = w / points.size
            val barW = min(slot * 0.28f, 14.dp.toPx())
            for ((i, p) in points.withIndex()) {
                val cx = axisW + slot * i + slot / 2
                if (picked == i) drawRoundRect(highlight, Offset(axisW + slot * i + 2f, 0f), Size(slot - 4f, h), CornerRadius(12f, 12f))
                val hi = h * (p.income.toFloat() / top) * progress.value
                val he = h * (p.expense.toFloat() / top) * progress.value
                val r = CornerRadius(barW / 2, barW / 2)
                if (hi > 0) drawRoundRect(colors.income, Offset(cx - barW - 2f, h - hi), Size(barW, hi), r)
                if (he > 0) drawRoundRect(colors.expense, Offset(cx + 2f, h - he), Size(barW, he), r)
                val t = measurer.measure(p.label, axisStyle.copy(color = if (picked == i) colors.transfer else label))
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, h + 4.dp.toPx()))
            }
        }
    }
}

@Composable
private fun ChartFigure(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

/**
 * Progress bar with a marker showing where spending should be by today. Colour says the outcome:
 * calm when on track, amber when close to the limit or running ahead of pace, red when over.
 */
@Composable
fun BudgetBar(fraction: Float, pace: Float?, modifier: Modifier = Modifier, over: Boolean = false, warn: Boolean = false, height: Dp = 10.dp) {
    val m = LocalMoneyColors.current
    val fill = when {
        over -> MaterialTheme.colorScheme.error
        warn -> m.warning
        else -> MaterialTheme.colorScheme.primary
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val marker = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(fraction) { anim.animateTo(fraction.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow)) }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (anim.value > 0f) drawRoundRect(Brush.horizontalGradient(listOf(fill.copy(alpha = 0.75f), fill)), size = Size(size.width * anim.value, size.height), cornerRadius = r)
        if (pace != null && pace in 0f..1f) {
            val x = size.width * pace
            drawLine(marker, Offset(x, -2f), Offset(x, size.height + 2f), strokeWidth = 4f, cap = StrokeCap.Round)
        }
    }
}

/** Horizontal share bar, e.g. needs / wants / savings. */
@Composable
fun StackedBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
        drawRect(track)
        var x = 0f
        for ((f, c) in parts) {
            val w = size.width * f.coerceIn(0f, 1f)
            if (w <= 0f) continue
            drawRect(c, Offset(x, 0f), Size(w, size.height))
            x += w
            drawLine(track, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        }
    }
}

/**
 * Cumulative spending through a month against the month before, so "am I spending faster than
 * last month?" is one glance. A shaded area shows this month; a dashed line continues today's pace
 * to the month end, and [budget], when set, is drawn as a limit line. Drag or tap to read any day.
 * [current] has one value per day up to today; [previous] covers the whole earlier month.
 */
@Composable
fun PaceChart(
    current: List<Long>,
    previous: List<Long>,
    currency: String,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    daysInMonth: Int = maxOf(current.size, previous.size),
    budget: Long? = null,
) {
    val days = maxOf(daysInMonth, current.size, previous.size, 2)
    // Where today's average daily spend would end the month.
    val unit = java.math.BigDecimal.ONE.movePointRight(app.kosh.core.Currencies.digits(currency)).toLong()
    val projected = if (current.isNotEmpty()) Math.round(current.last().toDouble() / current.size * days / unit) * unit else 0L
    val top = niceTop(maxOf(current.maxOrNull() ?: 0L, previous.maxOrNull() ?: 0L, projected, budget ?: 0L)).toFloat()
    var picked by remember(current, previous) { mutableStateOf<Int?>(null) }
    val line = MaterialTheme.colorScheme.primary
    val ghost = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val limitColor = MaterialTheme.colorScheme.error
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = label)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(current, previous) { anim.animateTo(1f, spring(stiffness = Spring.StiffnessLow)) }
    val day = picked ?: (current.size - 1).coerceAtLeast(0)
    Column(modifier.semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChartFigure("This month · day ${day + 1}", Money.ui(current.getOrElse(day) { current.lastOrNull() ?: 0L }, currency), line)
            ChartFigure("Last month · day ${day + 1}", Money.ui(previous.getOrElse(day) { previous.lastOrNull() ?: 0L }, currency), MaterialTheme.colorScheme.onSurface)
            if (picked == null && current.size in 3 until days) ChartFigure("On course for", Money.ui(projected, currency), if (budget != null && projected > budget) limitColor else label)
        }
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(days) { detectTapGestures { o -> picked = ((o.x / size.width) * (days - 1)).toInt().coerceIn(0, days - 1) } }
                .pointerInput(days) { detectHorizontalDragGestures { change, _ -> picked = ((change.position.x / size.width) * (days - 1)).toInt().coerceIn(0, days - 1) } },
        ) {
            val bottomPad = 16.dp.toPx()
            val w = size.width
            val h = size.height - bottomPad
            fun x(i: Int) = w * i / (days - 1)
            fun y(v: Long) = h - (h * (v / top)) * anim.value
            drawLine(grid, Offset(0f, h), Offset(w, h), strokeWidth = 2f)
            drawLine(grid, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val topLabel = measurer.measure(Money.compact(top.toLong(), currency), axisStyle)
            drawText(topLabel, topLeft = Offset(0f, 0f))
            for (d in listOf(1, days / 2, days)) {
                val t = measurer.measure("$d", axisStyle)
                drawText(t, topLeft = Offset((x(d - 1) - t.size.width / 2f).coerceIn(0f, w - t.size.width), h + 2.dp.toPx()))
            }
            if (budget != null && budget > 0) {
                val by = y(budget)
                drawLine(limitColor.copy(alpha = 0.6f), Offset(0f, by), Offset(w, by), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            }
            fun path(values: List<Long>) = Path().apply {
                values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
            }
            if (previous.size > 1) drawPath(path(previous), ghost, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)), cap = StrokeCap.Round))
            if (current.size > 1) {
                val area = path(current).apply { lineTo(x(current.size - 1), h); lineTo(x(0), h); close() }
                drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), line.copy(alpha = 0.02f)), startY = 0f, endY = h))
                drawPath(path(current), line, style = Stroke(6f, cap = StrokeCap.Round))
                if (current.size < days) {
                    drawLine(line.copy(alpha = 0.45f), Offset(x(current.size - 1), y(current.last())), Offset(x(days - 1), y(projected)),
                        strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)), cap = StrokeCap.Round)
                }
            }
            if (current.isNotEmpty()) {
                val i = day.coerceIn(0, current.size - 1)
                drawLine(line.copy(alpha = 0.25f), Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 2f)
                drawCircle(Color.White, 10f, Offset(x(i), y(current[i])))
                drawCircle(line, 7f, Offset(x(i), y(current[i])))
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot("This month", line)
            LegendDot("Last month", ghost)
            if (budget != null) LegendDot("Budget", limitColor)
        }
    }
}

/**
 * A month as a calendar where each day's colour shows how much was spent: quiet days are pale,
 * heavy days are strong. Tap a day to read its total. Weeks start on Monday.
 */
@Composable
fun SpendCalendar(month: YearMonth, perDay: Map<LocalDate, Long>, currency: String, modifier: Modifier = Modifier, onDay: ((LocalDate) -> Unit)? = null) {
    val max = perDay.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val base = LocalMoneyColors.current.expense
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    val today = java.time.LocalDate.now()
    var picked by remember(month) { mutableStateOf<LocalDate?>(null) }
    val lead = month.atDay(1).dayOfWeek.value - 1
    val cells = lead + month.lengthOfMonth()
    val total = perDay.values.sum()
    val activeDays = perDay.count { it.value > 0 }
    Column(modifier.semantics { contentDescription = "Spending calendar for ${month.month.getDisplayName(DateTextStyle.FULL, Locale.getDefault())}" }) {
        val sel = picked
        Text(
            if (sel != null) "${app.kosh.core.Dates.relativeDay(sel)}: ${Money.ui(perDay[sel] ?: 0, currency)}"
            else "Spent on $activeDays of ${minOf(month.lengthOfMonth(), if (YearMonth.from(today) == month) today.dayOfMonth else month.lengthOfMonth())} days · ${Money.ui(total, currency)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            for (d in DayOfWeek.entries) {
                Text(
                    d.getDisplayName(DateTextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        for (week in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 7) {
                    val idx = week * 7 + col - lead
                    if (idx < 0 || idx >= month.lengthOfMonth()) { Spacer(Modifier.weight(1f).aspectRatio(1f)); continue }
                    val date = month.atDay(idx + 1)
                    val v = perDay[date] ?: 0
                    val f = if (v <= 0) 0f else 0.18f + 0.82f * (v.toFloat() / max)
                    val future = date.isAfter(today)
                    val bg = if (v <= 0) empty.copy(alpha = if (future) 0.4f else 1f) else base.copy(alpha = f)
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(bg)
                            .then(
                                if (date == sel || date == today) Modifier.border(2.dp, if (date == sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                else Modifier,
                            )
                            .clickable(enabled = !future) { picked = if (picked == date) null else date; onDay?.invoke(date) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${idx + 1}", style = MaterialTheme.typography.labelSmall,
                            color = when {
                                f > 0.55f -> Color.White
                                date == today -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (future) 0.5f else 1f)
                            },
                            fontWeight = if (date == today || date == sel) androidx.compose.ui.text.font.FontWeight.Bold else null,
                        )
                    }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Less", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (a in listOf(0.18f, 0.4f, 0.65f, 1f)) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(base.copy(alpha = a)))
            }
            Spacer(Modifier.width(4.dp))
            Text("More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A tiny trend line for list rows and cards; no axes, just shape. */
@Composable
fun Sparkline(values: List<Long>, color: Color, modifier: Modifier = Modifier.size(width = 72.dp, height = 28.dp), fill: Boolean = true) {
    if (values.size < 2) return
    val lo = values.min()
    val hi = values.max()
    val span = (hi - lo).coerceAtLeast(1).toFloat()
    Canvas(modifier) {
        fun x(i: Int) = size.width * i / (values.size - 1)
        fun y(v: Long) = size.height - 3f - (size.height - 6f) * ((v - lo) / span)
        val p = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        if (fill) {
            val a = Path().apply { addPath(p); lineTo(x(values.size - 1), size.height); lineTo(0f, size.height); close() }
            drawPath(a, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
        }
        drawPath(p, color, style = Stroke(4f, cap = StrokeCap.Round))
        drawCircle(color, 5f, Offset(x(values.size - 1), y(values.last())))
    }
}

/**
 * Ranked horizontal bars: label, amount and a bar scaled to the largest. Good for "top categories"
 * and "top payees", where the order and relative size matter more than exact angles.
 */
@Composable
fun RankedBars(rows: List<Triple<String, Long, Color>>, currency: String, modifier: Modifier = Modifier, total: Long? = null, leading: (@Composable (Int) -> Unit)? = null, onClick: ((Int) -> Unit)? = null) {
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((i, r) in rows.withIndex()) {
            val (name, v, color) = r
            Row(
                Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick(i) } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) { leading(i); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (total != null && total > 0 && !LocalHideAmounts.current) {
                            Text("${(v * 100 / total)}%  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MoneyText(v, currency, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
                        Box(Modifier.fillMaxWidth((v.toFloat() / max).coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                    }
                }
            }
        }
    }
}

/** Average spend per weekday, to show which days cost the most. [values] is Monday..Sunday. */
@Composable
fun WeekdayBars(values: List<Long>, currency: String, modifier: Modifier = Modifier, height: Dp = 110.dp) {
    if (values.size != 7) return
    val max = values.max().coerceAtLeast(1)
    val peak = values.indexOf(values.max())
    val color = LocalMoneyColors.current.expense
    val muted = color.copy(alpha = 0.35f)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            for ((i, v) in values.withIndex()) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    if (i == peak && v > 0) Text(Money.compact(v, currency), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
                    Box(
                        Modifier.fillMaxWidth().height((height - 18.dp) * (v.toFloat() / max).coerceAtLeast(0.03f))
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(if (i == peak) color else muted),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in DayOfWeek.entries) {
                Text(d.getDisplayName(DateTextStyle.SHORT, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** Distinct, readable series colours that follow the chosen theme's brand colour first. */
@Composable
fun chartPalette(): List<Color> {
    val m = LocalMoneyColors.current
    return listOf(MaterialTheme.colorScheme.primary, m.transfer, m.warning, Color(0xFF7C3AED), Color(0xFF0891B2), m.expense, Color(0xFFDB2777), Color(0xFF65A30D))
}

/**
 * Where a balance is heading: the likely line, a shaded band between the cautious and hopeful lines,
 * and a zero line when the band dips below it. Drag or tap to read any day.
 */
@Composable
fun ForecastChart(
    dates: List<LocalDate>,
    expected: List<Long>,
    low: List<Long>,
    high: List<Long>,
    currency: String,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 190.dp,
) {
    if (dates.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val danger = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val hide = LocalHideAmounts.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    var pick by remember(dates.size) { mutableStateOf<Int?>(null) }
    // Scale to the data, with zero included only when the balance gets near it or below:
    // a large balance otherwise flattens into a line along the top.
    val rawHi = maxOf(high.max(), expected.max())
    val rawLo = minOf(low.min(), expected.min())
    val pad = ((rawHi - rawLo) / 8).coerceAtLeast(1)
    val hiV = rawHi + pad
    val loV = if (rawLo < 0 || rawLo < (rawHi - rawLo)) minOf(rawLo - pad, 0L) else rawLo - pad
    val span = (hiV - loV).coerceAtLeast(1).toFloat()
    val shown = pick ?: dates.lastIndex
    Column(modifier.semantics { contentDescription = description }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(app.kosh.core.Dates.medium(dates[shown]), style = MaterialTheme.typography.labelMedium, color = labelColor, modifier = Modifier.weight(1f))
            MoneyText(expected[shown], currency, style = MaterialTheme.typography.titleSmall, color = if (expected[shown] < 0) danger else Color.Unspecified)
        }
        if (!hide) Text("likely, between ${Money.ui(low[shown], currency)} and ${Money.ui(high[shown], currency)}", style = MaterialTheme.typography.labelSmall, color = labelColor)
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(dates.size) { detectTapGestures { o -> pick = ((o.x / size.width) * dates.lastIndex).toInt().coerceIn(0, dates.lastIndex) } }
                .pointerInput(dates.size) {
                    detectHorizontalDragGestures(onDragEnd = {}) { change, _ ->
                        pick = ((change.position.x / size.width) * dates.lastIndex).toInt().coerceIn(0, dates.lastIndex)
                    }
                },
        ) {
            val leftPad = 0f
            val topPad = 18.dp.toPx()
            val bottomPad = 18.dp.toPx()
            val h = size.height - bottomPad
            fun x(i: Int) = leftPad + (size.width - leftPad) * i / dates.lastIndex
            fun y(v: Long) = topPad + (h - topPad) * (1f - (v - loV) / span)
            // Gridlines at the top and bottom of the scale.
            for (gy in listOf(topPad, h)) drawLine(grid.copy(alpha = 0.5f), Offset(0f, gy), Offset(size.width, gy), 1f)
            if (loV < 0) drawLine(danger.copy(alpha = 0.7f), Offset(0f, y(0)), Offset(size.width, y(0)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            val band = Path().apply {
                high.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
                for (i in low.indices.reversed()) lineTo(x(i), y(low[i]))
                close()
            }
            drawPath(band, line.copy(alpha = 0.14f))
            val p = Path().apply { expected.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            drawPath(p, line, style = Stroke(5f, cap = StrokeCap.Round))
            val px = x(shown)
            drawLine(labelColor.copy(alpha = 0.5f), Offset(px, 0f), Offset(px, h), 2f)
            drawCircle(if (expected[shown] < 0) danger else line, 8f, Offset(px, y(expected[shown])))
            if (!hide) {
                val top = measurer.measure(Money.compact(hiV, currency), labelStyle)
                drawText(top, topLeft = Offset(4f, topPad - top.size.height - 2f))
                if (loV != 0L) {
                    val bottom = measurer.measure(Money.compact(loV, currency), labelStyle)
                    drawText(bottom, topLeft = Offset(size.width - bottom.size.width - 4f, h - bottom.size.height - 2f))
                }
                if (loV < 0) drawText(measurer.measure(Money.compact(0, currency), labelStyle), topLeft = Offset(4f, y(0) - 16.dp.toPx()))
            }
            for (i in listOf(0, dates.lastIndex / 2, dates.lastIndex)) {
                val t = measurer.measure(app.kosh.core.Dates.short(dates[i]), labelStyle)
                val lx = (x(i) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
                drawText(t, topLeft = Offset(lx, h + 4.dp.toPx()))
            }
        }
    }
}
