package com.nboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemSpellCheckDecisionTest {
    private val common = setOf("the", "receive", "house")
    private fun typo(vararg suggestions: String) = SpellVerdict(looksLikeTypo = true, recommended = true, suggestions = suggestions.toList())
    private val accepted = SpellVerdict(looksLikeTypo = false, recommended = false, suggestions = emptyList())

    private fun decide(typed: String, own: String?, verdict: SpellVerdict) =
        combineWithSystemSpellCheck(typed, own, verdict) { it in common }

    @Test fun wordTheCheckerAcceptsIsLeftAlone() {
        assertNull(decide("jaxon", own = "jason", verdict = accepted))
    }

    @Test fun nboardsOwnCorrectionStandsWhenTheCheckerAlsoSeesATypo() {
        assertEquals("receive", decide("recieve", own = "receive", verdict = typo("relieve", "receive")))
    }

    @Test fun checkerFillsInOnlyACommonWordOneEditAway() {
        assertEquals("the", decide("teh", own = null, verdict = typo("the", "ten")))
        assertEquals("house", decide("hous", own = null, verdict = typo("house")))
        assertNull("two edits away", decide("huose", own = null, verdict = typo("horse")))
        assertNull("not a common word", decide("tge", own = null, verdict = typo("tie")))
    }

    @Test fun capitalisedSuggestionsAreNotTaken() {
        assertNull(decide("yrist", own = null, verdict = typo("Christ", "trust")))
    }

    @Test fun adjacentSwapCountsAsOneEdit() {
        assertEquals(1, editDistanceWithSwaps("teh", "the"))
        assertEquals(1, editDistanceWithSwaps("hous", "house"))
        assertEquals(2, editDistanceWithSwaps("huose", "horse"))
    }
}
