package com.example.chartdroid

import java.util.Locale

/** Membaca dan menulis format .chart (Clone Hero / Moonscraper). */
object ChartIO {
    val difficulties = listOf("Easy", "Medium", "Hard", "Expert")

    /** Nama instrumen di file -> nama tampilan. */
    val instruments = linkedMapOf(
        "Single" to "Guitar",
        "DoubleGuitar" to "Guitar Co-op",
        "DoubleBass" to "Bass",
        "DoubleRhythm" to "Rhythm",
        "Keyboard" to "Keys",
        "Drums" to "Drums",
        "GHLGuitar" to "GHL Guitar",
        "GHLBass" to "GHL Bass",
        "GHLRhythm" to "GHL Rhythm",
        "GHLCoop" to "GHL Co-op",
        "GHLKeys" to "GHL Keys"
    )

    fun isEditableInstrument(inst: String) = instruments.containsKey(inst)

    fun trackKey(diff: String, inst: String) = diff + inst

    /** Pisahkan "ExpertSingle" menjadi ("Expert", "Single"); null bila bukan nama track. */
    fun splitTrack(name: String): Pair<String, String>? {
        for (d in difficulties) if (name.startsWith(d) && name.length > d.length) return d to name.substring(d.length)
        return null
    }

    private val numericKeys = setOf("Offset", "Resolution", "Difficulty", "PreviewStart", "PreviewEnd", "Length", "Player2")

    private fun unquote(v: String): String {
        val t = v.trim()
        return if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t.substring(1, t.length - 1) else t
    }

    fun parse(textIn: String): Song {
        val song = Song()
        song.bpms.clear()
        song.ts.clear()
        song.meta.clear()
        song.resolution = 192

        var section: String? = null
        var inBlock = false
        var unknownLines: ArrayList<String>? = null
        val flagTicks = HashMap<String, Pair<HashSet<Int>, HashSet<Int>>>()
        class Ext(val tick: Int, val kind: Int, val fret: Int)
        val ext = HashMap<String, ArrayList<Ext>>()
        val anchors = ArrayList<Pair<Int, Long>>()

        val text = textIn.removePrefix("﻿")
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (!inBlock) {
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length - 1)
                } else if (line == "{") {
                    inBlock = true
                    unknownLines = null
                }
                continue
            }
            if (line == "}") {
                val sec = section
                val ul = unknownLines
                if (sec != null && ul != null) song.unknownSections.add(sec to ul)
                inBlock = false
                section = null
                unknownLines = null
                continue
            }
            val sec = section ?: continue
            val eq = line.indexOf('=')
            if (eq < 0) {
                if (sec != "Song" && sec != "SyncTrack" && sec != "Events" && splitTrack(sec) == null) {
                    if (unknownLines == null) unknownLines = ArrayList()
                    unknownLines.add(line)
                }
                continue
            }
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()

            when {
                sec == "Song" -> {
                    val v = unquote(value)
                    song.meta[key] = v
                    if (key == "Resolution") song.resolution = v.toFloatOrNull()?.toInt()?.coerceAtLeast(1) ?: 192
                }
                sec == "SyncTrack" -> {
                    val tick = key.toIntOrNull()
                    val tk = value.split(" ").filter { it.isNotEmpty() }
                    if (tick == null || tk.isEmpty()) {
                        song.rawSync.add(RawLine(tick ?: 0, line))
                    } else if (tk[0] == "B" && tk.size >= 2 && tk[1].toIntOrNull() != null) {
                        song.bpms.add(BpmEv(tick, tk[1].toInt()))
                    } else if (tk[0] == "A" && tk.size >= 2 && tk[1].toLongOrNull() != null) {
                        anchors.add(tick to tk[1].toLong())
                    } else if (tk[0] == "TS" && tk.size >= 2 && tk[1].toIntOrNull() != null) {
                        song.ts.add(TsEv(tick, tk[1].toInt(), if (tk.size >= 3) tk[2].toIntOrNull() ?: 2 else 2))
                    } else {
                        song.rawSync.add(RawLine(tick, value))
                    }
                }
                sec == "Events" -> {
                    val tick = key.toIntOrNull()
                    if (tick != null && value.startsWith("E ")) {
                        song.events.add(TextEv(tick, unquote(value.substring(2))))
                    } else if (tick != null) {
                        song.rawEvents.add(RawLine(tick, value))
                    }
                }
                else -> {
                    val st = splitTrack(sec)
                    if (st == null) {
                        if (unknownLines == null) unknownLines = ArrayList()
                        unknownLines.add(line)
                    } else {
                        val tick = key.toIntOrNull()
                        if (tick == null) continue
                        val track = song.track(sec)
                        if (!isEditableInstrument(st.second)) {
                            track.raw.add(RawLine(tick, value))
                            continue
                        }
                        val tk = value.split(" ").filter { it.isNotEmpty() }
                        val drums = st.second == "Drums"
                        val ghl = st.second.startsWith("GHL")
                        when {
                            tk.size >= 3 && tk[0] == "N" -> {
                                val f = tk[1].toIntOrNull()
                                val len = tk[2].toIntOrNull() ?: 0
                                if (f == null) {
                                    track.raw.add(RawLine(tick, value))
                                } else if (drums) {
                                    when (f) {
                                        0, 1, 2, 3, 4, 5 -> track.notes.add(NoteObj(tick, f, len))
                                        32 -> track.notes.add(NoteObj(tick, 0, len, fl = NoteObj.FL_DOUBLE))
                                        in 33..38 -> ext.getOrPut(sec) { ArrayList() }.add(Ext(tick, NoteObj.FL_ACCENT, f - 33))
                                        in 40..44 -> ext.getOrPut(sec) { ArrayList() }.add(Ext(tick, NoteObj.FL_GHOST, f - 39))
                                        66, 67, 68 -> ext.getOrPut(sec) { ArrayList() }.add(Ext(tick, NoteObj.FL_CYMBAL, f - 64))
                                        else -> track.raw.add(RawLine(tick, value))
                                    }
                                } else {
                                    when {
                                        f in 0..4 || f == 7 || (ghl && f == 8) -> track.notes.add(NoteObj(tick, f, len))
                                        f == 5 -> flagTicks.getOrPut(sec) { HashSet<Int>() to HashSet() }.first.add(tick)
                                        f == 6 -> flagTicks.getOrPut(sec) { HashSet<Int>() to HashSet() }.second.add(tick)
                                        else -> track.raw.add(RawLine(tick, value))
                                    }
                                }
                            }
                            tk.size >= 3 && tk[0] == "S" && (tk[1] == "2" || tk[1] == "64" || tk[1] == "65" || tk[1] == "66") ->
                                track.starpower.add(Phrase(tick, tk[2].toIntOrNull() ?: 0, tk[1].toInt()))
                            tk.isNotEmpty() && tk[0] == "E" && value.length > 2 ->
                                track.events.add(TextEv(tick, unquote(value.substring(2))))
                            else -> track.raw.add(RawLine(tick, value))
                        }
                    }
                }
            }
        }
        for ((sec, pair) in flagTicks) {
            val track = song.track(sec)
            for (t in pair.first) {
                var any = false
                for (n in track.notes) if (n.tick == t) {
                    n.forced = true
                    any = true
                }
                if (!any) track.raw.add(RawLine(t, "N 5 0"))
            }
            for (t in pair.second) {
                var any = false
                for (n in track.notes) if (n.tick == t) {
                    n.tap = true
                    any = true
                }
                if (!any) track.raw.add(RawLine(t, "N 6 0"))
            }
        }
        for ((sec, list) in ext) {
            val track = song.track(sec)
            for (x in list) {
                var any = false
                for (n in track.notes) if (n.tick == x.tick && n.fret == x.fret) {
                    n.fl = n.fl or x.kind
                    any = true
                }
                if (!any) {
                    val code = when (x.kind) {
                        NoteObj.FL_ACCENT -> 33 + x.fret
                        NoteObj.FL_GHOST -> 39 + x.fret
                        else -> 64 + x.fret
                    }
                    track.raw.add(RawLine(x.tick, "N $code 0"))
                }
            }
        }
        for ((t, micro) in anchors) {
            val b = song.bpms.firstOrNull { it.tick == t }
            if (b != null) b.anchor = micro else song.rawSync.add(RawLine(t, "A $micro"))
        }
        if (!song.meta.containsKey("Resolution")) song.meta["Resolution"] = song.resolution.toString()
        song.normalize()
        return song
    }

    fun write(song: Song): String {
        song.normalize()
        val nl = "\r\n"
        val sb = StringBuilder()
        sb.append("[Song]").append(nl).append("{").append(nl)
        song.meta["Resolution"] = song.resolution.toString()
        for ((k, v) in song.meta) {
            if (k in numericKeys) {
                sb.append("  ").append(k).append(" = ").append(v.ifEmpty { "0" }).append(nl)
            } else if (k == "Year") {
                sb.append("  Year = \"").append(if (v.startsWith(",")) v else ", $v").append("\"").append(nl)
            } else {
                sb.append("  ").append(k).append(" = \"").append(v.replace("\"", "'")).append("\"").append(nl)
            }
        }
        sb.append("}").append(nl)

        sb.append("[SyncTrack]").append(nl).append("{").append(nl)
        class Line(val tick: Int, val order: Int, val sub: Int, val text: String)
        val sync = ArrayList<Line>()
        for (t in song.ts) {
            val txt = if (t.denExp == 2) "TS ${t.num}" else "TS ${t.num} ${t.denExp}"
            sync.add(Line(t.tick, 0, 0, txt))
        }
        for (b in song.bpms) {
            sync.add(Line(b.tick, 1, 0, "B ${b.milli}"))
            val an = b.anchor
            if (an != null) sync.add(Line(b.tick, 1, 1, "A $an"))
        }
        for (r in song.rawSync) sync.add(Line(r.tick, 2, 0, r.text))
        sync.sortWith(compareBy<Line>({ it.tick }, { it.order }, { it.sub }))
        for (l in sync) sb.append("  ").append(l.tick).append(" = ").append(l.text).append(nl)
        sb.append("}").append(nl)

        sb.append("[Events]").append(nl).append("{").append(nl)
        val ev = ArrayList<Line>()
        for (e in song.events) ev.add(Line(e.tick, 0, 0, "E \"${e.text}\""))
        for (r in song.rawEvents) ev.add(Line(r.tick, 1, 0, r.text))
        ev.sortWith(compareBy<Line>({ it.tick }, { it.order }))
        for (l in ev) sb.append("  ").append(l.tick).append(" = ").append(l.text).append(nl)
        sb.append("}").append(nl)

        for ((name, track) in song.tracks) {
            if (track.isEmpty()) continue
            val lines = ArrayList<Line>()
            val flaggedForced = HashSet<Int>()
            val flaggedTap = HashSet<Int>()
            val drums = splitTrack(name)?.second == "Drums"
            for (n in track.notes) {
                if (drums) {
                    val code = if (n.fret == 0 && n.dbl) 32 else n.fret
                    lines.add(Line(n.tick, 1, n.fret, "N $code ${n.length}"))
                    if (n.cymbal && n.fret in 2..4) lines.add(Line(n.tick, 1, 100 + n.fret, "N ${64 + n.fret} 0"))
                    if (n.accent && n.fret in 1..5) lines.add(Line(n.tick, 1, 110 + n.fret, "N ${33 + n.fret} 0"))
                    if (n.ghost && n.fret in 1..5) lines.add(Line(n.tick, 1, 120 + n.fret, "N ${39 + n.fret} 0"))
                } else {
                    lines.add(Line(n.tick, 1, n.fret, "N ${n.fret} ${n.length}"))
                    if (n.forced) flaggedForced.add(n.tick)
                    if (n.tap) flaggedTap.add(n.tick)
                }
            }
            for (t in flaggedForced) lines.add(Line(t, 1, 100, "N 5 0"))
            for (t in flaggedTap) lines.add(Line(t, 1, 101, "N 6 0"))
            for (p in track.starpower) lines.add(Line(p.tick, 0, p.kind, "S ${p.kind} ${p.length}"))
            for (e in track.events) lines.add(Line(e.tick, 2, 0, "E ${e.text}"))
            for (r in track.raw) lines.add(Line(r.tick, 3, 0, r.text))
            lines.sortWith(compareBy<Line>({ it.tick }, { it.order }, { it.sub }))
            sb.append("[").append(name).append("]").append(nl).append("{").append(nl)
            for (l in lines) sb.append("  ").append(l.tick).append(" = ").append(l.text).append(nl)
            sb.append("}").append(nl)
        }
        for ((n, ls) in song.unknownSections) {
            sb.append("[").append(n).append("]").append(nl).append("{").append(nl)
            for (l in ls) sb.append("  ").append(l).append(nl)
            sb.append("}").append(nl)
        }
        return sb.toString()
    }

    fun bpmText(milli: Int): String = String.format(Locale.US, "%.3f", milli / 1000.0).trimEnd('0').trimEnd('.')
}
