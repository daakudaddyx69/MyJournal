package com.myjournal.app.util
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
object BackupZipHelper {
    suspend fun writeBackupZip(
        context: Context,
        jsonPayload: String,
        outputStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
            // 1. Write journal_data.json
            val jsonEntry = ZipEntry("journal_data.json")
            zos.putNextEntry(jsonEntry)
            zos.write(jsonPayload.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            // 2. Write all media files (.jpg and .m4a) from filesDir/media/
            val mediaDir = File(context.filesDir, "media")
            if (mediaDir.exists() && mediaDir.isDirectory) {
                mediaDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        val entry = ZipEntry("media/${file.name}")
                        zos.putNextEntry(entry)
                        FileInputStream(file).use { fis ->
                            fis.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }
            }
        }
    }
    suspend fun readBackupZipOrJson(
        context: Context,
        inputStream: InputStream
    ): String? = withContext(Dispatchers.IO) {
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        val tempBytes = inputStream.readBytes()
        if (tempBytes.isEmpty()) return@withContext null
        // Check if file starts with '{' or '[' (raw JSON) vs 'PK' (ZIP header)
        val firstChar = tempBytes[0].toInt().toChar()
        if (firstChar == '{' || firstChar == '[') {
            return@withContext String(tempBytes, Charsets.UTF_8)
        }
        var extractedJson: String? = null
        ZipInputStream(BufferedInputStream(tempBytes.inputStream())).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    if (name == "journal_data.json" || name.endsWith(".json")) {
                        extractedJson = zis.readBytes().toString(Charsets.UTF_8)
                    } else if (name.startsWith("media/")) {
                        val fileName = File(name).name
                        val targetFile = File(mediaDir, fileName)
                        FileOutputStream(targetFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        extractedJson
    }
}