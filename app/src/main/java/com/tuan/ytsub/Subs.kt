package com.tuan.ytsub

import android.text.Html
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Một câu phụ đề: thời gian tính bằng mili-giây. */
data class Cue(val start: Long, var end: Long, val text: String)

/** Đọc json3 / vtt / srt của YouTube, ghi SRT, ghép song ngữ. */
object Subs {
    private val TAG = Regex("<[^>]+>")
    private val INLINE_TS = Regex("<\\d{1,2}:\\d{2}[:.]")
    private val SPACES = Regex("\\s+")

    private fun unescape(s: String): String =
        if (s.contains('&')) Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString() else s

    private fun ms(ts: String): Long {
        var parts = ts.trim().replace(',', '.').split(":")
        if (parts.size == 2) parts = listOf("0") + parts
        val (h, m, s) = parts
        return h.toLong() * 3_600_000 + m.toLong() * 60_000 + (s.toDouble() * 1000).roundToLong()
    }

    private fun fmt(v: Long): String {
        var x = max(0L, v)
        val h = x / 3_600_000; x %= 3_600_000
        val m = x / 60_000; x %= 60_000
        val s = x / 1000; val r = x % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, r)
    }

    private fun clean(line: String): String =
        unescape(line.replace(TAG, "")).replace(SPACES, " ").trim()

    fun normalize(input: List<Cue>): List<Cue> {
        val sorted = input.filter { it.text.isNotBlank() }.sortedBy { it.start }
        val out = ArrayList<Cue>()
        for (c0 in sorted) {
            val c = c0.copy(end = if (c0.end <= c0.start) c0.start + 2000 else c0.end)
            val last = out.lastOrNull()
            if (last != null && last.text == c.text && c.start - last.end < 500) {
                last.end = max(last.end, c.end); continue
            }
            out.add(c)
        }
        for (i in 0 until out.size - 1) {
            val a = out[i]; val b = out[i + 1]
            if (b.start < a.end && b.start > a.start) a.end = b.start
        }
        return out
    }

    fun parseText(raw: String): List<Cue> {
        val text = raw.replace("﻿", "").replace("\r\n", "\n").replace("\r", "\n")
        val rolling = text.contains("<c>") || INLINE_TS.containsMatchIn(text)

        data class Raw(val s: Long, val e: Long, val body: MutableList<String>)
        val raws = ArrayList<Raw>()
        var cur: Raw? = null
        for (line in text.split("\n")) {
            if (line.contains("-->")) {
                cur?.body?.let { body ->
                    while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.size - 1)
                    if (body.isNotEmpty() && body.last().trim().all { it.isDigit() } &&
                        (body.size == 1 || body[body.size - 2].isBlank())) body.removeAt(body.size - 1)
                }
                val left = line.substringBefore("-->")
                val right = line.substringAfter("-->").trim().split(Regex("\\s+")).firstOrNull() ?: ""
                cur = try { Raw(ms(left), ms(right), ArrayList()).also { raws.add(it) } } catch (e: Exception) { null }
                continue
            }
            cur?.body?.add(line)
        }

        val out = ArrayList<Cue>()
        var prev: List<String> = emptyList()
        for (r in raws) {
            var body = r.body.map { clean(it) }.filter { it.isNotEmpty() }
            if (rolling) {
                val fresh = body.filter { it !in prev }
                prev = body
                if (r.e - r.s <= 60 || fresh.isEmpty()) continue
                body = fresh
            }
            if (body.isNotEmpty()) out.add(Cue(r.s, r.e, body.joinToString("\n")))
        }
        return normalize(out)
    }

    fun parseJson3(text: String): List<Cue> {
        val events = JSONObject(text).optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Cue>()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val segs = ev.optJSONArray("segs") ?: continue
            val sb = StringBuilder()
            for (j in 0 until segs.length()) sb.append(segs.optJSONObject(j)?.optString("utf8", "") ?: "")
            val t = sb.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (t.isEmpty()) continue
            val start = ev.optLong("tStartMs", 0)
            val dur = ev.optLong("dDurationMs", 0).let { if (it <= 0) 2000 else it }
            out.add(Cue(start, start + dur, unescape(t)))
        }
        return normalize(out)
    }

    fun load(f: File): List<Cue> {
        val text = f.readText()
        return if (f.extension.equals("json3", true) || text.trimStart().startsWith("{")) parseJson3(text)
        else parseText(text)
    }

    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(fmt(c.start)).append(" --> ").append(fmt(c.end)).append('\n')
            append(c.text).append("\n\n")
        }
    }

    private fun oneLine(t: String) = t.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** Dòng trên = primary (EN), dòng dưới = secondary (VI). color != null → tô màu dòng dưới. */
    fun mergeBilingual(primary: List<Cue>, secondary: List<Cue>, color: String? = null): List<Cue> {
        val extras = List(primary.size) { ArrayList<String>() }
        val lonely = ArrayList<Cue>()
        var j = 0
        for (sc in secondary.sortedBy { it.start }) {
            while (j < primary.size - 1 && primary[j].end <= sc.start) j++
            var best = -1; var bestOv = 0L; var k = j
            while (k < primary.size && primary[k].start < sc.end) {
                val ov = min(primary[k].end, sc.end) - max(primary[k].start, sc.start)
                if (ov > bestOv) { best = k; bestOv = ov }
                k++
            }
            if (best < 0) lonely.add(sc) else extras[best].add(oneLine(sc.text))
        }
        fun paint(t: String) = if (color != null) "<font color=\"$color\">$t</font>" else t
        val out = ArrayList<Cue>()
        primary.forEachIndexed { i, c ->
            val top = oneLine(c.text)
            val text = if (extras[i].isEmpty()) top else top + "\n" + paint(extras[i].joinToString(" "))
            out.add(Cue(c.start, c.end, text))
        }
        lonely.forEach { out.add(Cue(it.start, it.end, paint(oneLine(it.text)))) }
        return out.sortedBy { it.start }
    }
}
