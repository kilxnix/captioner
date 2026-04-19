package com.sheltron.captioner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LineDao {
    @Insert
    suspend fun insert(line: Line): Long

    @Query("SELECT * FROM `lines` WHERE sessionId = :sessionId ORDER BY offsetMs ASC")
    fun forSession(sessionId: Long): Flow<List<Line>>

    @Query("SELECT * FROM `lines` WHERE sessionId = :sessionId ORDER BY offsetMs ASC")
    suspend fun forSessionOnce(sessionId: Long): List<Line>

    @Query("SELECT COUNT(*) FROM `lines` WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int
}
