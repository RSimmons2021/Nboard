package com.nboard.ime

import com.nboard.ime.prediction.PredictionCandidate
import com.nboard.ime.prediction.PredictionRanker
import com.nboard.ime.prediction.PredictionRequest
import org.junit.Assert.*
import org.junit.Test

class PredictionRankerTest {
    @Test fun typoCompletionsIncludeAdjacentSwapsAndMissingLetters() {
        assertEquals(1, PredictionRanker.prefixDistance("teh", "the"))
        assertEquals(1, PredictionRanker.prefixDistance("helo", "hello"))
        assertEquals(1, PredictionRanker.prefixDistance("adres", "address"))
        assertEquals(0, PredictionRanker.prefixDistance("hel", "hello"))
        assertEquals(2, PredictionRanker.prefixDistance("xyz", "hello"))
        assertEquals(2, PredictionRanker.prefixDistance("h", "world"))
    }
    @Test fun dictionaryFrequencyAndContextCompeteInOneRanking() {
        val request = PredictionRequest("thank you ", "", "ENGLISH")
        val common = PredictionRanker.score("the", 1000000, 0, request)
        val contextual = PredictionRanker.score("very", 10000, 200000, request)
        assertTrue(contextual.score > common.score)
    }
    @Test fun recencyAndExplicitAcceptanceHelpPersonalWords() {
        val request = PredictionRequest("meet at ", "lu", "ENGLISH", words = mapOf("lumen" to 8),
            lastUsed = mapOf("lumen" to 1_000_000L), now = 1_000_000L)
        val personal = PredictionRanker.score("lumen", 1, 0, request)
        val generic = PredictionRanker.score("lunch", 1000, 0, request)
        assertTrue(personal.score > generic.score)
        assertTrue(personal.score > PredictionRanker.score("lumen", 1, 0, request.copy(now = request.now+365L*86_400_000)).score)
    }
    @Test fun oftenTypedWordsDoNotCrowdOutContextAtTheNextWord() {
        // "the" typed 300 times must not outrank a word that fits "thank you ___".
        val heavyUser = PredictionRequest("thank you ", "", "ENGLISH", words = mapOf("the" to 300),
            lastUsed = mapOf("the" to 1_000_000L), now = 1_000_000L)
        val often = PredictionRanker.score("the", 1000000, 0, heavyUser)
        val fitting = PredictionRanker.score("very", 10000, 200000, heavyUser)
        assertTrue(fitting.score > often.score)
        // A learned pair is personal context and still promotes the user's continuation.
        val habit = heavyUser.copy(bigrams = mapOf("you|so" to 20))
        assertTrue(PredictionRanker.score("so", 10000, 0, habit).score > PredictionRanker.score("so", 10000, 0, heavyUser).score)
    }
    @Test fun rejectionStopsPromotingAnUnwantedCorrection() {
        val request = PredictionRequest("", "teh", "ENGLISH")
        val before = PredictionRanker.score("the", 1000000, 0, request)
        val after = PredictionRanker.score("the", 1000000, 0, request.copy(rejected = mapOf("teh->the" to 3)))
        assertTrue(before.score-after.score >= 10.0)
    }
    @Test fun nearTiesStayInPlaceButBetterWordsMove() {
        val candidates = listOf(PredictionCandidate("home", 5.0), PredictionCandidate("back", 5.1))
        assertEquals(listOf("home", "back"), PredictionRanker.stableTop(candidates, listOf("home", "back")))
        assertEquals("back", PredictionRanker.stableTop(candidates.map { if (it.word=="back") it.copy(score=7.0) else it }, listOf("home", "back")).first())
        assertEquals(listOf("back", "home"), PredictionRanker.stableTop(candidates, listOf("invalid")))
    }
}
