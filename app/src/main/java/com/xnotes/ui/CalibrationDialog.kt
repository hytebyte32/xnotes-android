package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.measure.ScreenCalibration
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Calibrate the screen by matching an object of known length: a bar is dragged to the object's
 * length and the ratio is how many pixels make a real centimetre on this glass.
 *
 * It starts at the density the device reports, so for most screens the bar is already nearly right
 * and the drag only trims it.
 */
@Composable
fun CalibrationDialog(
    initialPxPerCm: Double,
    onSave: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val references = ScreenCalibration.REFERENCES
    var refIndex by remember { mutableStateOf(0) }
    var customText by remember { mutableStateOf("10.0") }
    val realCm = if (refIndex < references.size) references[refIndex].second else customText.toDoubleOrNull() ?: 0.0
    var barPx by remember { mutableStateOf(0f) }
    LaunchedEffect(refIndex, realCm) {
        if (realCm > 0.0) barPx = (initialPxPerCm * realCm).toFloat()
    }
    val pxPerCm = ScreenCalibration.fromReference(barPx.toDouble(), realCm)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f),
            shape = androidx.compose.material3.MaterialTheme.shapes.medium,
            color = palette.surface.toComposeColor(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.calib_title), color = palette.text.toComposeColor(), fontSize = 18.sp)
                Text(stringResource(R.string.calib_help), color = palette.textDim.toComposeColor(), fontSize = 13.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    references.forEachIndexed { i, (name, cm) ->
                        TextButton(onClick = { refIndex = i }) {
                            Text(
                                "$name (%.1f cm)".format(cm),
                                color = if (refIndex == i) palette.accent.toComposeColor() else palette.text.toComposeColor(),
                                fontSize = 13.sp,
                            )
                        }
                    }
                    TextButton(onClick = { refIndex = references.size }) {
                        Text(
                            stringResource(R.string.calib_custom),
                            color = if (refIndex == references.size) palette.accent.toComposeColor() else palette.text.toComposeColor(),
                            fontSize = 13.sp,
                        )
                    }
                }
                if (refIndex == references.size) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        label = { Text(stringResource(R.string.calib_length_cm)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }

                // The bar: as wide as the reference should be on the glass, with a handle on its right
                // end that drags it longer or shorter. It is capped at the room the dialog has.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val maxPx = with(density) { maxWidth.toPx() } - with(density) { 44.dp.toPx() }
                    Box(modifier = Modifier.height(64.dp)) {
                        Box(
                            modifier = Modifier
                                .width(with(density) { barPx.toDp() })
                                .height(64.dp)
                                .background(Color(0x551E88E5)),
                        )
                        Box(
                            modifier = Modifier
                                .padding(start = with(density) { barPx.toDp() })
                                .width(6.dp)
                                .height(64.dp)
                                .background(Color(0xFF1E88E5)),
                        )
                        Box(
                            modifier = Modifier
                                .padding(start = with(density) { barPx.toDp() } - 18.dp)
                                .width(42.dp)
                                .height(64.dp)
                                .pointerInput(maxPx) {
                                    detectHorizontalDragGestures { _, dx ->
                                        barPx = (barPx + dx).coerceIn(50f, maxPx.coerceAtLeast(50f))
                                    }
                                },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { barPx = (barPx - 1f).coerceAtLeast(50f) }) { Text("−1 px") }
                    TextButton(onClick = { barPx = barPx + 1f }) { Text("+1 px") }
                    Text(
                        pxPerCm?.let { "%.1f px/cm  (%.0f dpi)".format(it, it * 2.54) } ?: "",
                        color = palette.textDim.toComposeColor(),
                        fontSize = 13.sp,
                    )
                }

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(enabled = pxPerCm != null, onClick = { pxPerCm?.let(onSave) }) {
                        Text(stringResource(R.string.calib_save))
                    }
                }
            }
        }
    }
}
