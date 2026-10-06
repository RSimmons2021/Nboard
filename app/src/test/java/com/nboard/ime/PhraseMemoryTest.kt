package com.nboard.ime

import com.nboard.ime.prediction.PhraseMemory
import com.nboard.ime.prediction.PredictionRanker
import com.nboard.ime.prediction.PredictionRequest
import org.junit.Assert.*
import org.junit.Test

class PhraseMemoryTest {
    private val now = 1_000_000_000L
    @Test fun repeatedLongPhrasesDisambiguateIdenticalLastTwoWords() {
        val memory = PhraseMemory()
        repeat(3) {
            memory.record("please leave it at the ", "office", "ENGLISH", "chat", now)
            memory.record("please collect it at the ", "station", "ENGLISH", "chat", now)
        }
        val result = memory.suggestions("please collect it at the ", "ENGLISH", "chat", now)
        assertTrue(result.getValue("station") > (result["office"] ?: 0.0))
        assertEquals(2, memory.suggestions("please put it at the ", "ENGLISH", "chat", now).size)
        assertEquals("station", PredictionRanker.stableTop(result.map { (word, _) ->
            PredictionRanker.score(word, 1, 0, PredictionRequest("please collect it at the ", "", "ENGLISH", phrases = result))
        }, emptyList()).first())
    }
    @Test fun oneOccurrenceDoesNotPromoteAndUndoRemovesEvidence() {
        val memory = PhraseMemory()
        memory.record("meet me at ", "lumen", "ENGLISH", "chat", now)
        assertTrue(memory.suggestions("meet me at ", "ENGLISH", "chat", now).isEmpty())
        val receipt = memory.record("meet me at ", "lumen", "ENGLISH", "chat", now)!!
        assertTrue("lumen" in memory.suggestions("meet me at ", "ENGLISH", "chat", now))
        memory.retract(receipt)
        assertTrue(memory.suggestions("meet me at ", "ENGLISH", "chat", now).isEmpty())
    }
    @Test fun appSpecificEvidenceWinsTiesAndGlobalFallbackWorks() {
        val memory = PhraseMemory()
        repeat(2) {
            memory.record("send it to ", "work", "ENGLISH", "mail", now)
            memory.record("send it to ", "home", "ENGLISH", "chat", now)
        }
        val mail = memory.suggestions("send it to ", "ENGLISH", "mail", now)
        assertTrue(mail.getValue("work") > mail.getValue("home"))
        assertEquals(2, memory.suggestions("send it to ", "ENGLISH", "new.app", now).size)
        assertTrue(memory.suggestions("send it to ", "FRENCH", "mail", now).isEmpty())
    }
    @Test fun sentenceBoundariesDoNotLeakOldPhrases() {
        val memory = PhraseMemory()
        repeat(2) { memory.record("send it to ", "work", "ENGLISH", "mail", now) }
        assertTrue(memory.suggestions("send it to. ", "ENGLISH", "mail", now).isEmpty())
        assertTrue(memory.suggestions("send it to\nhello ", "ENGLISH", "mail", now).isEmpty())
        assertEquals("", PredictionRanker.signals(PredictionRequest("hello ", "", "ENGLISH")).previousTwo)
        assertEquals("", PredictionRanker.signals(PredictionRequest("hello world. ", "", "ENGLISH")).previous)
        assertEquals(listOf("i'll", "meet", "you"), PhraseMemory.sentenceTokens("I’ll meet you "))
    }
    @Test fun snapshotRoundTripPreservesRankingAndRejectsMalformedEntries() {
        val memory = PhraseMemory()
        repeat(2) { memory.record("meet me at ", "lumen", "ENGLISH", "chat", now) }
        val restored = PhraseMemory()
        restored.restore(PhraseMemory.decode(PhraseMemory.encode(memory.snapshot())), now)
        assertEquals(memory.suggestions("meet me at ", "ENGLISH", "chat", now),
            restored.suggestions("meet me at ", "ENGLISH", "chat", now))
        assertTrue(PhraseMemory.decode("invalid").isEmpty())
        restored.restore(listOf(PhraseMemory.Entry("ENGLISH", "app", "one|http://bad", "hello", 10, now)), now)
        assertTrue(restored.snapshot().isEmpty())
    }
    @Test fun capacityAndClearRemainBounded() {
        val memory = PhraseMemory(12)
        for (letter in 'a'..'z') memory.record("a b $letter ", "word", "ENGLISH", "app", now)
        assertTrue(memory.snapshot().size <= 12)
        memory.clear()
        assertTrue(memory.snapshot().isEmpty())
    }
    @Test fun temporalEvaluationUsesOnlyPastObservationsAndDecay() {
        val memory = PhraseMemory()
        val context = "meet me at "
        assertTrue(memory.suggestions(context, "ENGLISH", "app", now).isEmpty())
        memory.record(context, "lumen", "ENGLISH", "app", now)
        assertTrue(memory.suggestions(context, "ENGLISH", "app", now).isEmpty())
        memory.record(context, "lumen", "ENGLISH", "app", now)
        val recent = memory.suggestions(context, "ENGLISH", "app", now).getValue("lumen")
        val older = memory.suggestions(context, "ENGLISH", "app", now + 365L * 86_400_000).getValue("lumen")
        assertTrue(recent > older)
    }
}
