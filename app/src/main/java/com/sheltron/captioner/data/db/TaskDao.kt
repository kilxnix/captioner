package com.sheltron.captioner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: Task): Long

    @Insert
    suspend fun insertAll(tasks: List<Task>): List<Long>

    @Update
    suspend fun update(task: Task)

    @Query("SELECT * FROM tasks ORDER BY done ASC, COALESCE(dueDate, 9223372036854775807) ASC, createdAt DESC")
    fun all(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE sourceSessionId = :sessionId ORDER BY createdAt ASC")
    fun forSession(sessionId: Long): Flow<List<Task>>

    @Query("SELECT COUNT(*) FROM tasks WHERE sourceSessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    @Query("UPDATE tasks SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    @Query("UPDATE tasks SET sourceSessionId = NULL, sourceLineOffsetMs = NULL WHERE sourceSessionId = :sessionId")
    suspend fun detachFromSession(sessionId: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)
}
