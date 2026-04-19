package com.sheltron.captioner.api

import android.content.Context
import com.sheltron.captioner.data.db.Line
import com.sheltron.captioner.data.db.Task
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Runs Gemini Nano on-device to pull tasks out of a session's transcript.
 * Falls back to a simple heuristic extractor if Nano is unavailable, so the
 * button never silently fails on unsupported devices.
 */
object TaskExtractor {

    sealed class Result {
        data class Ok(val tasks: List<Task>) : Result()
        data class Failed(val message: String) : Result()
    }

    // Concise prompt — Nano is a small model, keep it focused.
    private const val INSTRUCTIONS = """Extract actionable tasks from the dictation below. Return ONLY a JSON array. No prose, no markdown.
Each item: {"title": "...", "context": "...", "due": "YYYY-MM-DD" or null, "priority": "low"|"medium"|"high"}.
- "title": short imperative, under 80 chars.
- "context": the phrase from the transcript that suggested this task.
- "due": an ISO date if one was stated (use today's date to resolve words like "Friday"), else null.
- "priority": medium unless the speaker flagged urgency.
Skip questions, guesses, idle chatter. If nothing is actionable, return [].

Today: %TODAY%
Transcript:
%TRANSCRIPT%
"""

    suspend fun extract(
        context: Context,
        lines: List<Line>,
        sessionId: Long,
        now: Long = System.currentTimeMillis(),
        onPhase: (String) -> Unit = {}
    ): Result {
        if (lines.isEmpty()) return Result.Ok(emptyList())

        onPhase("Preparing prompt")
        val transcript = lines.joinToString("\n") { "[${formatOffset(it.offsetMs)}] ${it.text}" }
        val prompt = INSTRUCTIONS
            .replace("%TODAY%", isoDate(now))
            .replace("%TRANSCRIPT%", transcript.take(6000))

        return when (val r = GemmaClient.generate(context, prompt, onPhase = onPhase)) {
            is GemmaClient.Result.Ok -> {
                onPhase("Parsing response")
                parseTasks(r.text, lines, sessionId, now)
            }
            is GemmaClient.Result.Failed -> {
                onPhase("Running fallback extractor")
                val heuristic = HeuristicExtractor.extract(lines, sessionId, now)
                if (heuristic.isNotEmpty()) Result.Ok(heuristic)
                else Result.Failed(r.message)
            }
        }
    }

    private fun parseTasks(raw: String, lines: List<Line>, sessionId: Long, now: Long): Result {
        val json = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = try {
            JSONArray(json)
        } catch (t: Throwable) {
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
            val ctx = obj.optString("context").trim().ifBlank {
                obj.optString("context_snippet").trim()
            }
            val due = parseIsoDate(
                obj.optString("due").takeIf { it.isNotBlank() && it != "null" }
                    ?: obj.optString("due_date").takeIf { it.isNotBlank() && it != "null" }
            )
            val priority = when (obj.optString("priority").lowercase()) {
                "high" -> "high"
                "low" -> "low"
                else -> "medium"
            }
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

/**
 * Last-resort extractor: pulls obvious imperative / reminder patterns from the transcript.
 * Crude, but better than a blank "Nano unavailable" error on unsupported devices.
 */
private object HeuristicExtractor {
    private val patterns = listOf(
        Regex("""(?i)\b(?:remind me to|don['’]t forget to|i need to|we need to|have to|must|todo:?)\s+([^.!?\n]+)"""),
        Regex("""(?i)\bfollow up (?:with|on)\s+([^.!?\n]+)"""),
        Regex("""(?i)\bmake sure (?:to\s+)?([^.!?\n]+)""")
    )

    fun extract(lines: List<Line>, sessionId: Long, now: Long): List<Task> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Task>()
        for (line in lines) {
            for (p in patterns) {
                for (m in p.findAll(line.text)) {
                    val raw = m.groupValues.getOrNull(1)?.trim() ?: continue
                    val title = raw.take(80).trimEnd(',', ' ', '.', ';')
                    if (title.length < 3) continue
                    if (!seen.add(title.lowercase())) continue
                    out.add(
                        Task(
                            title = title,
                            contextSnippet = line.text.take(500),
                            priority = "medium",
                            sourceSessionId = sessionId,
                            sourceLineOffsetMs = line.offsetMs,
                            createdAt = now
                        )
                    )
                }
            }
        }
        return out
    }
}
