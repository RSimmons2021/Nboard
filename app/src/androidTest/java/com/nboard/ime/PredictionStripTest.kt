package com.nboard.ime

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PredictionStripTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tapsWhileUpdatingReachTheServiceWhichDecides() {
        // The strip forwards every tap; NboardImeService rejects words that no longer fit
        // (see TypingRolloverDeviceTest.editingNearPunctuationAndInsideWords).
        val words = mutableStateOf(listOf("hello"))
        val accepting = mutableStateOf(true)
        var accepted = ""
        compose.setContent { PredictionStrip(words.value, true, Color.White, true, accepting.value) { accepted = it } }
        compose.runOnIdle { accepting.value = false }
        compose.onNodeWithContentDescription("Updating suggestion hello").performTouchInput { click() }
        compose.runOnIdle { assertEquals("hello", accepted); accepted = ""; words.value = listOf("there"); accepting.value = true }
        compose.onNodeWithContentDescription("Insert suggestion there").performClick()
        compose.runOnIdle { assertEquals("there", accepted) }
    }

    @Test fun interruptedUnexpectedWordsSettleWithoutGhostLetters() {
        val words = mutableStateOf(listOf("international", "interesting", "internet"))
        val animate = mutableStateOf(true)
        compose.setContent {
            Box(Modifier.size(300.dp, 48.dp).background(Color.Black).testTag("strip")) {
                PredictionStrip(words.value, animate.value, Color.White, true) {}
            }
        }
        compose.mainClock.autoAdvance = false
        for (fragment in listOf("i", "iz", "izq", "izqx", "izq", "iz", "i", "izqx")) {
            // An unexpected word shows only the typed fragment (see publishPredictions).
            compose.runOnIdle { words.value = listOf(fragment) }
            compose.mainClock.advanceTimeBy(32)
        }
        compose.mainClock.advanceTimeBy(600)
        val animated = compose.onNodeWithTag("strip").captureToImage().toPixelMap()
        compose.runOnIdle { animate.value = false }
        compose.mainClock.advanceTimeBy(32)
        val static = compose.onNodeWithTag("strip").captureToImage().toPixelMap()
        var error = 0f
        for (x in 0 until static.width) for (y in 0 until static.height) {
            error += kotlin.math.abs(animated[x, y].red - static[x, y].red)
        }
        assertTrue("Interrupted glyphs did not settle cleanly", error / (static.width * static.height) < 0.001f)
        compose.onNodeWithContentDescription("Insert suggestion izqx").assertIsDisplayed()
    }

    @Test fun rapidChangesAlwaysInsertLatestSuggestionDuringAnimation() {
        val words = mutableStateOf(listOf("hello", "help", "hey"))
        var accepted = ""
        compose.setContent { PredictionStrip(words.value, true, Color.White, true) { accepted = it } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { words.value = listOf("helium", "hello", "help") }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { words.value = listOf("helicopter", "helium", "hello") }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithContentDescription("Insert suggestion helicopter").performClick()
        compose.runOnIdle { assertEquals("helicopter", accepted) }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithContentDescription("Insert suggestion helicopter").assertIsDisplayed()
    }

    @Test fun disabledMotionUpdatesImmediatelyAndEmptySlotsDoNotAcceptWords() {
        val words = mutableStateOf(listOf("one"))
        var accepted = ""
        compose.setContent { PredictionStrip(words.value, false, Color.White, false) { accepted = it } }
        compose.runOnIdle { words.value = listOf("two") }
        compose.onNodeWithContentDescription("Insert suggestion two").performClick()
        compose.runOnIdle { assertEquals("two", accepted) }
        compose.runOnIdle { words.value = emptyList() }
        compose.onNodeWithContentDescription("Insert suggestion two").assertDoesNotExist()
    }

    @Test fun wordChangingRankNeverPassesThroughTheMiddleSlot() {
        val words = mutableStateOf(listOf("", "motion", ""))
        compose.setContent {
            Box(Modifier.size(300.dp, 48.dp).background(Color.Black).testTag("strip")) {
                PredictionStrip(words.value, true, Color.White, true) {}
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { words.value = listOf("", "", "motion") }
        compose.mainClock.advanceTimeBy(64)
        val pixels = compose.onNodeWithTag("strip").captureToImage().toPixelMap()
        // Exclude the stationary dividers; no letter may sweep through this slot.
        for (x in pixels.width / 3 + 8 until pixels.width * 2 / 3 - 8) {
            for (y in 0 until pixels.height) {
                val color = pixels[x, y]
                assertTrue("Unexpected moving text at ($x, $y)", color.red < 0.01f && color.green < 0.01f && color.blue < 0.01f)
            }
        }
    }
}
