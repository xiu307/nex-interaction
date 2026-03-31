package cn.shengwang.convoai.quickstart.audio

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PcmFileUtil(private val context: Context) {

    fun createPcmFile(dateTaken: Long): File {
        val title = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.getDefault())
            .format(Date(dateTaken)) + ".pcm"
        return File(pcmFolder(), title)
    }

    private fun pcmFolder(): File {
        val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "PCM")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        return folder
    }
}
