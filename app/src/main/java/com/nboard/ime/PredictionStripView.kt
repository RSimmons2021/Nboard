package com.nboard.ime

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A Compose island in the native IME; the key grid never recomposes. */
class PredictionStripView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AbstractComposeView(context, attrs) {
    var words by mutableStateOf<List<String>>(emptyList())
    var animateWords by mutableStateOf(true)
    var foreground by mutableStateOf(Color.White)
    var useRoboto by mutableStateOf(false)
    var onAccept: (String) -> Unit = {}

    @Composable override fun Content() {
        PredictionStrip(words, animateWords, foreground, useRoboto, onAccept)
    }
}

internal fun predictionSlots(words: List<String>): List<String> = when (words.size) {
    0 -> listOf("", "", "")
    1 -> listOf("", words[0], "")
    2 -> listOf(words[1], words[0], "")
    else -> listOf(words[1], words[0], words[2])
}

@Composable
internal fun PredictionStrip(words: List<String>, animate: Boolean, foreground: Color, useRoboto: Boolean, onAccept: (String) -> Unit) {
    val slots = predictionSlots(words)
    // Slot identity stays fixed: ranking updates reshape letters locally, never
    // carry an entire word across a divider. The full 48 dp row remains tappable.
    Row(Modifier.fillMaxSize().clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
        slots.forEachIndexed { index, word -> key(index) {
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight(0.42f)
                .background(foreground.copy(alpha = 0.14f)))
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val tint by animateColorAsState(
                foreground.copy(alpha = if (pressed && word.isNotBlank()) 0.08f else 0f),
                if (animate) tween(110) else snap(), label = "suggestion-highlight")
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .semantics { contentDescription = if (word.isNotBlank()) "Insert suggestion $word" else "" }
                .clickable(interactionSource = interaction, indication = null,
                    enabled = word.isNotBlank(), role = Role.Button) { onAccept(word) }) {
                Box(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 7.dp)
                    .background(tint, RoundedCornerShape(6.dp)))
                val slotWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
                MorphingWord(word, animate, foreground, useRoboto, slotWidthPx)
            }
        } }
    }
}

private data class GlyphFrame(val glyph: PredictionGlyph, val position: State<Float>, val opacity: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>)

/** Draw each grapheme separately, retaining its identity and velocity through edits. */
@Composable
private fun MorphingWord(word: String, animate: Boolean, foreground: Color, useRoboto: Boolean, availableWidth: Float) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val fontSize = with(density) { 16.sp.toPx() }
    val inset = with(density) { 8.dp.toPx() }
    val paint = remember(context, fontSize, useRoboto) { TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSize
        // Independent glyph motion needs separate glyphs rather than fi/fl ligatures.
        fontFeatureSettings = "'liga' 0"
        typeface = if (useRoboto) Typeface.create("sans-serif", Typeface.NORMAL) else context.resources.getFont(R.font.inter_variable)
    } }
    val target = remember(word, availableWidth, paint) { TextUtils.ellipsize(word, paint, (availableWidth - inset * 2).coerceAtLeast(1f), TextUtils.TruncateAt.END).toString() }
    var glyphs by remember { mutableStateOf(predictionGraphemes(target).mapIndexed { index, (start, end) ->
        PredictionGlyph(index.toLong() + 1, target.substring(start, end), target, start, end)
    }) }
    LaunchedEffect(target, animate) {
        glyphs = if (animate) reconcilePredictionGlyphs(glyphs, target) else predictionGraphemes(target).mapIndexed { index, (start, end) ->
            PredictionGlyph(index.toLong() + 1, target.substring(start, end), target, start, end)
        }
    }
    val frames = glyphs.map { glyph -> key(glyph.id) {
        val origin = paint.getRunAdvance(glyph.source, 0, glyph.source.length, 0, glyph.source.length, false, glyph.start) - paint.measureText(glyph.source) / 2f
        val entryDistance = with(density) { 2.dp.toPx() }
        var positionTarget by remember { mutableStateOf(origin + if (glyph.entering && animate) entryDistance else 0f) }
        LaunchedEffect(origin) { positionTarget = origin }
        val position = animateFloatAsState(if (animate) positionTarget else origin, if (animate) spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = 0.05f) else snap(), label = "letter-position")
        val opacity = remember { Animatable(if (glyph.entering && animate) 0f else 1f) }
        LaunchedEffect(glyph.exiting, animate) {
            if (animate) opacity.animateTo(if (glyph.exiting) 0f else 1f, tween(110, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
            else opacity.snapTo(if (glyph.exiting) 0f else 1f)
            if (glyph.exiting) glyphs = glyphs.filterNot { it.id == glyph.id && it.exiting }
        }
        GlyphFrame(glyph, position, opacity)
    } }
    Canvas(Modifier.fillMaxSize()) {
        val metrics = paint.fontMetrics
        val baseline = (size.height - metrics.ascent - metrics.descent) / 2f
        drawIntoCanvas { canvas ->
            frames.forEach { frame ->
                val glyph = frame.glyph
                paint.color = foreground.toArgb()
                paint.alpha = (foreground.alpha * frame.opacity.value.coerceIn(0f, 1f) * 255).toInt()
                canvas.nativeCanvas.drawTextRun(glyph.source, glyph.start, glyph.end, 0, glyph.source.length,
                    size.width / 2f + frame.position.value, baseline, false, paint)
            }
        }
    }
}
