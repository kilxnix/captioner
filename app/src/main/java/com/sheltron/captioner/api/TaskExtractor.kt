package com.sheltron.captioner.api

import com.sheltron.captioner.data.db.Line
import com.sheltron.captioner.data.db.Task
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TaskExtractor {

    sealed class Result {
        data class Ok(val tasks: List<Task>) : Result()
        data class Failed(val message: String) : Result()
    }

    private const val SYSTEM = """
You extract actionable tasks from a voice-transcribed session. The transcript may contain filler, false starts, and repetition — ignore those. Return ONLY a JSON array, no prose before or after, no markdown fences.

Schema for each task:
{
  "title": string (short imperative, <= 80 chars),
  "context_snippet": string (the single transcript line or short quoted fragment that motivated this task),
  "due_date": string|null (ISO 8601 date like "2026-04-26", or null if none mentioned),
  "priority": "low" | "medium" | "high"
}

Rules:
- Only include things the speaker actually committed to or assigned. Skip idle speculation, hypothetical "we could..." statements, and questions.
- If the same task is mentioned multiple times, return one entry.
- If there are no tasks, return [].
- Use today's date as the anchor for relative dates ("Friday", "next week").
- Default priority is "medium". Use "high" only when the speaker explicitly signals urgency.
"""

    suspend fun extract(
        lines: List<Line>,
        sessionId: Long,
        apiKey: String,
        model: String,
        now: Long = System.currentTimeMillis()
    ): Result {
        if (lines.isEmpty()) return Result.Ok(emptyList())

        val transcript = lines.joinToString("\n") { "[${formatOffset(it.offsetMs)}] ${it.text}" }
        val dateStr = isoDate(now)
        val user = "Today's date is $dateStr.\n\nTranscript:\n\n$transcript"

        return when (val res = ClaudeClient.complete(apiKey, model, SYSTEM.trim(), user)) {
            is ClaudeClient.Result.Ok -> parseTasks(res.text, lines, sessionId, now)
            is ClaudeClient.Result.HttpError -> Result.Failed(apiErrorMessage(res.code, res.body))
            is ClaudeClient.Result.NetworkError -> Result.Failed("Network: ${res.message}")
        }
    }

    private fun apiErrorMessage(code: Int, body: String): String {
        // Anthropic returns {"type":"error","error":{"type":"...","message":"..."}}
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }

        return when (code) {
            401 -> "Invalid API key. Check Settings."
            429 -> "Rate limited. Try again in a moment."
            529 -> "Anthropic is overloaded. Try again."
            in 500..599 -> "Anthropic server error ($code). ${detail ?: "Try again."}"
            400 -> detail ?: "Bad request ($code)."  // low credits / invalid model / etc. land here
            else -> detail?.let { "$it ($code)" } ?: "API error $code: ${body.take(200)}"
        }
    }

    private fun parseTasks(raw: String, lines: List<Line>, sessionId: Long, now: Long): Result {
        val json = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = try {
            JSONArray(json)
        } catch (t: Throwable) {
            // Try to find the first [ ... ] in case the model wrapped it in prose.
            val start = json.indexOf('[')
            val end = json.lastIndexOf(']')
            if (start < 0 || end <= start) return Result.Failed("Model returned non-JSON")
            try { JSONArray(json.substring(start, end + 1)) }
            catch (_: Throwable) { return Result.Failed("Model returned non-JSON") }
        }

        val tasks = mutableListOf<Task>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = obj.optString("title").trim()
            if (title.isBlank()) continue
            val ctx = obj.optString("context_snippet").trim()
            val due = parseIsoDate(obj.optString("due_date").takeIf { it.isNotBlank() && it != "null" })
            val priority = when (obj.optString("priority").lowercase()) {
                "high" -> "high"
                "low" -> "low"
                else -> "medium"
            }
            // Best-effort: find the transcript line whose text overlaps the snippet, to set sourceLineOffsetMs.
            val matchOffset = lines.firstOrNull {
                ctx.isNotBlank() && (it.text.contains(ctx, ignoreCase = true) || ctx.contains(it.text, ignoreCase = true))
            }?.offsetMs

            tasks.add(
                Task(
                    title = title.take(200),
                    contextSnippet = ctx.take(500),
                    dueDate = due,
                    priority = priority,
                    sourceSessionId = sessionId,
                    sourceLineOffsetMs = matchOffset,
                    createdAt = now
                )
            )
        }
        return Result.Ok(tasks)
    }

    private fun isoDate(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        return fmt.format(Date(ms))
    }

    private fun parseIsoDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
            fmt.parse(s)?.time
        } catch (_: Throwable) { null }
    }

    private fun formatOffset(ms: Long): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return "%02d:%02d".format(m, s)
    }
}
