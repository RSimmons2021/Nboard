package com.nboard.ime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** Autocorrect runs on the UI thread when a word ends; it must stay well inside one frame. */
class CorrectionLatencyDeviceTest {
    @Test fun correctionStaysInsideAFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AutoCorrect(context, AutoCorrect.AutoCorrectMode.ENGLISH_ONLY, cacheCapacity = 1)
        engine.preload()
        val typos = listOf("wprk", "becuase", "tomorow", "definately", "hapy", "thw", "recieve", "goign", "probaly",
            "acommodate", "begining", "wierd", "untill", "seperate", "freind", "beleive", "tge", "ot", "jsut", "knwo",
            "xyzzyq", "lmao", "kubernetes", "abcdefghij", "pleaseee")
        repeat(3) { typos.forEach { engine.correct(it, "the") } } // JIT warm-up
        val times = typos.map { word ->
            val started = System.nanoTime()
            val result = engine.correct(word, "the")
            ((System.nanoTime() - started) / 1e6) to "$word->$result"
        }
        val sorted = times.map { it.first }.sorted()
        android.util.Log.i("CorrectionLatency", "median=${"%.2f".format(sorted[sorted.size / 2])}ms " +
            "max=${"%.2f".format(sorted.last())}ms ${times.map { it.second }}")
        assertTrue("slowest correction ${sorted.last()} ms", sorted.last() < 8.0)
    }
}
