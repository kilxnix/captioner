package com.sheltron.captioner.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    indices = [Index("sourceSessionId"), Index("done")]
)
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val contextSnippet: String,
    val dueDate: Long? = null,            // epoch ms
    val priority: String = "medium",      // "low" | "medium" | "high"
    val done: Boolean = false,
    val sourceSessionId: Long? = null,    // nullable so session delete doesn't cascade
    val sourceLineOffsetMs: Long? = null,
    val createdAt: Long
)
