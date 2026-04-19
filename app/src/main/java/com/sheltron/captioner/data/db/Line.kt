package com.sheltron.captioner.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lines",
    foreignKeys = [ForeignKey(
        entity = Session::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class Line(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val offsetMs: Long,
    val text: String
)
