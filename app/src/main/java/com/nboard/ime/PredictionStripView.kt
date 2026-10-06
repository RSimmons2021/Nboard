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
    var motion by mutableStateOf(PredictionMotionLevel.STANDARD)
    var acceptSuggestions by mutableStateOf(true)
    /** Letters typed of the current word; suggestions show them at full strength. */
    var typed by mutableStateOf("")
    var foreground by mutableStateOf(Color.White)
    var useRoboto by mutableStateOf(false)
    var onAccept: (String) -> Unit = {}

    @Composable override fun Content() {
        PredictionStrip(words, motion.animates, foreground, useRoboto, acceptSuggestions, typed, motion, onAccept)
    }
}

internal fun predictionSlots(words: List<String>): List<String> = when (words.size) {
    0 -> listOf("", "", "")
    1 -> listOf("", words[0], "")
    2 -> listOf(words[1], words[0], "")
    else -> listOf(words[1], words[0], words[2])
}

@Composable
internal fun PredictionStrip(words: List<String>, animate: Boolean, foreground: Color, useRoboto: Boolean,
                             accepting: Boolean = true, typed: String = "",
                             motion: PredictionMotionLevel = PredictionMotionLevel.STANDARD, onAccept: (String) -> Unit) {
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
                .semantics { contentDescription = if (word.isBlank()) "" else if (accepting) "Insert suggestion $word" else "Updating suggestion $word" }
                // Taps while updating go to onAccept, which keeps words that still fit the typed text.
                .clickable(interactionSource = interaction, indication = null,
                    enabled = word.isNotBlank(), role = Role.Button) { onAccept(word) }) {
                Box(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 7.dp)
                    .background(tint, RoundedCornerShape(6.dp)))
                val slotWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
                MorphingWord(word, animate, foreground, useRoboto, slotWidthPx, typed, motion)
            }
        } }
    }
}

private data class GlyphFrame(val glyph: PredictionGlyph, val position: State<Float>, val opacity: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>)

/** Draw each grapheme separately, retaining its identity and velocity through edits. */
@Composable
private fun MorphingWord(word: String, animate: Boolean, foreground: Color, useRoboto: Boolean, availableWidth: Float,
                         typed: String = "", motion: PredictionMotionLevel = PredictionMotionLevel.STANDARD) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val fontSize = with(density) { 17.sp.toPx() }
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
    val maxTravel = with(density) { 8.dp.toPx() }
    fun positionOf(source: String, start: Int) = paint.getRunAdvance(source, 0, source.length, 0, source.length, false, start) - paint.measureText(source) / 2f
    LaunchedEffect(target, animate) {
        val shown = glyphs.filterNot { it.exiting }.joinToString("") { it.text }
        glyphs = if (animate && predictionWordsRelated(shown, target)) {
            reconcilePredictionGlyphs(glyphs, target, ::positionOf, maxTravel)
        } else {
            // An unrelated word replaces the old one outright; crossfading letters of two
            // different words in one place reads as a glitch. New ids restart each glyph.
            val firstId = (glyphs.maxOfOrNull { it.id } ?: 0L) + 1
            predictionGraphemes(target).mapIndexed { index, (start, end) ->
                PredictionGlyph(firstId + index, target.substring(start, end), target, start, end,
                    entering = animate)
            }
        }
    }
    val frames = glyphs.map { glyph -> key(glyph.id) {
        val origin = positionOf(glyph.source, glyph.start)
        val entryDistance = with(density) { motion.entryDp.dp.toPx() }
        var positionTarget by remember { mutableStateOf(origin + if (glyph.entering && animate) entryDistance else 0f) }
        LaunchedEffect(origin) { positionTarget = origin }
        val position = animateFloatAsState(if (animate) positionTarget else origin, if (animate) spring(dampingRatio = 1f, stiffness = motion.stiffness.coerceAtLeast(1f), visibilityThreshold = 0.05f) else snap(), label = "letter-position")
        val opacity = remember { Animatable(if (glyph.entering && animate) 0f else 1f) }
        LaunchedEffect(glyph.exiting, animate) {
            if (animate) opacity.animateTo(if (glyph.exiting) 0f else 1f, tween(if (glyph.exiting) motion.fadeOutMs else motion.fadeInMs, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
            else opacity.snapTo(if (glyph.exiting) 0f else 1f)
            if (glyph.exiting) glyphs = glyphs.filterNot { it.id == glyph.id && it.exiting }
        }
        GlyphFrame(glyph, position, opacity)
    } }
    // Letters that match what was typed are confirmed; the rest of the suggestion is dimmer.
    val confirmed = typedPrefixLength(target, typed)
    Canvas(Modifier.fillMaxSize()) {
        val metrics = paint.fontMetrics
        val baseline = (size.height - metrics.ascent - metrics.descent) / 2f
        drawIntoCanvas { canvas ->
            frames.forEach { frame ->
                val glyph = frame.glyph
                paint.color = foreground.toArgb()
                val emphasis = if (confirmed == 0 || glyph.end <= confirmed) 1f else COMPLETION_ALPHA
                paint.alpha = (foreground.alpha * frame.opacity.value.coerceIn(0f, 1f) * emphasis * 255).toInt()
                canvas.nativeCanvas.drawTextRun(glyph.source, glyph.start, glyph.end, 0, glyph.source.length,
                    size.width / 2f + frame.position.value, baseline, false, paint)
            }
        }
    }
}

private const val COMPLETION_ALPHA = .55f

/** Characters of [word] that repeat what was typed, ignoring case and accents ("Hel" in "hello" -> 3). */
internal fun typedPrefixLength(word: String, typed: String): Int {
    if (typed.isEmpty()) return 0
    val folded = com.nboard.ime.prediction.PredictionRanker.fold(word)
    val typedFolded = com.nboard.ime.prediction.PredictionRanker.fold(typed)
    // Folding keeps lengths for precomposed letters; fall back to no emphasis otherwise.
    if (folded.length != word.length) return 0
    var count = 0
    while (count < minOf(folded.length, typedFolded.length) && folded[count] == typedFolded[count]) count++
    return count // partial for corrections ("teh" -> "the": only "t" is confirmed)
}
