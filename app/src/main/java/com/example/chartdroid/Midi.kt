package com.example.chartdroid

import java.io.ByteArrayOutputStream

/** Impor / ekspor file .mid (konvensi Rock Band / Clone Hero). */
object MidiIO {

    private val partNames = linkedMapOf(
        "PART GUITAR" to "Single",
        "T1 GEMS" to "Single",
        "PART GUITAR COOP" to "DoubleGuitar",
        "PART BASS" to "DoubleBass",
        "PART RHYTHM" to "DoubleRhythm",
        "PART KEYS" to "Keyboard",
        "PART DRUMS" to "Drums",
        "PART GUITAR GHL" to "GHLGuitar",
        "PART BASS GHL" to "GHLBass",
        "PART RHYTHM GHL" to "GHLRhythm",
        "PART GUITAR COOP GHL" to "GHLCoop",
        "PART KEYS GHL" to "GHLKeys"
    )

    private val diffBase = intArrayOf(60, 72, 84, 96)
    private val ghlBase = intArrayOf(58, 70, 82, 94)
    private val ghlFrets = intArrayOf(7, 0, 1, 2, 3, 4, 8) // offset 0..6 -> fret

    private class Ev(val tick: Int, val kind: Int, val a: Int, val b: Int, val data: ByteArray?)
    // kind: 0 = note on, 1 = note off, 2 = meta (a = type), 3 = sysex

    private class Interval(val note: Int, val start: Int, var end: Int)

    // ====================== BACA ======================
    private class Reader(val d: ByteArray) {
        var p = 0
        fun u8(): Int = d[p++].toInt() and 0xFF
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Int = (u16() shl 16) or u16()
        fun vlq(): Int {
            var v = 0
            while (true) {
                val b = u8()
                v = (v shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            return v
        }
        fun bytes(n: Int): ByteArray {
            val r = d.copyOfRange(p, minOf(p + n, d.size))
            p += n
            return r
        }
    }

    fun read(bytes: ByteArray): Song {
        val r = Reader(bytes)
        if (bytes.size < 14 || r.u32() != 0x4D546864) throw IllegalArgumentException("bukan file MIDI")
        r.u32()
        r.u16()
        val ntrks = r.u16()
        var division = r.u16()
        if (division and 0x8000 != 0 || division == 0) division = 480

        val song = Song()
        song.bpms.clear()
        song.ts.clear()
        song.resolution = division
        song.meta["Resolution"] = division.toString()

        class Trk(val name: String, val evs: List<Ev>)
        val tracks = ArrayList<Trk>()
        for (t in 0 until ntrks) {
            if (r.p + 8 > bytes.size) break
            if (r.u32() != 0x4D54726B) break
            val len = r.u32()
            val end = minOf(r.p + len, bytes.size)
            val evs = ArrayList<Ev>()
            var tick = 0
            var status = 0
            var name = ""
            try {
                while (r.p < end) {
                    tick += r.vlq()
                    var s = r.u8()
                    if (s < 0x80) {
                        r.p--
                        s = status
                    } else if (s < 0xF0) status = s
                    when {
                        s == 0xFF -> {
                            val type = r.u8()
                            val l = r.vlq()
                            val data = r.bytes(l)
                            if (type == 0x03 && name.isEmpty()) name = String(data, Charsets.ISO_8859_1)
                            evs.add(Ev(tick, 2, type, 0, data))
                        }
                        s == 0xF0 || s == 0xF7 -> {
                            val l = r.vlq()
                            evs.add(Ev(tick, 3, 0, 0, r.bytes(l)))
                        }
                        else -> {
                            val hi = s and 0xF0
                            val a = r.u8()
                            val b = if (hi == 0xC0 || hi == 0xD0) 0 else r.u8()
                            if (hi == 0x90 && b > 0) evs.add(Ev(tick, 0, a, b, null))
                            else if (hi == 0x80 || hi == 0x90) evs.add(Ev(tick, 1, a, b, null))
                        }
                    }
                }
            } catch (e: IndexOutOfBoundsException) {
            }
            r.p = end
            tracks.add(Trk(name, evs))
        }

        // tempo, birama
        val tempos = ArrayList<BpmEv>()
        val sigs = ArrayList<TsEv>()
        for (t in tracks) for (e in t.evs) {
            if (e.kind != 2 || e.data == null) continue
            if (e.a == 0x51 && e.data.size >= 3) {
                val us = ((e.data[0].toInt() and 0xFF) shl 16) or ((e.data[1].toInt() and 0xFF) shl 8) or (e.data[2].toInt() and 0xFF)
                if (us > 0) tempos.add(BpmEv(e.tick, Math.round(6.0e10 / us).toInt()))
            } else if (e.a == 0x58 && e.data.size >= 2) {
                sigs.add(TsEv(e.tick, e.data[0].toInt() and 0xFF, e.data[1].toInt() and 0xFF))
            }
        }
        song.bpms.addAll(tempos.sortedBy { it.tick }.distinctBy { it.tick })
        song.ts.addAll(sigs.sortedBy { it.tick }.distinctBy { it.tick })
        song.normalize()

        for (t in tracks) {
            val inst = partNames[t.name.trim()]
            if (t.name.trim() == "EVENTS") {
                for (e in t.evs) {
                    if (e.kind != 2 || e.data == null || (e.a != 0x01 && e.a != 0x05)) continue
                    var txt = String(e.data, Charsets.ISO_8859_1).trim()
                    if (txt.startsWith("[") && txt.endsWith("]")) txt = txt.substring(1, txt.length - 1)
                    if (txt.startsWith("prc_")) txt = "section " + txt.substring(4)
                    if (txt.isNotEmpty()) song.events.add(TextEv(e.tick, txt))
                }
            } else if (inst != null) {
                readPart(song, inst, t.evs)
            }
        }
        if (tracks.isNotEmpty() && tracks[0].name.isNotEmpty() && tracks[0].name != "EVENTS" && partNames[tracks[0].name] == null) {
            song.meta["Name"] = tracks[0].name
        }
        song.normalize()
        return song
    }

    private fun readPart(song: Song, inst: String, evs: List<Ev>) {
        val mode = Lanes.modeOf(inst)
        // kumpulkan interval not
        val open = HashMap<Int, Int>()
        val iv = ArrayList<Interval>()
        var enhancedOpens = false
        val sysex = ArrayList<Triple<Int, Int, Pair<Int, Boolean>>>() // tick, diff, (code, value)
        for (e in evs) {
            when (e.kind) {
                0 -> if (!open.containsKey(e.a)) open[e.a] = e.tick
                1 -> {
                    val s = open.remove(e.a)
                    if (s != null) iv.add(Interval(e.a, s, e.tick))
                }
                2 -> if (e.data != null && (e.a == 0x01 || e.a == 0x05)) {
                    val txt = String(e.data, Charsets.ISO_8859_1).trim().removePrefix("[").removeSuffix("]")
                    if (txt == "ENHANCED_OPENS") enhancedOpens = true
                }
                3 -> {
                    val dt = e.data
                    if (dt != null && dt.size >= 7 && dt[0].toInt() == 0x50 && dt[1].toInt() == 0x53 && dt[2].toInt() == 0 && dt[3].toInt() == 0) {
                        val diff = dt[4].toInt() and 0xFF
                        val code = dt[5].toInt() and 0xFF
                        val v = (dt[6].toInt() and 0xFF) == 1
                        sysex.add(Triple(e.tick, diff, code to v))
                    }
                }
            }
        }
        for ((n, s) in open) iv.add(Interval(n, s, s + 1))
        iv.sortBy { it.start }

        val tm = song.resolution
        if (mode == Mode.DRUMS) {
            val tomMarks = iv.filter { it.note in 110..112 }
            val hasMarks = tomMarks.isNotEmpty()
            for (d in 0..3) {
                val tr = song.track(ChartIO.trackKey(ChartIO.difficulties[d], inst))
                for (i in iv) {
                    val off = i.note - diffBase[d]
                    if (i.note == 95 && d == 3) {
                        tr.notes.add(NoteObj(i.start, 0, 0, fl = NoteObj.FL_DOUBLE))
                        continue
                    }
                    if (off in 0..5) {
                        val n = NoteObj(i.start, off, 0)
                        if (hasMarks && off in 2..4) {
                            val isTom = tomMarks.any { it.note == 108 + off && it.start <= i.start && i.start < it.end }
                            n.cymbal = !isTom
                        }
                        tr.notes.add(n)
                    }
                }
            }
            for (i in iv) {
                val kind = when (i.note) {
                    116 -> 2
                    in 120..124 -> 64
                    126 -> 65
                    127 -> 66
                    else -> 0
                }
                if (kind == 0) continue
                for (d in 0..3) {
                    val tr = song.track(ChartIO.trackKey(ChartIO.difficulties[d], inst))
                    if (kind == 64 && tr.starpower.any { it.kind == 64 && it.tick == i.start }) continue
                    tr.starpower.add(Phrase(i.start, i.end - i.start, kind))
                }
            }
            cleanup(song, inst)
            return
        }
        val bases = if (mode == Mode.GHL) ghlBase else diffBase
        val trs = Array(4) { song.track(ChartIO.trackKey(ChartIO.difficulties[it], inst)) }
        val forcedHopo = Array(4) { ArrayList<Interval>() }
        val forcedStrum = Array(4) { ArrayList<Interval>() }
        val taps = ArrayList<Interval>()
        for (i in iv) {
            when {
                i.note == 116 -> for (d in 0..3) trs[d].starpower.add(Phrase(i.start, i.end - i.start, 2))
                i.note == 103 -> for (d in 0..3) {
                    trs[d].events.add(TextEv(i.start, "solo"))
                    trs[d].events.add(TextEv(i.end, "soloend"))
                }
                i.note == 104 -> taps.add(i)
                else -> {
                    for (d in 0..3) {
                        val off = i.note - bases[d]
                        if (mode == Mode.GUITAR) {
                            when (off) {
                                in 0..4 -> trs[d].notes.add(NoteObj(i.start, off, (i.end - i.start).let { if (it > tm / 3) it else 0 }))
                                -1 -> if (enhancedOpens) trs[d].notes.add(NoteObj(i.start, 7, (i.end - i.start).let { if (it > tm / 3) it else 0 }))
                                5 -> forcedHopo[d].add(i)
                                6 -> forcedStrum[d].add(i)
                            }
                        } else {
                            when (off) {
                                in 0..6 -> trs[d].notes.add(NoteObj(i.start, ghlFrets[off], (i.end - i.start).let { if (it > tm / 3) it else 0 }))
                                7 -> forcedHopo[d].add(i)
                                8 -> forcedStrum[d].add(i)
                            }
                        }
                    }
                }
            }
        }
        // sysex: open (code 1) dan tap (code 4)
        val sysOpen = Array(4) { ArrayList<IntArray>() }
        val sysTap = Array(4) { ArrayList<IntArray>() }
        run {
            val startOpen = HashMap<Int, Int>()
            val startTap = HashMap<Int, Int>()
            for ((tick, diff, cv) in sysex.sortedBy { it.first }) {
                val code = cv.first
                val on = cv.second
                val ds = if (diff == 0xFF) 0..3 else diff..diff
                for (d in ds) {
                    if (d !in 0..3) continue
                    val key = d
                    if (code == 1) {
                        if (on) startOpen[key] = tick else startOpen.remove(key)?.let { sysOpen[d].add(intArrayOf(it, tick)) }
                    } else if (code == 4) {
                        if (on) startTap[key] = tick else startTap.remove(key)?.let { sysTap[d].add(intArrayOf(it, tick)) }
                    }
                }
            }
        }
        for (d in 0..3) {
            val tr = trs[d]
            tr.notes.sortWith(compareBy<NoteObj>({ it.tick }, { it.fret }))
            for (o in sysOpen[d]) for (n in tr.notes) if (n.fret == 0 && n.tick >= o[0] && n.tick < o[1].coerceAtLeast(o[0] + 1)) n.fret = 7
            val nat = Hopo.natural(tr.notes, song.resolution)
            for ((idx, n) in tr.notes.withIndex()) {
                val covHopo = forcedHopo[d].any { n.tick >= it.start && n.tick < maxOf(it.end, it.start + 1) }
                val covStrum = forcedStrum[d].any { n.tick >= it.start && n.tick < maxOf(it.end, it.start + 1) }
                if (covHopo && !nat[idx]) n.forced = true
                if (covStrum && nat[idx]) n.forced = true
                if (taps.any { n.tick >= it.start && n.tick < maxOf(it.end, it.start + 1) } ||
                    sysTap[d].any { n.tick >= it[0] && n.tick < maxOf(it[1], it[0] + 1) }
                ) {
                    n.tap = true
                    n.forced = false
                }
            }
        }
        cleanup(song, inst)
    }

    private fun cleanup(song: Song, inst: String) {
        for (d in 0..3) {
            val k = ChartIO.trackKey(ChartIO.difficulties[d], inst)
            val t = song.tracks[k]
            if (t != null && t.notes.isEmpty()) song.tracks.remove(k)
        }
    }

    // ====================== TULIS ======================
    private class OutEv(val tick: Int, val order: Int, val bytes: ByteArray)

    private fun vlq(out: ByteArrayOutputStream, v: Int) {
        var value = v
        val buf = ArrayList<Int>()
        buf.add(value and 0x7F)
        value = value ushr 7
        while (value > 0) {
            buf.add((value and 0x7F) or 0x80)
            value = value ushr 7
        }
        for (i in buf.indices.reversed()) out.write(buf[i])
    }

    private fun meta(type: Int, data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0xFF)
        o.write(type)
        vlq(o, data.size)
        o.write(data)
        return o.toByteArray()
    }

    private fun text(type: Int, s: String) = meta(type, s.toByteArray(Charsets.ISO_8859_1))

    private fun trackBytes(name: String, evs: List<OutEv>): ByteArray {
        val body = ByteArrayOutputStream()
        vlq(body, 0)
        body.write(meta(0x03, name.toByteArray(Charsets.ISO_8859_1)))
        var last = 0
        for (e in evs.sortedWith(compareBy<OutEv>({ it.tick }, { it.order }))) {
            vlq(body, e.tick - last)
            last = e.tick
            body.write(e.bytes)
        }
        vlq(body, 0)
        body.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4D, 0x54, 0x72, 0x6B))
        val b = body.toByteArray()
        out.write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
        out.write(b)
        return out.toByteArray()
    }

    private fun noteOn(tick: Int, n: Int, evs: MutableList<OutEv>, len: Int) {
        evs.add(OutEv(tick, 2, byteArrayOf(0x90.toByte(), n.toByte(), 100)))
        evs.add(OutEv(tick + maxOf(len, 1), 0, byteArrayOf(0x80.toByte(), n.toByte(), 0)))
    }

    private fun sysex(tick: Int, diff: Int, code: Int, v: Int) = OutEv(
        tick, 1,
        byteArrayOf(0xF0.toByte(), 8, 0x50, 0x53, 0, 0, diff.toByte(), code.toByte(), v.toByte(), 0xF7.toByte())
    )

    fun write(song: Song): ByteArray {
        song.normalize()
        val res = song.resolution
        val tempo = ArrayList<OutEv>()
        for (b in song.bpms) {
            val us = Math.round(6.0e10 / b.milli.coerceAtLeast(1)).toInt()
            tempo.add(OutEv(b.tick, 1, meta(0x51, byteArrayOf((us ushr 16).toByte(), (us ushr 8).toByte(), us.toByte()))))
        }
        for (t in song.ts) {
            tempo.add(OutEv(t.tick, 0, meta(0x58, byteArrayOf(t.num.toByte(), t.denExp.toByte(), 24, 8))))
        }
        val events = ArrayList<OutEv>()
        for (e in song.events) {
            val s = if (e.text.startsWith("section ")) "[section ${e.text.substring(8)}]" else "[${e.text}]"
            events.add(OutEv(e.tick, 1, text(0x01, s)))
        }
        val chunks = ArrayList<ByteArray>()
        chunks.add(trackBytes(song.name.ifEmpty { "ChartDroid" }, tempo))
        chunks.add(trackBytes("EVENTS", events))
        for ((key, inst) in partNames.entries.map { it.key to it.value }.distinctBy { it.second }) {
            val mode = Lanes.modeOf(inst)
            var any = false
            val evs = ArrayList<OutEv>()
            var enhanced = false
            for (d in 0..3) {
                val tr = song.tracks[ChartIO.trackKey(ChartIO.difficulties[d], inst)] ?: continue
                if (tr.notes.isEmpty()) continue
                any = true
                if (mode == Mode.DRUMS) {
                    val hasCym = tr.notes.any { it.cymbal }
                    for (n in tr.notes) {
                        if (n.fret == 0 && n.dbl && d == 3) noteOn(n.tick, 95, evs, 1)
                        if (n.fret == 0 && n.dbl && d == 3) continue
                        noteOn(n.tick, diffBase[d] + n.fret, evs, 1)
                        if (d == 3 && hasCym && n.fret in 2..4 && !n.cymbal) noteOn(n.tick, 108 + n.fret, evs, 1)
                    }
                } else {
                    val bases = if (mode == Mode.GHL) ghlBase else diffBase
                    val hop = Hopo.natural(tr.notes, res)
                    for ((idx, n) in tr.notes.withIndex()) {
                        val len = if (n.length > res / 3) n.length else 1
                        if (mode == Mode.GUITAR) {
                            if (n.fret == 7) {
                                enhanced = true
                                noteOn(n.tick, bases[d] - 1, evs, len)
                            } else noteOn(n.tick, bases[d] + n.fret, evs, len)
                        } else {
                            val off = ghlFrets.indexOf(n.fret)
                            if (off >= 0) noteOn(n.tick, bases[d] + off, evs, len)
                        }
                        if (n.forced && !n.tap) {
                            val off = if (mode == Mode.GUITAR) 5 else 7
                            val toHopo = !hop[idx]
                            noteOn(n.tick, bases[d] + off + (if (toHopo) 0 else 1), evs, 1)
                        }
                        if (n.tap) {
                            evs.add(sysex(n.tick, d, 4, 1))
                            evs.add(sysex(n.tick + 1, d, 4, 0))
                        }
                    }
                }
            }
            if (!any) continue
            val ex = song.tracks[ChartIO.trackKey("Expert", inst)] ?: song.tracks.entries.firstOrNull { it.key.endsWith(inst) && !it.value.isEmpty() }?.value
            if (ex != null) {
                for (p in ex.starpower) {
                    val n = when (p.kind) {
                        2 -> 116
                        64 -> 120
                        65 -> 126
                        66 -> 127
                        else -> continue
                    }
                    noteOn(p.tick, n, evs, p.length)
                }
                var solo = -1
                for (e in ex.events.sortedBy { it.tick }) {
                    if (e.text == "solo") solo = e.tick
                    else if (e.text == "soloend" && solo >= 0) {
                        noteOn(solo, 103, evs, e.tick - solo)
                        solo = -1
                    }
                }
            }
            if (enhanced) evs.add(OutEv(0, 1, text(0x01, "[ENHANCED_OPENS]")))
            chunks.add(trackBytes(key, evs))
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 1))
        out.write(byteArrayOf((chunks.size ushr 8).toByte(), chunks.size.toByte()))
        out.write(byteArrayOf((res ushr 8).toByte(), res.toByte()))
        for (c in chunks) out.write(c)
        return out.toByteArray()
    }
}
