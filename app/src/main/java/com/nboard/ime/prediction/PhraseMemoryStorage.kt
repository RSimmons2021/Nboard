package com.nboard.ime.prediction

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors

/**
 * One ordered writer survives IME destruction; serialization never runs on a keypress.
 * The phrase table can hold tens of thousands of entries, so it lives in its own file in
 * no-backup storage (written atomically) rather than SharedPreferences, which would keep the
 * whole encoded table in memory for the life of the process.
 */
internal object PhraseMemoryStorage {
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "nboard-phrase-storage") }
    /** Earlier versions stored the table in these preferences; read once, then removed. */
    private const val LEGACY_PREFS = "prediction_phrase_memory"
    private const val FILE_NAME = "phrase-memory.json"

    private fun file(context: Context) = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))

    private fun read(context: Context): List<PhraseMemory.Entry> {
        val file = file(context)
        if (file.baseFile.exists()) {
            return try { PhraseMemory.decode(String(file.readFully(), Charsets.UTF_8)) } catch (_: Exception) { emptyList() }
        }
        val legacy = context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        return PhraseMemory.decode(legacy.getString("entries", "[]") ?: "[]")
    }

    /** Blocking load, ordered after queued writes (tests and the settings screen). */
    fun load(context: Context): List<PhraseMemory.Entry> = writer.submit<List<PhraseMemory.Entry>> { read(context) }.get()

    /** Decodes on the storage thread and hands the entries to [onLoaded] there. */
    fun loadAsync(context: Context, onLoaded: (List<PhraseMemory.Entry>) -> Unit) {
        val app = context.applicationContext
        writer.execute { onLoaded(read(app)) }
    }

    fun save(context: Context, entries: List<PhraseMemory.Entry>) {
        val app = context.applicationContext
        writer.execute {
            val file = file(app)
            val out = file.startWrite()
            try {
                out.write(PhraseMemory.encode(entries).toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                return@execute
            }
            val legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            if (legacy.contains("entries")) legacy.edit().remove("entries").commit()
        }
    }

    fun clear(context: Context) = save(context, emptyList())

    /** Runs after every queued write, so learned-history saves and clears stay ordered. */
    fun runOrdered(task: () -> Unit) = writer.execute(task)
}
