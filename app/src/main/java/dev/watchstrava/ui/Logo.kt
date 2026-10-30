package dev.watchstrava.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import dev.watchstrava.R

/** Heartbeat line inside an accent ring: the app mark. Coordinates are 0..1 of the inner box. */
private val PULSE = listOf(
    0.00f to 0.55f, 0.28f to 0.55f, 0.36f to 0.40f, 0.44f to 0.55f,
    0.50f to 0.55f, 0.58f to 0.12f, 0.67f to 0.88f, 0.74f to 0.55f, 1.00f to 0.55f,
)

@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    val accent = Accent
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.07f
        val r = this.size.minDimension / 2 - stroke / 2
        drawCircle(
            brush = Brush.sweepGradient(listOf(accent, accent.copy(alpha = 0.25f), accent)),
            radius = r,
            style = Stroke(stroke),
        )
        drawCircle(accent.copy(alpha = 0.12f), radius = r - stroke / 2)

        val box = r * 1.3f
        val left = center.x - box / 2
        val top = center.y - box / 2
        val path = Path()
        PULSE.forEachIndexed { i, (x, y) ->
            val p = Offset(left + x * box, top + y * box)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, Color.White, style = Stroke(stroke * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Menu header: mark, wordmark and a greeting. */
@Composable
fun LogoHeader(name: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LogoMark(46.dp)
        Spacer(Modifier.height(4.dp))
        Text("CORSA BT", fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        if (name != null) Text(stringResource(R.string.greeting, name), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
