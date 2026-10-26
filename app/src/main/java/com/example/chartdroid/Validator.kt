package com.example.chartdroid

/** Pemeriksa chart sederhana (mirip Song Validator Moonscraper). */
object Validator {
    fun report(song: Song, hasAudio: Boolean, lengthSec: Double): String {
        val sb = StringBuilder()
        var errors = 0
        var warns = 0
        fun err(s: String) {
            sb.append("✖ ").append(s).append('\n')
            errors++
        }
        fun warn(s: String) {
            sb.append("⚠ ").append(s).append('\n')
            warns++
        }
        val res = song.resolution
        val tm = TempoMap(song)
        fun at(tick: Int): String {
            val s = tm.tickToSec(tick.toDouble())
            return "tick $tick (${String.format(java.util.Locale.US, "%d:%05.2f", (s / 60).toInt(), s % 60)})"
        }
        if (song.bpms.none { it.tick == 0 }) err("Tidak ada BPM di tick 0")
        if (song.ts.none { it.tick == 0 }) err("Tidak ada birama di tick 0")
        val bt = song.bpms.groupBy { it.tick }.filter { it.value.size > 1 }
        for ((t, _) in bt) err("BPM ganda di ${at(t)}")
        for (b in song.bpms) if (b.milli < 1000) err("BPM terlalu kecil (${b.milli / 1000.0}) di ${at(b.tick)}")
        if (song.name.isBlank()) warn("Judul lagu kosong")
        if (!hasAudio) warn("Audio belum dimuat")
        if (song.events.none { it.text.startsWith("section ") }) warn("Belum ada Section")
        for ((key, tr) in song.tracks) {
            if (tr.notes.isEmpty()) continue
            val st = ChartIO.splitTrack(key) ?: continue
            val title = "${st.first} ${ChartIO.instruments[st.second] ?: st.second}"
            val notes = tr.notes
            val seen = HashSet<Long>()
            var i = 0
            while (i < notes.size) {
                var j = i
                while (j < notes.size && notes[j].tick == notes[i].tick) j++
                // duplikat
                for (k in i until j) {
                    val key2 = notes[k].tick.toLong() * 100 + notes[k].fret
                    if (!seen.add(key2)) err("$title: not ganda di ${at(notes[k].tick)}")
                }
                val mode = Lanes.modeOf(st.second)
                if (mode != Mode.DRUMS) {
                    val hasOpen = (i until j).any { notes[it].fret == 7 }
                    if (hasOpen && j - i > 1) warn("$title: not open bercampur not lain di ${at(notes[i].tick)}")
                }
                // sustain menabrak not berikutnya
                if (j < notes.size) {
                    val nextTick = notes[j].tick
                    for (k in i until j) {
                        val n = notes[k]
                        if (n.length > 0 && n.tick + n.length > nextTick) {
                            warn("$title: sustain menembus not berikutnya di ${at(n.tick)}")
                            break
                        }
                    }
                }
                i = j
            }
            if (lengthSec > 0) {
                val last = notes[notes.size - 1]
                if (tm.tickToSec(last.tick.toDouble()) + song.offset > lengthSec + 1) warn("$title: ada not melewati akhir audio")
            }
            // star power
            val sp = tr.starpower.filter { it.kind == 2 }.sortedBy { it.tick }
            for (a in sp.indices) {
                val p = sp[a]
                if (notes.none { it.tick >= p.tick && it.tick < p.tick + maxOf(p.length, 1) }) warn("$title: star power kosong di ${at(p.tick)}")
                if (a + 1 < sp.size && p.tick + p.length > sp[a + 1].tick) err("$title: star power tumpang tindih di ${at(p.tick)}")
            }
            // solo berpasangan
            var open = -1
            for (e in tr.events.sortedBy { it.tick }) {
                if (e.text == "solo") {
                    if (open >= 0) err("$title: solo ganda di ${at(e.tick)}")
                    open = e.tick
                } else if (e.text == "soloend") {
                    if (open < 0) err("$title: soloend tanpa solo di ${at(e.tick)}")
                    open = -1
                }
            }
            if (open >= 0) err("$title: solo tidak ditutup (soloend hilang)")
            if (st.first == "Expert" && notes.size < 4) warn("$title: sedikit sekali not")
        }
        // section
        val secs = song.events.filter { it.text.startsWith("section ") }.groupBy { it.tick }
        for ((t, l) in secs) if (l.size > 1) warn("Section ganda di ${at(t)}")
        if (res < 96) warn("Resolusi rendah ($res); disarankan 192")
        if (errors == 0 && warns == 0) return "✔ Tidak ada masalah ditemukan."
        return "$errors error, $warns peringatan\n\n" + sb.toString()
    }
}
