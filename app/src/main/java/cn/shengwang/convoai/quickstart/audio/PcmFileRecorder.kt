package cn.shengwang.convoai.quickstart.audio

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class PcmFileRecorder(context: Context) {

    private val fileUtil = PcmFileUtil(context)
    private var outputStream: FileOutputStream? = null
    private var currentFile: File? = null

    @Synchronized
    fun isSaving(): Boolean {
        return outputStream != null
    }

    @Synchronized
    fun start(): Result<File> {
        if (outputStream != null && currentFile != null) {
            return Result.failure(IllegalStateException("PCM capture is already running"))
        }
        return runCatching {
            val file = fileUtil.createPcmFile(System.currentTimeMillis())
            outputStream = FileOutputStream(file)
            currentFile = file
            file
        }
    }

    @Synchronized
    fun append(data: ByteArray): Result<Unit> {
        val stream = outputStream ?: return Result.success(Unit)
        return runCatching {
            stream.write(data)
        }.onFailure {
            closeStream()
        }
    }

    @Synchronized
    fun stop(): Result<File?> {
        return runCatching {
            closeStream()
            currentFile.also {
                currentFile = null
            }
        }
    }

    private fun closeStream() {
        try {
            outputStream?.close()
        } catch (_: IOException) {
        } finally {
            outputStream = null
        }
    }
}
