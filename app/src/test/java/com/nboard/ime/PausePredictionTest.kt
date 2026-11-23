package com.nboard.ime

import com.nboard.ime.prediction.PredictionCandidate
import com.nboard.ime.prediction.PredictionRequest
import com.nboard.ime.prediction.isPauseRefinementEligible
import com.nboard.ime.prediction.refineVisiblePredictions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PausePredictionTest {
    private val visible = listOf("first", "second", "third")

    @Test fun onlyStrongNewCandidateCanReplaceTheLastSlot() {
        val scores = listOf(PredictionCandidate("third", 0.0), PredictionCandidate("new", 1.0),
            PredictionCandidate("second", 3.0))
        assertEquals(listOf("first", "second", "new"), refineVisiblePredictions(visible, scores, emptyMap()))
        assertEquals(visible, refineVisiblePredictions(visible, scores, mapOf("third" to 4.0)))
        assertEquals(visible, refineVisiblePredictions(visible,
            listOf(PredictionCandidate("third", 0.0), PredictionCandidate("new", .2)), emptyMap()))
    }

    @Test fun requiresEnglishWordBoundaryWithSentenceContext() {
        val request = PredictionRequest("Let's meet ", "", "ENGLISH")
        assertTrue(isPauseRefinementEligible(request))
        assertFalse(isPauseRefinementEligible(request.copy(prefix = "t")))
        assertFalse(isPauseRefinementEligible(request.copy(language = "FRENCH")))
        assertFalse(isPauseRefinementEligible(request.copy(context = "First sentence. Hello ")))
    }
}
