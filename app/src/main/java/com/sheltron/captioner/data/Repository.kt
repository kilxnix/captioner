package com.sheltron.captioner.data

import android.content.Context
import com.sheltron.captioner.audio.AudioPaths
import com.sheltron.captioner.data.db.Line
import com.sheltron.captioner.data.db.LineDao
import com.sheltron.captioner.data.db.Session
import com.sheltron.captioner.data.db.SessionDao
import com.sheltron.captioner.data.db.Task
import com.sheltron.captioner.data.db.TaskDao
import kotlinx.coroutines.flow.Flow

class Repository(
    private val context: Context,
    private val sessionDao: SessionDao,
    private val lineDao: LineDao,
    private val taskDao: TaskDao
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

    suspend fun replaceLines(sessionId: Long, newLines: List<Pair<Long, String>>) {
        val rows = newLines.map { (offset, text) ->
            Line(sessionId = sessionId, offsetMs = offset, text = text)
        }
        lineDao.replaceForSession(sessionId, rows)
    }

    /** Deleting a session detaches its tasks (sets sourceSessionId=null) — tasks persist. */
    suspend fun deleteSession(id: Long) {
        taskDao.detachFromSession(id)
        sessionDao.delete(id)
        try { AudioPaths.sessionAudio(context, id).delete() } catch (_: Exception) {}
    }

    suspend fun lineCount(sessionId: Long): Int = lineDao.countForSession(sessionId)

    fun audioFileFor(sessionId: Long) = AudioPaths.sessionAudio(context, sessionId)

    // Tasks
    fun allTasks(): Flow<List<Task>> = taskDao.all()
    fun tasksForSession(sessionId: Long): Flow<List<Task>> = taskDao.forSession(sessionId)
    suspend fun countTasksForSession(sessionId: Long): Int = taskDao.countForSession(sessionId)
    suspend fun insertTasks(tasks: List<Task>) { taskDao.insertAll(tasks) }
    suspend fun setTaskDone(id: Long, done: Boolean) { taskDao.setDone(id, done) }
    suspend fun deleteTask(id: Long) { taskDao.delete(id) }
}
