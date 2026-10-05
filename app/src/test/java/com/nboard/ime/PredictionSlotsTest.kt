package com.nboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class PredictionSlotsTest {
    @Test fun `best suggestion stays in middle with stable empty slots`() {
        assertEquals(listOf("", "", ""), predictionSlots(emptyList()))
        assertEquals(listOf("", "best", ""), predictionSlots(listOf("best")))
        assertEquals(listOf("second", "best", ""), predictionSlots(listOf("best", "second")))
        assertEquals(listOf("second", "best", "third"), predictionSlots(listOf("best", "second", "third")))
    }
}
