package com.example.chartdroid

import java.util.Locale

/** Satu not. fret: 0..4 = Hijau, Merah, Kuning, Biru, Oranye; 7 = Open. */
class NoteObj(var tick: Int, var fret: Int, var length: Int = 0, var tap: Boolean = false, var forced: Boolean = false) {
    fun copy() = NoteObj(tick, fret, length, tap, forced)
}

class Phrase(var tick: Int, var length: Int)

class TextEv(var tick: Int, var text: String)

class BpmEv(var tick: Int, var milli: Int)

class TsEv(var tick: Int, var num: Int, var denExp: Int)

/** Baris yang tidak dikenali disimpan apa adanya supaya tidak hilang saat menyimpan. */
class RawLine(val tick: Int, val text: String)

class Track {
    val notes = ArrayList<NoteObj>()
    val starpower = ArrayList<Phrase>()
    val events = ArrayList<TextEv>()
    val raw = ArrayList<RawLine>()

    fun isEmpty() = notes.isEmpty() && starpower.isEmpty() && events.isEmpty() && raw.isEmpty()

    fun sortAll() {
        notes.sortWith(compareBy<NoteObj>({ it.tick }, { it.fret }))
        starpower.sortBy { it.tick }
        events.sortBy { it.tick }
    }

    fun deepCopy(): Track {
        val t = Track()
        for (n in notes) t.notes.add(n.copy())
        for (p in starpower) t.starpower.add(Phrase(p.tick, p.length))
        for (e in events) t.events.add(TextEv(e.tick, e.text))
        t.raw.addAll(raw)
        return t
    }
}

class Song {
    val meta = LinkedHashMap<String, String>()
    var resolution = 192
    val bpms = ArrayList<BpmEv>()
    val ts = ArrayList<TsEv>()
    val events = ArrayList<TextEv>()
    val rawSync = ArrayList<RawLine>()
    val rawEvents = ArrayList<RawLine>()
    val unknownSections = ArrayList<Pair<String, List<String>>>()
    val tracks = LinkedHashMap<String, Track>()

    var offset: Float
        get() = meta["Offset"]?.toFloatOrNull() ?: 0f
        set(v) {
            meta["Offset"] = trimFloat(v)
        }

    var name: String
        get() = meta["Name"] ?: ""
        set(v) {
            meta["Name"] = v
        }

    init {
        bpms.add(BpmEv(0, 120000))
        ts.add(TsEv(0, 4, 2))
        meta["Name"] = ""
        meta["Artist"] = ""
        meta["Charter"] = ""
        meta["Offset"] = "0"
        meta["Resolution"] = "192"
        meta["Player2"] = "bass"
        meta["Difficulty"] = "0"
        meta["PreviewStart"] = "0"
        meta["PreviewEnd"] = "0"
        meta["Genre"] = "rock"
        meta["MediaType"] = "cd"
        meta["MusicStream"] = "song.ogg"
    }

    fun track(key: String): Track = tracks.getOrPut(key) { Track() }

    fun normalize() {
        if (bpms.none { it.tick == 0 }) bpms.add(BpmEv(0, 120000))
        if (ts.none { it.tick == 0 }) ts.add(TsEv(0, 4, 2))
        bpms.sortBy { it.tick }
        ts.sortBy { it.tick }
        events.sortBy { it.tick }
        for (t in tracks.values) t.sortAll()
    }

    companion object {
        fun trimFloat(v: Float): String {
            val s = String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
            return if (s.isEmpty() || s == "-") "0" else s
        }
    }
}

/** Peta tempo: konversi tick <-> detik. */
class TempoMap(private val song: Song) {
    private val ticks = ArrayList<Int>()
    private val secs = ArrayList<Double>()
    private val secPerTick = ArrayList<Double>()

    init {
        val list = song.bpms.sortedBy { it.tick }
        var t = 0.0
        var prevTick = 0
        var prevSpt = 0.0
        for ((i, b) in list.withIndex()) {
            if (i > 0) t += (b.tick - prevTick) * prevSpt
            val spt = 60.0 / (b.milli.coerceAtLeast(1) / 1000.0) / song.resolution
            ticks.add(b.tick)
            secs.add(t)
            secPerTick.add(spt)
            prevTick = b.tick
            prevSpt = spt
        }
        if (ticks.isEmpty()) {
            ticks.add(0)
            secs.add(0.0)
            secPerTick.add(0.5 / song.resolution)
        }
    }

    private fun idxForTick(tick: Double): Int {
        var lo = 0
        var hi = ticks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (ticks[mid] <= tick) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun tickToSec(tick: Double): Double {
        val i = idxForTick(tick)
        return secs[i] + (tick - ticks[i]) * secPerTick[i]
    }

    fun secToTick(sec: Double): Double {
        var lo = 0
        var hi = secs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (secs[mid] <= sec) lo = mid else hi = mid - 1
        }
        return ticks[lo] + (sec - secs[lo]) / secPerTick[lo]
    }

    fun bpmAt(tick: Int): Double {
        val i = idxForTick(tick.toDouble())
        return 60.0 / secPerTick[i] / song.resolution
    }
}

/** Satu garis ukuran/ketukan untuk digambar. */
class BeatLine(val tick: Int, val isMeasure: Boolean, val measureNo: Int)

object BeatGrid {
    /** Daftar garis ketukan dari tick [from] sampai [to] (mengikuti time signature). */
    fun lines(song: Song, from: Int, to: Int): List<BeatLine> {
        val out = ArrayList<BeatLine>()
        val res = song.resolution
        val tsl = song.ts.sortedBy { it.tick }
        var startMeasure = 0
        for (i in tsl.indices) {
            val t = tsl[i]
            val end = if (i + 1 < tsl.size) tsl[i + 1].tick else Int.MAX_VALUE
            val num = t.num.coerceAtLeast(1)
            val beat = (res * 4) / (1 shl t.denExp.coerceIn(0, 10))
            if (beat <= 0) continue
            val measure = beat * num
            if (end >= from) {
                var k = if (from > t.tick) (from - t.tick + beat - 1) / beat else 0
                while (true) {
                    val tick = t.tick + k * beat
                    if (tick > to || tick >= end) break
                    out.add(BeatLine(tick, k % num == 0, startMeasure + k / num + 1))
                    k++
                }
            }
            if (end != Int.MAX_VALUE) startMeasure += (end - t.tick + measure - 1) / measure
        }
        return out
    }
}
