package com.myjournal.app.util
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
class MediaHelper(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioFile: File? = null
    fun copyImageToInternalStorage(uri: Uri): String? {
        return try {
            val dir = File(context.filesDir, "media/images").apply { mkdirs() }
            val destFile = File(dir, "img_${UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    fun startAudioRecording(): File? {
        return try {
            stopAudioRecording()
            val dir = File(context.filesDir, "media/audio").apply { mkdirs() }
            val outFile = File(dir, "voice_${UUID.randomUUID()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            currentAudioFile = outFile
            outFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    fun stopAudioRecording(): String? {
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            currentAudioFile?.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            mediaRecorder?.release()
            mediaRecorder = null
            null
        }
    }
    fun playAudio(filePath: String?, onCompletion: () -> Unit): Boolean {
        stopPlayback()
        if (filePath.isNullOrBlank()) return false
        val file = File(filePath)
        if (!file.exists()) return false
        return try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopPlayback()
                    onCompletion()
                }
                start()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    fun stopPlayback() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}
