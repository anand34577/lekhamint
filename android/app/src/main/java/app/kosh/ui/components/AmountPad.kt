package app.kosh.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Calculator-style amount pad (docs/01, "Quick add"): opens instantly with no
 * system keyboard, supports + − × ÷, and "=" folds the expression into a number.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AmountPad(onKey: (Char) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val rows = listOf(
        listOf('7', '8', '9', '÷'),
        listOf('4', '5', '6', '×'),
        listOf('1', '2', '3', '−'),
        listOf('.', '0', '⌫', '+'),
    )
    Column(modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (key in row) {
                    val isOp = key in "÷×−+"
                    val label = when (key) {
                        '⌫' -> "Delete"
                        '÷' -> "Divide"
                        '×' -> "Multiply"
                        '−' -> "Minus"
                        '+' -> "Plus"
                        '.' -> "Decimal point"
                        else -> key.toString()
                    }
                    Surface(
                        color = if (isOp) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else if (app.kosh.ui.theme.LocalDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = if (isOp) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(16.dp))
                            .semantics { contentDescription = label }
                            .combinedClickable(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onKey(key)
                                },
                                onLongClick = if (key == '⌫') ({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClear() }) else null,
                            ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (key == '⌫') {
                                Icon(Icons.AutoMirrored.Rounded.Backspace, null)
                            } else {
                                Text(key.toString(), fontSize = 24.sp, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
