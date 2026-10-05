package com.nboard.ime

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.nboard.ime.ai.TextGenerationClient
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class KeyboardDetailsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText().trim() } }
    private fun find(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), predicate)?.let { return it }
        return null
    }
    private fun await(condition: () -> Boolean) {
        val deadline=SystemClock.uptimeMillis()+15_000
        while (SystemClock.uptimeMillis()<deadline) {
            var ready=false
            instrumentation.runOnMainSync { ready=condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        error("Keyboard did not reach the expected state")
    }
    private fun touch(view: View, action: Int) {
        val now=SystemClock.uptimeMillis()
        val event=MotionEvent.obtain(now,now,action,view.width/2f,view.height/2f,0)
        view.dispatchTouchEvent(event); event.recycle()
    }
    @Test fun realImePopupReuseAiApplyUndoStopAndFrameTiming() {
        val previous=shell("settings get secure default_input_method")
        shell("ime enable com.nboard.ime/.NboardImeService")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                await { NboardImeService.debugInstance?.get()?.isPredictionRowInitialized()==true }
                val service=NboardImeService.debugInstance!!.get()!!
                val firstRow=service.keyboardRoot.findViewById<android.widget.LinearLayout>(R.id.row1)
                val thirdRow=service.keyboardRoot.findViewById<android.widget.LinearLayout>(R.id.row3)
                await { service.keyboardRoot.isShown }
                var editor: EditText?=null
                scenario.onActivity { activity -> editor=find(activity.window.decorView) { it is EditText } as EditText }
                instrumentation.runOnMainSync {
                    service.latestClipboardDismissed=true
                    service.isAiMode=false
                    service.isNumbersMode=false
                    service.refreshUi()
                    val first=firstRow.getChildAt(0)
                    service.handleShiftTap(); service.renderKeyRows()
                    assertSame("Shift must reuse existing keys",first,firstRow.getChildAt(0))
                    val key=find(firstRow) { it is TextView && it.text.toString().equals("e",true) }!!
                    touch(key,MotionEvent.ACTION_DOWN)
                }
                SystemClock.sleep(80)
                instrumentation.runOnMainSync {
                    assertTrue(WindowInspector.getGlobalWindowViews().any { decor -> find(decor) { it is TextView && it.textSize>service.dp(25) && it.text.toString().equals("e",true) }!=null })
                }
                instrumentation.runOnMainSync {
                    val key=find(firstRow) { it is TextView && it.text.toString().equals("e",true) }!!
                    touch(key,MotionEvent.ACTION_CANCEL)
                    service.isNumbersMode=true; service.isSymbolsSubmenuOpen=false; service.renderKeyRows()
                    assertNotNull(find(thirdRow) { it is TextView && it.text.toString()=="?" })
                    assertNull(find(thirdRow) { it is TextView && it.text.toString()=="=" })
                    service.isNumbersMode=false; service.renderKeyRows()
                }
                val originalClient=service.textGenerationClient
                val cancelled=AtomicBoolean(false)
                val fake=object:TextGenerationClient {
                    override val isConfigured=true
                    override suspend fun generateText(prompt:String,systemInstruction:String?,outputCharLimit:Int)=Result.success("Correct grammar")
                    override suspend fun generateStreaming(prompt:String,systemInstruction:String?,outputCharLimit:Int,onPartial:(String)->Unit):Result<String> {
                        try { onPartial("Correct "); delay(300); return Result.success("Correct grammar") }
                        finally { cancelled.set(true) }
                    }
                }
                try {
                    scenario.onActivity { editor!!.setText("bad grammer"); editor!!.setSelection(0,11) }
                    await { service.editorSelectionStart==0 && service.editorSelectionEnd==11 }
                    instrumentation.runOnMainSync {
                        service.textGenerationClient=fake; service.isAiMode=true; service.refreshUi()
                        service.runQuickAiAction(QuickAiAction.FIX_GRAMMAR)
                    }
                    await { service.aiPreview?.text=="Correct " }
                    scenario.onActivity { assertEquals("bad grammer",editor!!.text.toString()) }
                    await { service.aiPreview?.complete==true }
                    instrumentation.runOnMainSync { assertTrue(service.aiPreviewApply.isEnabled); service.applyAiPreview() }
                    await { service.editorSelectionStart==15 && service.editorSelectionEnd==15 }
                    scenario.onActivity { assertEquals("Correct grammar",editor!!.text.toString()) }
                    instrumentation.runOnMainSync { service.undoAiEdit() }
                    scenario.onActivity { assertEquals("bad grammer",editor!!.text.toString()); editor!!.setSelection(0,11) }
                    await { service.editorSelectionStart==0 && service.editorSelectionEnd==11 }
                    instrumentation.runOnMainSync { service.runQuickAiAction(QuickAiAction.FIX_GRAMMAR) }
                    scenario.onActivity { editor!!.setSelection(3) }
                    await { service.aiPreview?.complete==true }
                    instrumentation.runOnMainSync { assertFalse(service.aiPreviewApply.isEnabled); service.applyAiPreview() }
                    scenario.onActivity { assertEquals("bad grammer",editor!!.text.toString()); editor!!.setSelection(0,11) }
                    await { service.editorSelectionStart==0 && service.editorSelectionEnd==11 }
                    cancelled.set(false)
                    instrumentation.runOnMainSync { service.runQuickAiAction(QuickAiAction.FIX_GRAMMAR) }
                    await { service.aiPreview?.text=="Correct " }
                    instrumentation.runOnMainSync { service.stopAiGeneration() }
                    await { cancelled.get() && !service.isGenerating && service.aiPreview==null }
                } finally { instrumentation.runOnMainSync { service.stopAiGeneration(); service.textGenerationClient=originalClient; service.isAiMode=false; service.refreshUi() } }

                // Query reduction must preserve punctuation spacing and selected-text replacement.
                val originalAutoSpace=service.autoSpaceAfterPunctuationEnabled
                instrumentation.runOnMainSync { service.autoSpaceAfterPunctuationEnabled=true }
                try {
                    scenario.onActivity { editor!!.setText("hi"); editor!!.setSelection(2) }
                    await { service.editorSelectionStart==2 && service.editorSelectionEnd==2 }
                    instrumentation.runOnMainSync { service.commitKeyText(".") }
                    scenario.onActivity { assertEquals("hi. ",editor!!.text.toString()) }
                    instrumentation.runOnMainSync { service.commitKeyText("."); service.commitKeyText("a") }
                    scenario.onActivity { assertEquals("hi..a",editor!!.text.toString()); editor!!.setText("world"); editor!!.setSelection(0,5) }
                    await { service.editorSelectionStart==0 && service.editorSelectionEnd==5 }
                    instrumentation.runOnMainSync { service.commitKeyText("h") }
                    scenario.onActivity { assertEquals("h",editor!!.text.toString()); editor!!.setText("thank"); editor!!.setSelection(5) }
                    await { service.editorSelectionStart==5 && service.editorSelectionEnd==5 }
                    instrumentation.runOnMainSync { service.commitKeyText(" ") }
                    scenario.onActivity { assertEquals("thank ",editor!!.text.toString()); editor!!.setText("cant"); editor!!.setSelection(4) }
                    await { service.editorSelectionStart==4 && service.editorSelectionEnd==4 }
                    instrumentation.runOnMainSync { service.commitKeyText(" ") }
                    scenario.onActivity { assertEquals("can't ",editor!!.text.toString()) }
                } finally { instrumentation.runOnMainSync { service.autoSpaceAfterPunctuationEnabled=originalAutoSpace } }

                val frames=mutableListOf<Double>()
                val keys=mutableListOf<Double>()
                val listener=Window.OnFrameMetricsAvailableListener { _,metrics,_ -> frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)/1_000_000.0) }
                instrumentation.runOnMainSync { service.window!!.window!!.addOnFrameMetricsAvailableListener(listener,Handler(Looper.getMainLooper())) }
                scenario.onActivity { editor!!.setText(""); editor!!.setSelection(0) }
                for (letter in "thank you very much see you tomorrow ") {
                    instrumentation.runOnMainSync {
                        val started=System.nanoTime(); service.commitKeyText(letter.toString()); keys += (System.nanoTime()-started)/1_000_000.0
                    }
                    SystemClock.sleep(85)
                }
                SystemClock.sleep(400)
                instrumentation.runOnMainSync {
                    service.window!!.window!!.removeOnFrameMetricsAvailableListener(listener)
                    frames.sort(); keys.sort()
                    assertTrue(frames.isNotEmpty())
                    val memory=android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(memory)
                    val report="ime_frames=${frames.size} frame_p50_ms=${frames[frames.size/2]} frame_p95_ms=${frames[(frames.size*.95).toInt().coerceAtMost(frames.lastIndex)]} commit_p95_ms=${keys[(keys.size*.95).toInt().coerceAtMost(keys.lastIndex)]} process_pss_kb=${memory.totalPss}"
                    android.util.Log.i("NboardBenchmark",report)
                    java.io.File(instrumentation.targetContext.filesDir,"keyboard-frame-benchmark.txt").writeText(report)
                }
            }
        } finally { if (previous.isNotBlank() && previous!="null") shell("ime set $previous") }
    }
}
