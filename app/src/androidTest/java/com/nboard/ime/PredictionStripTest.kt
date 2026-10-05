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
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PredictionStripTest {
    @get:Rule val compose = createComposeRule()

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
