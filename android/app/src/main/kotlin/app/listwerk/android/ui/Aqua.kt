package app.listwerk.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Aqua-Design (angelehnt an Mac OS X): Nadelstreifen und Gel-Kugeln. Die Farben selbst stehen in themes/aqua.json. */
val ListwerkColors.isAqua: Boolean get() = theme.id == "aqua"

/** Hintergrund der Bildschirme: im Aqua-Design durchsichtig, damit die Nadelstreifen der Wurzel durchscheinen. */
@Composable
fun screenColor(): Color = if (MaterialTheme.listwerk.isAqua) Color.Transparent else MaterialTheme.colorScheme.background

fun Modifier.pinstripes(on: Boolean, base: Color): Modifier = if (!on) this else drawBehind {
    drawRect(base)
    val stripe = Color.White.copy(alpha = 0.6f)
    val step = 4.dp.toPx()
    val h = 2.dp.toPx()
    var y = 0f
    while (y < size.height) { drawRect(stripe, Offset(0f, y), Size(size.width, h)); y += step }
}

/** Abhak-Kreis; im Aqua-Design eine glänzende Gel-Kugel. */
@Composable
fun CheckMark(done: Boolean, tint: Color, description: String) {
    if (!MaterialTheme.listwerk.isAqua) {
        Icon(if (done) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked, description, tint = tint)
        return
    }
    val fill = if (done) listOf(lerp(tint, Color.White, 0.5f), tint, lerp(tint, Color.Black, 0.15f)) else listOf(Color.White, Color(0xFFDCDCDC))
    Box(
        Modifier.size(22.dp).semantics { contentDescription = description }.clip(CircleShape)
            .background(Brush.verticalGradient(fill))
            .border(1.dp, if (done) lerp(tint, Color.Black, 0.3f) else Color(0xFF9A9A9A), CircleShape),
    ) {
        // Glanzlicht oben wie bei den Aqua-Knöpfen.
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 2.dp).size(14.dp, 7.dp).clip(RoundedCornerShape(50))
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0f)))),
        )
        if (done) Icon(Icons.Default.Check, null, Modifier.align(Alignment.Center).size(14.dp), tint = Color.White)
    }
}
