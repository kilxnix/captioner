package com.sheltron.captioner.data

import android.content.Context
import com.sheltron.captioner.audio.AudioPaths
import com.sheltron.captioner.data.db.Line
import com.sheltron.captioner.data.db.LineDao
import com.sheltron.captioner.data.db.Session
import com.sheltron.captioner.data.db.SessionDao
import kotlinx.coroutines.flow.Flow

class Repository(
    private val context: Context,
    private val sessionDao: SessionDao,
    private val lineDao: LineDao
) {
    fun allSessions(): Flow<List<Session>> = sessionDao.all()

    fun sessionFlow(id: Long): Flow<Session?> = sessionDao.byIdFlow(id)

    suspend fun sessionById(id: Long): Session? = sessionDao.byId(id)

    fun linesFor(sessionId: Long): Flow<List<Line>> = lineDao.forSession(sessionId)

    suspend fun linesForOnce(sessionId: Long): List<Line> = lineDao.forSessionOnce(sessionId)

    suspend fun startSession(startedAt: Long, title: String): Long {
        return sessionDao.insert(Session(startedAt = startedAt, endedAt = null, title = title))
    }

    suspend fun endSession(id: Long, endedAt: Long) {
        sessionDao.markEnded(id, endedAt)
    }

    suspend fun addLine(sessionId: Long, offsetMs: Long, text: String) {
        lineDao.insert(Line(sessionId = sessionId, offsetMs = offsetMs, text = text))
    }

    suspend fun deleteSession(id: Long) {
        sessionDao.delete(id)
        try { AudioPaths.sessionAudio(context, id).delete() } catch (_: Exception) {}
    }

    suspend fun lineCount(sessionId: Long): Int = lineDao.countForSession(sessionId)

    fun audioFileFor(sessionId: Long) = AudioPaths.sessionAudio(context, sessionId)
}
