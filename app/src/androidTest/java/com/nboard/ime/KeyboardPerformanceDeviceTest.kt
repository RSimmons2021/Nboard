package com.nboard.ime

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPerformanceDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() } }
    @Test fun measureImeFromAnIndependentEditorProcess() {
        val previous=shell("settings get secure default_input_method")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            instrumentation.context.startActivity(Intent().setComponent(ComponentName("com.nboard.ime.test",KeyboardBenchmarkActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline=SystemClock.uptimeMillis()+15_000
            while (SystemClock.uptimeMillis()<deadline && NboardImeService.debugInstance?.get()?.currentInputEditorInfo?.packageName!="com.nboard.ime.test") SystemClock.sleep(100)
            val service=NboardImeService.debugInstance!!.get()!!
            assertTrue(service.currentInputEditorInfo.packageName=="com.nboard.ime.test")
            SystemClock.sleep(1500)
            val words=service.learnedWordFrequency.toMap()
            val bigrams=service.learnedBigramFrequency.toMap()
            val trigrams=service.learnedTrigramFrequency.toMap()
            val recency=service.learnedWordLastUsed.toMap()
            val frames=mutableListOf<Double>()
            val calls=mutableListOf<Double>()
            val slowKeys=mutableListOf<String>()
            val listener=Window.OnFrameMetricsAvailableListener { _,metrics,_ -> frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)/1_000_000.0 }
            instrumentation.runOnMainSync { service.latestClipboardDismissed=true; service.refreshUi(); service.window!!.window!!.addOnFrameMetricsAvailableListener(listener,Handler(Looper.getMainLooper())) }
            try {
                // Warm startup separately from steady typing.
                for (letter in "hello ") { instrumentation.runOnMainSync { service.commitKeyText(letter.toString()) }; SystemClock.sleep(100) }
                SystemClock.sleep(1000)
                instrumentation.runOnMainSync { frames.clear() }
                for ((index,letter) in "thank you very much see you tomorrow can you send me the address ".withIndex()) {
                    instrumentation.runOnMainSync {
                        val start=System.nanoTime(); service.commitKeyText(letter.toString())
                        val elapsed=(System.nanoTime()-start)/1_000_000.0
                        calls += elapsed
                        if (elapsed>30) slowKeys += "$index:${if (letter==' ') "space" else "letter"}:$elapsed"
                    }
                    SystemClock.sleep(100)
                }
                SystemClock.sleep(400)
                instrumentation.runOnMainSync {
                    frames.sort(); calls.sort(); assertTrue(frames.isNotEmpty())
                    val report="external_editor_frames=${frames.size} frame_p50_ms=${frames[frames.size/2]} frame_p95_ms=${frames[(frames.size*.95).toInt().coerceAtMost(frames.lastIndex)]} commit_p50_ms=${calls[calls.size/2]} commit_p95_ms=${calls[(calls.size*.95).toInt().coerceAtMost(calls.lastIndex)]}"
                    android.util.Log.i("NboardBenchmark",report)
                    java.io.File(instrumentation.targetContext.filesDir,"keyboard-external-frame-benchmark.txt").writeText("$report\nslow_keys_ms=${slowKeys.joinToString()}\n")
                }
            } finally {
                instrumentation.runOnMainSync {
                    service.window!!.window!!.removeOnFrameMetricsAvailableListener(listener)
                    service.learnedWordFrequency.clear(); service.learnedWordFrequency.putAll(words)
                    service.learnedBigramFrequency.clear(); service.learnedBigramFrequency.putAll(bigrams)
                    service.learnedTrigramFrequency.clear(); service.learnedTrigramFrequency.putAll(trigrams)
                    service.learnedWordLastUsed.clear(); service.learnedWordLastUsed.putAll(recency)
                    service.savePredictionLearning(force=true)
                }
            }
        } finally {
            instrumentation.context.startActivity(Intent().setComponent(ComponentName("com.nboard.ime.test",KeyboardBenchmarkActivity::class.java.name))
                .putExtra("finish",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            if (previous.isNotBlank() && previous!="null") shell("ime set $previous")
        }
    }
}
