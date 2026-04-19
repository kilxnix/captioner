package com.sheltron.captioner.audio

import android.content.Context
import java.io.File

object AudioPaths {
    fun sessionAudio(context: Context, sessionId: Long): File {
        val dir = File(context.filesDir, "audio")
        dir.mkdirs()
        return File(dir, "$sessionId.m4a")
    }
}
