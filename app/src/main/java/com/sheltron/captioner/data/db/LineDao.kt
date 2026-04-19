package com.sheltron.captioner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LineDao {
    @Insert
    suspend fun insert(line: Line): Long

    @Insert
    suspend fun insertAll(lines: List<Line>): List<Long>

    @Query("DELETE FROM `lines` WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Transaction
    suspend fun replaceForSession(sessionId: Long, newLines: List<Line>) {
        deleteForSession(sessionId)
        if (newLines.isNotEmpty()) insertAll(newLines)
    }

    @Query("SELECT * FROM `lines` WHERE sessionId = :sessionId ORDER BY offsetMs ASC")
    fun forSession(sessionId: Long): Flow<List<Line>>

    @Query("SELECT * FROM `lines` WHERE sessionId = :sessionId ORDER BY offsetMs ASC")
    suspend fun forSessionOnce(sessionId: Long): List<Line>

    @Query("SELECT COUNT(*) FROM `lines` WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int
}
