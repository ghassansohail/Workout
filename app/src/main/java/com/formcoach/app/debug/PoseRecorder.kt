package com.formcoach.app.debug

import android.content.Context
import android.util.Log
import com.formcoach.app.coach.Exercise
import com.formcoach.app.coach.PoseFrame
import com.formcoach.app.coach.PoseFrameCsv
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Debug-only landmark recorder. Writes landmarks as CSV (never images) to
 * getExternalFilesDir("recordings"). [record] is called from the camera thread and only
 * queues a line; all file I/O happens on one background thread.
 */
class PoseRecorder(private val context: Context) {
    @Volatile
    var isRecording: Boolean = false
        private set

    // Guarded by the monitor of this object (start/stop). The writer fields are touched only on the executor thread.
    private var executor: ExecutorService? = null
    private var currentFile: File? = null
    private var writer: BufferedWriter? = null

    /** Starts a new file for [exercise]. If already recording, stops the previous file first. */
    @Synchronized
    fun start(exercise: Exercise) {
        if (isRecording) stop()
        val dir = context.getExternalFilesDir("recordings")
        if (dir == null) {
            Log.e(TAG, "External files dir unavailable; not recording")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "${exercise.name.lowercase(Locale.US)}_$stamp.csv")
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "PoseRecorder") }
        try {
            dir.mkdirs()
            val w = BufferedWriter(FileWriter(file))
            w.write(PoseFrameCsv.header())
            w.newLine()
            writer = w
            executor = exec
            currentFile = file
            isRecording = true
            Log.i(TAG, "Recording to ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Could not open ${file.absolutePath}", e)
            exec.shutdown()
        }
    }

    /** Queues one frame. No-op when not recording. Never blocks the caller. */
    fun record(frame: PoseFrame) {
        if (!isRecording) return
        val exec = executor ?: return
        val line = PoseFrameCsv.encode(frame)
        try {
            exec.execute { writeLine(line) }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Recorder already closed; dropping frame")
        }
    }

    /** Flushes and closes the file on the writer thread. Returns the file, or null if not recording. */
    @Synchronized
    fun stop(): File? {
        // Keyed on the executor, not isRecording: a write error clears isRecording but must still release the thread.
        val exec = executor ?: return null
        isRecording = false
        val file = currentFile
        executor = null
        currentFile = null
        val closed = exec.submit { closeWriter() }
        exec.shutdown()
        try {
            closed.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error while closing recording", e)
        }
        return file
    }

    // Runs on the writer thread only.
    private fun writeLine(line: String) {
        val w = writer ?: return
        try {
            w.write(line)
            w.newLine()
        } catch (e: IOException) {
            Log.e(TAG, "Write failed; stopping recording", e)
            isRecording = false
            closeWriter()
        }
    }

    // Runs on the writer thread only.
    private fun closeWriter() {
        val w = writer ?: return
        writer = null
        try {
            w.flush()
            w.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing recording", e)
        }
    }

    private companion object {
        const val TAG = "PoseRecorder"
    }
}
