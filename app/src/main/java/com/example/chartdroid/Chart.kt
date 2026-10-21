package com.example.chartdroid

import java.util.Locale

/** Satu not. fret: 0..4 = Hijau, Merah, Kuning, Biru, Oranye; 7 = Open. */
class NoteObj(var tick: Int, var fret: Int, var length: Int = 0, var tap: Boolean = false, var forced: Boolean = false, var fl: Int = 0) {
    fun copy() = NoteObj(tick, fret, length, tap, forced, fl)

    var cymbal: Boolean
        get() = fl and FL_CYMBAL != 0
        set(v) { fl = if (v) fl or FL_CYMBAL else fl and FL_CYMBAL.inv() }
    var accent: Boolean
        get() = fl and FL_ACCENT != 0
        set(v) { fl = if (v) fl or FL_ACCENT else fl and FL_ACCENT.inv() }
    var ghost: Boolean
        get() = fl and FL_GHOST != 0
        set(v) { fl = if (v) fl or FL_GHOST else fl and FL_GHOST.inv() }
    var dbl: Boolean
        get() = fl and FL_DOUBLE != 0
        set(v) { fl = if (v) fl or FL_DOUBLE else fl and FL_DOUBLE.inv() }

    companion object {
        const val FL_CYMBAL = 1
        const val FL_ACCENT = 2
        const val FL_GHOST = 4
        const val FL_DOUBLE = 8
    }
}

/** kind: 2 = star power, 64 = drum fill, 65 = drum roll tunggal, 66 = drum roll ganda. */
class Phrase(var tick: Int, var length: Int, var kind: Int = 2)

class TextEv(var tick: Int, var text: String)

/** anchor: waktu (mikrodetik) tempat BPM ini dikunci; null bila tidak di-anchor. */
class BpmEv(var tick: Int, var milli: Int, var anchor: Long? = null)

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
        for (p in starpower) t.starpower.add(Phrase(p.tick, p.length, p.kind))
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


enum class Mode { GUITAR, GHL, DRUMS }

/** Aturan HOPO alami (sama seperti Clone Hero / Moonscraper). */
object Hopo {
    /** Kembalikan array parallel: true bila not tersebut HOPO efektif (natural xor forced, tap = bukan HOPO). */
    fun effective(notes: List<NoteObj>, res: Int): BooleanArray {
        val nat = natural(notes, res)
        val out = BooleanArray(notes.size)
        for (i in notes.indices) out[i] = if (notes[i].tap) false else (nat[i] != notes[i].forced)
        return out
    }

    fun natural(notes: List<NoteObj>, res: Int): BooleanArray {
        val n = notes.size
        val out = BooleanArray(n)
        val limit = 65 * res / 192
        var i = 0
        var prevStart = -1
        var prevEnd = -1
        while (i < n) {
            var j = i
            while (j < n && notes[j].tick == notes[i].tick) j++
            val chord = (j - i) > 1
            if (!chord && prevStart >= 0) {
                val prevChord = (prevEnd - prevStart) > 1
                val same = !prevChord && notes[prevStart].fret == notes[i].fret
                out[i] = !same && (notes[i].tick - notes[prevStart].tick) <= limit
            }
            prevStart = i
            prevEnd = j
            i = j
        }
        return out
    }
}

/** Susunan jalur untuk tiap jenis instrumen. */
class Lanes(val mode: Mode, val drum5: Boolean) {
    /** Fret yang digambar sebagai jalur, urut dari kiri. */
    val frets: IntArray = when (mode) {
        Mode.GUITAR -> intArrayOf(0, 1, 2, 3, 4)
        Mode.GHL -> intArrayOf(0, 1, 2, 3, 4, 8)
        Mode.DRUMS -> if (drum5) intArrayOf(1, 2, 3, 4, 5) else intArrayOf(1, 2, 3, 4)
    }
    val count get() = frets.size
    /** Fret yang digambar sebagai bar selebar highway (open / kick). */
    val barFret: Int get() = if (mode == Mode.DRUMS) 0 else 7

    fun laneOf(fret: Int): Int {
        if (mode == Mode.DRUMS && !drum5 && fret == 5) return 3
        for (i in frets.indices) if (frets[i] == fret) return i
        return -1
    }

    fun fretOf(lane: Int): Int = frets[lane.coerceIn(0, frets.size - 1)]

    fun color(fret: Int): Int = when (mode) {
        Mode.GUITAR -> if (fret == 7) 0xFFA855F7.toInt() else GUITAR_COLORS[fret.coerceIn(0, 4)]
        Mode.GHL -> when (fret) {
            0, 1, 2 -> 0xFFF3F4F6.toInt()
            3, 4, 8 -> 0xFF4B5563.toInt()
            else -> 0xFFA855F7.toInt()
        }
        Mode.DRUMS -> when (fret) {
            0 -> 0xFFF59E0B.toInt()
            1 -> 0xFFEF4444.toInt()
            2 -> 0xFFEAB308.toInt()
            3 -> 0xFF3B82F6.toInt()
            4 -> if (drum5) 0xFFF97316.toInt() else 0xFF22C55E.toInt()
            else -> 0xFF22C55E.toInt()
        }
    }

    companion object {
        val GUITAR_COLORS = intArrayOf(0xFF22C55E.toInt(), 0xFFEF4444.toInt(), 0xFFEAB308.toInt(), 0xFF3B82F6.toInt(), 0xFFF97316.toInt())

        fun modeOf(inst: String): Mode = when {
            inst == "Drums" -> Mode.DRUMS
            inst.startsWith("GHL") -> Mode.GHL
            else -> Mode.GUITAR
        }
    }
}
