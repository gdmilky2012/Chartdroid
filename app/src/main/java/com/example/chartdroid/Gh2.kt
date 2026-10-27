package com.example.chartdroid

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Pemasang lagu untuk Guitar Hero II (PS2) / GH2 Deluxe Custom Edition.
 * Format dan alur mengikuti Onyx Music Game Toolkit (mtolly/onyx, GPL-3).
 * Bagian di file ini murni JVM (tanpa API Android) supaya bisa diuji.
 */

// ====================================================================
// DTB (DTA biner)
// ====================================================================
object Dtb {
    sealed class C
    class I(val v: Int) : C()
    class F(val v: Float) : C()
    /** Tipe: 2 Var, 5 Sym, 7 IfDef, 0x12 String, 0x20 Define, 0x21 Include, 0x22 Merge, 0x23 IfNDef, 0x25 Undef. */
    class S(val type: Int, val bytes: ByteArray) : C()
    /** Tipe tanpa data: 6, 8, 9, 0x24. */
    class N(val type: Int) : C()
    /** Tipe 0x10 Parens, 0x11 Braces, 0x13 Brackets. */
    class T(val type: Int, val kids: MutableList<C>) : C()

    class Doc(val flag: Int, val root: MutableList<C>) {
        /** 0 = tidak terenkripsi, 1 = enkripsi lama (GH PS2), 2 = enkripsi baru (Rock Band). */
        var crypt = 0
        var key = 0
    }

    fun sym(s: String): C = S(5, s.toByteArray(Charsets.ISO_8859_1))
    fun str(s: String): C = S(0x12, s.toByteArray(Charsets.ISO_8859_1))
    fun v(s: String): C = S(2, s.toByteArray(Charsets.ISO_8859_1))
    fun i(n: Int): C = I(n)
    fun f(n: Float): C = F(n)
    fun p(vararg k: C): C = T(0x10, k.toMutableList())
    fun pl(k: List<C>): C = T(0x10, k.toMutableList())
    fun b(vararg k: C): C = T(0x11, k.toMutableList())

    /** (key a b c) */
    fun kv(key: String, vararg a: C): C = T(0x10, (listOf(sym(key)) + a.toList()).toMutableList())

    fun copy(c: C): C = when (c) {
        is I -> I(c.v)
        is F -> F(c.v)
        is S -> S(c.type, c.bytes.copyOf())
        is N -> N(c.type)
        is T -> T(c.type, c.kids.map { copy(it) }.toMutableList())
    }

    /** Teks mirip DTA untuk pemeriksaan manual. */
    fun text(c: C, sb: StringBuilder = StringBuilder(), depth: Int = 0): StringBuilder {
        when (c) {
            is I -> sb.append(c.v)
            is F -> sb.append(c.v)
            is S -> when (c.type) {
                5 -> sb.append(String(c.bytes, Charsets.ISO_8859_1))
                2 -> sb.append('$').append(String(c.bytes, Charsets.ISO_8859_1))
                0x12 -> sb.append('"').append(String(c.bytes, Charsets.ISO_8859_1)).append('"')
                else -> sb.append("<s").append(c.type).append(':').append(String(c.bytes, Charsets.ISO_8859_1)).append('>')
            }
            is N -> sb.append("<n").append(c.type).append('>')
            is T -> {
                val (o, cl) = when (c.type) { 0x10 -> "(" to ")"; 0x11 -> "{" to "}"; 0x13 -> "[" to "]"; else -> "<t${c.type}" to ">" }
                sb.append(o)
                for ((i, k) in c.kids.withIndex()) { if (i > 0) sb.append(' '); text(k, sb, depth + 1) }
                sb.append(cl)
            }
        }
        return sb
    }

    fun symName(c: C): String? = if (c is S && c.type == 5) String(c.bytes, Charsets.ISO_8859_1) else null

    /** Elemen Parens yang anak pertamanya Sym == key. */
    fun isEntry(c: C, key: String): Boolean = c is T && c.type == 0x10 && c.kids.isNotEmpty() && symName(c.kids[0]) == key

    // ---------- baca ----------
    fun parse(data: ByteArray): Doc {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val flag = bb.get().toInt() and 0xFF
        val root = readTree(bb)
        return Doc(flag, root)
    }

    private fun readTree(bb: ByteBuffer): MutableList<C> {
        val n = bb.short.toInt() and 0xFFFF
        bb.int // id simpul
        val out = ArrayList<C>(n)
        repeat(n) { out.add(readChunk(bb)) }
        return out
    }

    private fun readChunk(bb: ByteBuffer): C {
        val id = bb.int
        return when (id) {
            0x0 -> I(bb.int)
            0x1 -> F(bb.float)
            0x2, 0x5, 0x7, 0x12, 0x20, 0x21, 0x22, 0x23, 0x25 -> {
                val len = bb.int
                require(len >= 0 && len <= bb.remaining()) { "DTB rusak (panjang string)" }
                val arr = ByteArray(len)
                bb.get(arr)
                S(id, arr)
            }
            0x6, 0x8, 0x9, 0x24 -> {
                bb.int
                N(id)
            }
            0x10, 0x11, 0x13 -> T(id, readTree(bb))
            else -> throw IllegalArgumentException("Tipe chunk DTB tidak dikenal: $id")
        }
    }

    // ---------- tulis ----------
    private class W {
        val out = ByteArrayOutputStream()
        var next = 1
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v ushr 16) and 0xFFFF) }
        fun tree(kids: List<C>) {
            le16(kids.size)
            le32(next++)
            for (k in kids) chunk(k)
        }
        fun chunk(c: C) {
            when (c) {
                is I -> { le32(0); le32(c.v) }
                is F -> { le32(1); le32(java.lang.Float.floatToIntBits(c.v)) }
                is S -> { le32(c.type); le32(c.bytes.size); out.write(c.bytes) }
                is N -> { le32(c.type); le32(0) }
                is T -> { le32(c.type); tree(c.kids) }
            }
        }
    }

    fun write(doc: Doc): ByteArray {
        val w = W()
        w.out.write(doc.flag)
        w.tree(doc.root)
        return w.out.toByteArray()
    }

    // ---------- enkripsi DTB (dari Onyx Harmonix/DTA/Crypt.hs) ----------
    private fun oldStream(key: Int, n: Int): ByteArray {
        val tbl = IntArray(0xF9)
        var v1 = key
        for (i in 0..0xF8) {
            val v2 = v1 * 0x41C64E6D + 0x3039
            v1 = v2 * 0x41C64E6D + 0x3039
            tbl[i] = (v1 and 0x7FFF0000) or (v2 ushr 16)
        }
        var i1 = 0
        var i2 = 0x67
        val out = ByteArray(n)
        for (k in 0 until n) {
            val next = tbl[i1] xor tbl[i2]
            tbl[i1] = next
            i1 = if (i1 == 0xF8) 0 else i1 + 1
            i2 = if (i2 == 0xF8) 0 else i2 + 1
            out[k] = next.toByte()
        }
        return out
    }

    private fun newStream(key: Int, n: Int): ByteArray {
        var d = key.toLong() and 0xFFFFFFFFL
        val out = ByteArray(n)
        for (k in 0 until n) {
            val q = d / 0x1F31DL
            var v = ((d - q * 0x1F31DL) * 0x41A7L - q * 0xB14L) and 0xFFFFFFFFL
            if (v > 0x7FFFFFFFL) v = (v + 0x7FFFFFFFL) and 0xFFFFFFFFL
            d = v
            out[k] = v.toByte()
        }
        return out
    }

    private fun xorWith(data: ByteArray, from: Int, stream: ByteArray): ByteArray {
        val out = ByteArray(data.size - from)
        for (i in out.indices) out[i] = (data[from + i].toInt() xor stream[i].toInt()).toByte()
        return out
    }

    private fun tryParse(b: ByteArray, strict: Boolean): Doc? = try {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val flag = bb.get().toInt() and 0xFF
        val root = readTree(bb)
        if (strict && bb.hasRemaining()) null else Doc(flag, root)
    } catch (e: Exception) { null }

    /** Baca DTB, otomatis mendeteksi enkripsi. */
    fun parseAny(data: ByteArray): Doc {
        tryParse(data, true)?.let { return it }
        if (data.size > 8) {
            val key = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val n = data.size - 4
            tryParse(xorWith(data, 4, oldStream(key, n)), true)?.let { it.crypt = 1; it.key = key; return it }
            tryParse(xorWith(data, 4, newStream(key, n)), true)?.let { it.crypt = 2; it.key = key; return it }
        }
        throw IllegalArgumentException("DTB tidak bisa dibaca (bukan DTB, atau enkripsi tidak dikenal)")
    }

    /** Tulis DTB dengan enkripsi yang sama seperti saat dibaca. */
    fun writeAny(doc: Doc): ByteArray {
        val plain = write(doc)
        if (doc.crypt == 0) return plain
        val st = if (doc.crypt == 1) oldStream(doc.key, plain.size) else newStream(doc.key, plain.size)
        val enc = xorWith(plain, 0, st)
        val out = ByteArray(4 + enc.size)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(doc.key)
        System.arraycopy(enc, 0, out, 4, enc.size)
        return out
    }

    // ---------- bantu ----------
    fun removeEntry(list: MutableList<C>, key: String) {
        list.removeAll { isEntry(it, key) }
    }
}

// ====================================================================
// HDR / ARK (versi 3: Guitar Hero 1/2)
// ====================================================================
interface Blob {
    fun length(): Long
    fun read(pos: Long, len: Int): ByteArray
    fun write(pos: Long, data: ByteArray)
    fun truncate(len: Long) {}
}

class ArkEntry(var offset: Long, var name: String, var folder: String?, var size: Long, var inflate: Long) {
    val path: String get() = if (folder != null) "$folder/$name" else name
}

class Ark(val partSizes: MutableList<Long>, val entries: MutableList<ArkEntry>, val parts: List<Blob>) {
    var dirty = false

    fun find(path: String): ArkEntry? = entries.lastOrNull { it.path.equals(path, ignoreCase = true) }

    private fun locate(global: Long): Pair<Int, Long> {
        var rest = global
        for ((i, s) in partSizes.withIndex()) {
            if (rest < s) return i to rest
            rest -= s
        }
        throw IllegalStateException("Offset di luar semua file ARK")
    }

    fun read(e: ArkEntry): ByteArray {
        require(e.inflate == 0L) { "Entri terkompresi tidak didukung: ${e.path}" }
        val out = ByteArray(e.size.toInt())
        var done = 0
        var g = e.offset
        while (done < out.size) {
            val (pi, local) = locate(g)
            val can = min((partSizes[pi] - local), (out.size - done).toLong()).toInt()
            val chunk = parts[pi].read(local, can)
            System.arraycopy(chunk, 0, out, done, chunk.size)
            done += can
            g += can
        }
        return out
    }

    /** Tambah/ganti berkas: data baru ditulis di akhir ARK terakhir, entri HDR diperbarui. */
    fun put(path: String, data: ByteArray) {
        val last = parts.size - 1
        val base = partSizes.dropLast(1).sum()
        val local = partSizes[last]
        parts[last].write(local, data)
        partSizes[last] = local + data.size
        val existing = find(path)
        if (existing != null) {
            existing.offset = base + local
            existing.size = data.size.toLong()
            existing.inflate = 0
        } else {
            val cut = path.lastIndexOf('/')
            val folder = if (cut >= 0) path.substring(0, cut) else null
            val name = if (cut >= 0) path.substring(cut + 1) else path
            entries.add(ArkEntry(base + local, name, folder, data.size.toLong(), 0))
        }
        dirty = true
    }

    fun remove(path: String) {
        if (entries.removeAll { it.path.equals(path, ignoreCase = true) }) dirty = true
    }

    fun hdrBytes(): ByteArray {
        val strings = LinkedHashMap<String, Int>()
        for (e in entries) {
            strings.getOrPut(e.name) { strings.size }
            e.folder?.let { strings.getOrPut(it) { strings.size } }
        }
        val bank = ByteArrayOutputStream()
        val offs = ArrayList<Int>()
        for (s in strings.keys) {
            offs.add(bank.size())
            bank.write(s.toByteArray(Charsets.ISO_8859_1))
            bank.write(0)
        }
        val bb = ByteBuffer.allocate(64 + partSizes.size * 4 + bank.size() + offs.size * 4 + entries.size * 20)
            .order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(3)
        bb.putInt(partSizes.size)
        bb.putInt(partSizes.size)
        for (s in partSizes) bb.putInt(s.toInt())
        bb.putInt(bank.size())
        bb.put(bank.toByteArray())
        bb.putInt(offs.size)
        for (o in offs) bb.putInt(o)
        bb.putInt(entries.size)
        for (e in entries) {
            bb.putInt(e.offset.toInt())
            bb.putInt(strings[e.name]!!)
            bb.putInt(e.folder?.let { strings[it]!! } ?: -1)
            bb.putInt(e.size.toInt())
            bb.putInt(e.inflate.toInt())
        }
        return bb.array().copyOf(bb.position())
    }

    companion object {
        fun open(hdr: ByteArray, parts: List<Blob>): Ark {
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val version = bb.int
            require(version in 0..10) { "HDR terenkripsi atau bukan HDR (versi $version). Hanya HDR GH1/GH2 versi 3 yang didukung." }
            require(version == 3) { "Versi ARK $version tidak didukung (butuh versi 3: Guitar Hero 1/2)." }
            val c1 = bb.int
            val c2 = bb.int
            require(c1 == c2 && c1 > 0) { "HDR rusak (jumlah ARK)" }
            val sizes = ArrayList<Long>()
            repeat(c1) { sizes.add(bb.int.toLong() and 0xFFFFFFFFL) }
            val bankSize = bb.int
            val bank = ByteArray(bankSize)
            bb.get(bank)
            val offCount = bb.int
            val offs = IntArray(offCount) { bb.int }
            fun str(i: Int): String {
                require(i in 0 until offCount) { "HDR rusak (indeks string)" }
                var e = offs[i]
                while (e < bank.size && bank[e].toInt() != 0) e++
                return String(bank, offs[i], e - offs[i], Charsets.ISO_8859_1)
            }
            val n = bb.int
            val list = ArrayList<ArkEntry>(n)
            repeat(n) {
                val off = bb.int.toLong() and 0xFFFFFFFFL
                val name = bb.int
                val folder = bb.int
                val size = bb.int.toLong() and 0xFFFFFFFFL
                val infl = bb.int.toLong() and 0xFFFFFFFFL
                list.add(ArkEntry(off, str(name), if (folder == -1) null else str(folder), size, infl))
            }
            require(parts.size == sizes.size) { "Jumlah file ARK (${parts.size}) tidak cocok dengan HDR (${sizes.size})" }
            return Ark(sizes, list, parts)
        }
    }
}

// ====================================================================
// VAG / VGS (PS2 ADPCM)
// ====================================================================
object Vag {
    private val filter = arrayOf(
        doubleArrayOf(0.0, 0.0),
        doubleArrayOf(-60.0 / 64.0, 0.0),
        doubleArrayOf(-115.0 / 64.0, 52.0 / 64.0),
        doubleArrayOf(-98.0 / 64.0, 55.0 / 64.0),
        doubleArrayOf(-122.0 / 64.0, 60.0 / 64.0)
    )

    /** State: [state1, state2, state1a, state2a]. */
    class State {
        val s = DoubleArray(4)
        val buffer = Array(28) { DoubleArray(5) }
        val mx = DoubleArray(5)
        val dSamples = DoubleArray(28)
        val four = IntArray(28)
    }

    /** Port encode_vag.c (Guitar Wizard / Onyx). in: 28 sampel. Mengembalikan 16 byte. */
    fun encodeBlock(inp: ShortArray, flag: Int, st: State): ByteArray {
        val out = ByteArray(16)
        out[1] = flag.toByte()
        val buffer = st.buffer
        var min = 1e10
        val mx = st.mx
        var predictNr = 0
        var s1 = 0.0
        var s2 = 0.0
        val stat0 = st.s[0]
        val stat1 = st.s[1]
        for (i in 0 until 5) {
            mx[i] = 0.0
            s1 = stat0
            s2 = stat1
            for (j in 0 until 28) {
                var s0 = inp[j].toDouble()
                if (s0 > 30719.0) s0 = 30719.0
                if (s0 < -30720.0) s0 = -30720.0
                val ds = s0 + s1 * filter[i][0] + s2 * filter[i][1]
                buffer[j][i] = ds
                if (abs(ds) > mx[i]) mx[i] = abs(ds)
                s2 = s1
                s1 = s0
            }
            if (mx[i] < min) {
                min = mx[i]
                predictNr = i
            }
            if (min <= 7) {
                predictNr = 0
                break
            }
        }
        st.s[0] = s1
        st.s[1] = s2
        val dSamples = st.dSamples
        for (i in 0 until 28) dSamples[i] = buffer[i][predictNr]
        val min2 = min.toInt()
        var shiftMask = 0x4000
        var shiftFactor = 0
        while (shiftFactor < 12) {
            if ((shiftMask and (min2 + (shiftMask shr 3))) != 0) break
            shiftFactor++
            shiftMask = shiftMask shr 1
        }
        var a0 = st.s[2]
        var a1 = st.s[3]
        val four = st.four
        for (i in 0 until 28) {
            val s0a = dSamples[i] + a0 * filter[predictNr][0] + a1 * filter[predictNr][1]
            val ds = s0a * (1 shl shiftFactor).toDouble()
            var di = (ds.toInt() + 0x800) and 0xfffff000.toInt()
            if (di > 32767) di = 32767
            if (di < -32768) di = -32768
            four[i] = di.toShort().toInt()
            di = di shr shiftFactor
            a1 = a0
            a0 = di.toDouble() - s0a
        }
        st.s[2] = a0
        st.s[3] = a1
        out[0] = ((predictNr shl 4) or shiftFactor).toByte()
        var k = 0
        while (k < 28) {
            val amt = ((four[k + 1] shr 8) and 0xF0) or ((four[k] shr 12) and 0xF)
            out[k / 2 + 2] = amt.toByte()
            k += 2
        }
        return out
    }

    /** Sumber PCM 16-bit berurutan. */
    abstract class Src(val channels: Int, val rate: Int) {
        /** Isi buf (interleaved) dengan sampai [frames] frame; sisanya nol. */
        abstract fun read(buf: ShortArray, frames: Int)
    }

    class ZeroSrc(channels: Int, rate: Int) : Src(channels, rate) {
        override fun read(buf: ShortArray, frames: Int) { java.util.Arrays.fill(buf, 0, frames * channels, 0) }
    }

    /** PCM s16le interleaved dari stream (mis. berkas sementara). Dipotong/di-pad ke totalFrames oleh penulis. */
    class StreamSrc(channels: Int, rate: Int, private val inp: InputStream, private var skipFrames: Long) : Src(channels, rate) {
        private var eof = false
        private val tmp = ByteArray(28 * 8 * 2)
        init {
            // lewati skipFrames (offset positif)
            var toSkip = skipFrames * channels * 2
            while (toSkip > 0) {
                val s = inp.skip(toSkip)
                if (s <= 0) { eof = true; break }
                toSkip -= s
            }
        }
        override fun read(buf: ShortArray, frames: Int) {
            val need = frames * channels * 2
            var got = 0
            val arr = if (need <= tmp.size) tmp else ByteArray(need)
            while (!eof && got < need) {
                val n = inp.read(arr, got, need - got)
                if (n < 0) { eof = true; break }
                got += n
            }
            val samples = got / 2
            for (i in 0 until frames * channels) {
                buf[i] = if (i < samples) ((arr[2 * i].toInt() and 0xFF) or (arr[2 * i + 1].toInt() shl 8)).toShort() else 0
            }
        }
    }

    /**
     * Tulis VGS multi-rate. Seluruh sumber ditulis selama [seconds] detik.
     */
    fun writeVgs(out: OutputStream, srcs: List<Src>, seconds: Double) {
        val blocks = srcs.map { ceil(seconds * it.rate / 28.0).toInt().coerceAtLeast(1) }
        val hdr = ByteBuffer.allocate(0x80).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("VgS!".toByteArray())
        hdr.putInt(2)
        for ((i, s) in srcs.withIndex()) repeat(s.channels) { hdr.putInt(s.rate); hdr.putInt(blocks[i]) }
        require(hdr.position() <= 0x80) { "Terlalu banyak kanal untuk header VGS" }
        out.write(hdr.array())
        val firstCh = ArrayList<Int>()
        var c = 0
        for (s in srcs) { firstCh.add(c); c += s.channels }
        val states = srcs.map { s -> Array(s.channels) { State() } }
        val written = IntArray(srcs.size)
        val bufs = srcs.map { ShortArray(28 * it.channels) }
        val chan = ShortArray(28)
        while (true) {
            // pilih sumber dengan detik tertulis paling kecil
            var pick = -1
            var best = Double.MAX_VALUE
            for (i in srcs.indices) {
                val t = written[i] * 28.0 / srcs[i].rate
                if (t < best) { best = t; pick = i }
            }
            if (pick < 0 || best >= seconds || written[pick] >= blocks[pick]) {
                // sumber terpilih selesai; cek apakah semua selesai
                if (srcs.indices.all { written[it] >= blocks[it] || written[it] * 28.0 / srcs[it].rate >= seconds }) break
                // pilih yang belum selesai
                pick = srcs.indices.filter { written[it] < blocks[it] }.minByOrNull { written[it] * 28.0 / srcs[it].rate } ?: break
            }
            val s = srcs[pick]
            s.read(bufs[pick], 28)
            for (ch in 0 until s.channels) {
                for (k in 0 until 28) chan[k] = bufs[pick][k * s.channels + ch]
                out.write(encodeBlock(chan, firstCh[pick] + ch, states[pick][ch]))
            }
            written[pick]++
        }
    }
}

// ====================================================================
// MIDI GH2 dan DTA
// ====================================================================
object Gh2 {
    const val RES = 480
    private val bases = intArrayOf(60, 72, 84, 96)

    class Meta(
        var symbol: String = "",
        var title: String = "",
        var artist: String = "",
        var album: String = "",
        var author: String = "",
        var year: String = "",
        var genre: String = "",
        var coop: String = "rhythm", // "rhythm" | "bass"
        var guitarRank: Int = -1,
        var bassRank: Int = -1,
        var drumRank: Int = -1,
        var cover: Boolean = false,
        var deluxe: Boolean = true
    )

    class MidiResult(val bytes: ByteArray, val scores: IntArray, val padSeconds: Double, val endSeconds: Double, val warnings: List<String>)

    fun sanitizeSymbol(s: String): String {
        val t = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        return if (t.isEmpty()) "customsong" else t.take(24)
    }

    private fun latin(s: String): String = buildString {
        for (ch in s) append(if (ch == '&') '+' else if (ch.code < 256) ch else '?')
    }

    private class OutEv(val tick: Int, val order: Int, val bytes: ByteArray)

    private fun vlq(o: ByteArrayOutputStream, v: Int) {
        var value = v
        val buf = ArrayList<Int>()
        buf.add(value and 0x7F)
        value = value ushr 7
        while (value > 0) { buf.add((value and 0x7F) or 0x80); value = value ushr 7 }
        for (i in buf.indices.reversed()) o.write(buf[i])
    }

    private fun meta(type: Int, data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0xFF); o.write(type); vlq(o, data.size); o.write(data)
        return o.toByteArray()
    }

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
        val b = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4D, 0x54, 0x72, 0x6B))
        out.write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
        out.write(b)
        return out.toByteArray()
    }

    private fun note(tick: Int, n: Int, len: Int, evs: MutableList<OutEv>) {
        evs.add(OutEv(tick, 2, byteArrayOf(0x90.toByte(), n.toByte(), 100)))
        evs.add(OutEv(tick + max(len, 1), 0, byteArrayOf(0x80.toByte(), n.toByte(), 0)))
    }

    private fun gems(song: Song, instKey: String, d: Int): List<NoteObj>? {
        val tr = song.tracks[ChartIO.trackKey(ChartIO.difficulties[d], instKey)] ?: return null
        val l = tr.notes.filter { it.fret in 0..4 }.sortedWith(compareBy({ it.tick }, { it.fret }))
        return if (l.isEmpty()) null else l
    }

    /** Daftar instrumen 5-fret yang berisi not. */
    fun availableParts(song: Song): List<String> =
        listOf("Single", "DoubleGuitar", "DoubleBass", "DoubleRhythm").filter { inst ->
            (0..3).any { gems(song, inst, it) != null }
        }

    private fun scoreBase(notes: List<NoteObj>, res: Int): Int {
        var total = 0
        for (n in notes) {
            val beats = n.length.toDouble() / res
            val tail = if (beats > 1.0 / 3.0) Math.floor(beats * 25).toInt() else 0
            total += 50 + tail
        }
        return total
    }

    /**
     * Susun MIDI GH2. leadKey/coopKey = kunci instrumen chart (Single, DoubleBass, ...).
     * audioSeconds: durasi audio (detik) atau <=0 bila tidak ada audio.
     */
    fun buildMidi(song: Song, leadKey: String, coopKey: String?, coopIsBass: Boolean, audioSeconds: Double, audioOffsetSec: Double): MidiResult {
        song.normalize()
        val warnings = ArrayList<String>()
        val res = song.resolution
        val scale = RES.toDouble() / res
        fun t480(t: Int) = Math.round(t * scale).toInt()
        val tm = TempoMap(song)
        val bpm0 = (song.bpms.minByOrNull { it.tick }?.milli ?: 120000) / 1000.0

        // posisi not pertama (detik)
        var firstTick = Int.MAX_VALUE
        var lastTick = 0
        for (key in listOfNotNull(leadKey, coopKey)) for (d in 0..3) gems(song, key, d)?.let {
            firstTick = min(firstTick, it.first().tick)
            val l = it.last()
            lastTick = max(lastTick, l.tick + l.length)
        }
        if (firstTick == Int.MAX_VALUE) throw IllegalArgumentException("Track ${leadKey} tidak punya not 5-fret")
        val firstSec = tm.tickToSec(firstTick.toDouble())
        val lastSec = tm.tickToSec(lastTick.toDouble())
        // pad: whole beat pada tempo awal agar not pertama >= 3 detik
        var padBeats = 0
        if (firstSec < 3.0) padBeats = ceil((3.0 - firstSec) * bpm0 / 60.0).toInt()
        val padTicks = padBeats * RES
        val padSec = padBeats * 60.0 / bpm0

        val usedPadTicks = padTicks

        val tempo = ArrayList<OutEv>()
        for (b in song.bpms) {
            val us = Math.round(6.0e10 / b.milli.coerceAtLeast(1)).toInt()
            val tick = if (b.tick == 0) 0 else t480(b.tick) + usedPadTicks
            tempo.add(OutEv(tick, 1, meta(0x51, byteArrayOf((us ushr 16).toByte(), (us ushr 8).toByte(), us.toByte()))))
        }
        if (song.bpms.none { it.tick == 0 }) {
            val us = 500000
            tempo.add(OutEv(0, 1, meta(0x51, byteArrayOf((us ushr 16).toByte(), (us ushr 8).toByte(), us.toByte()))))
        }
        for (t in song.ts) {
            val tick = if (t.tick == 0) 0 else t480(t.tick) + usedPadTicks
            tempo.add(OutEv(tick, 0, meta(0x58, byteArrayOf(t.num.toByte(), t.denExp.toByte(), 24, 8))))
        }

        val scores = IntArray(4)
        fun buildPart(key: String, trackName: String): Pair<ByteArray?, IntArray> {
            val evs = ArrayList<OutEv>()
            val sc = IntArray(4)
            var any = false
            var fallback: List<NoteObj>? = null
            for (d in 3 downTo 0) {
                var g = gems(song, key, d)
                if (g == null) {
                    if (fallback == null) continue
                    g = fallback
                    warnings.add("${trackName}: tingkat ${ChartIO.difficulties[d]} kosong, memakai salinan tingkat di atasnya")
                }
                fallback = g
                any = true
                val hop = Hopo.natural(g, res)
                for ((idx, n) in g.withIndex()) {
                    val start = t480(n.tick) + usedPadTicks
                    var len = t480(n.length)
                    if (n.length <= res / 3) len = RES / 8
                    val next = g.drop(idx + 1).firstOrNull { it.fret == n.fret }
                    if (next != null) {
                        val lim = t480(next.tick) + usedPadTicks - 1
                        if (start + len > lim) len = max(1, lim - start)
                    }
                    note(start, bases[d] + n.fret, len, evs)
                    if (n.forced && !n.tap) {
                        val toHopo = !hop[idx]
                        note(start, bases[d] + 5 + (if (toHopo) 0 else 1), 1, evs)
                    }
                }
                sc[d] = scoreBase(g, res)
                val own = song.tracks[ChartIO.trackKey(ChartIO.difficulties[d], key)]?.starpower?.filter { it.kind == 2 }.orEmpty()
                val sp = if (own.isNotEmpty()) own else song.tracks[ChartIO.trackKey("Expert", key)]?.starpower?.filter { it.kind == 2 }.orEmpty()
                for (p in sp) note(t480(p.tick) + usedPadTicks, bases[d] + 7, max(t480(p.length), 1), evs)
            }
            return if (any) trackBytes(trackName, evs) to sc else null to sc
        }

        val leadRes = buildPart(leadKey, "PART GUITAR")
        val leadBytes = leadRes.first!!
        val coopSrcKey = coopKey ?: leadKey
        val coopName = if (coopIsBass) "PART BASS" else "PART RHYTHM"
        val coopRes = buildPart(coopSrcKey, coopName)
        for (d in 0..3) scores[d] = leadRes.second[d] + coopRes.second[d]

        // akhir lagu
        var endSec = lastSec + padSec + 3.0
        if (audioSeconds > 0) {
            val audioEnd = audioSeconds - audioOffsetSec + padSec
            endSec = max(lastSec + padSec + 2.0, audioEnd - 5.0)
        }
        // detik (setelah pad) -> tick 480: hitung lewat tempo map hasil pad
        // pad di awal memakai tempo awal; selebihnya waktu chart + pad
        val endChartSec = endSec - padSec
        val endTick = t480(tm.secToTick(endChartSec).roundToInt()) + usedPadTicks

        val events = ArrayList<OutEv>()
        for (e in song.events) {
            if (e.text.startsWith("section ")) {
                val nm = latin(e.text.substring(8)).trim()
                events.add(OutEv(t480(e.tick) + usedPadTicks, 1, meta(0x01, "[section $nm]".toByteArray(Charsets.ISO_8859_1))))
            }
        }
        events.add(OutEv(0, 1, meta(0x01, "[music_start]".toByteArray())))
        events.add(OutEv(endTick, 1, meta(0x01, "[end]".toByteArray())))

        val chunks = ArrayList<ByteArray>()
        chunks.add(trackBytes(latin(song.name).ifEmpty { "GH2" }, tempo))
        chunks.add(leadBytes)
        coopRes.first?.let { chunks.add(it) }
        chunks.add(trackBytes("EVENTS", events))
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 1))
        out.write(byteArrayOf((chunks.size ushr 8).toByte(), chunks.size.toByte()))
        out.write(byteArrayOf((RES ushr 8).toByte(), RES.toByte()))
        for (c in chunks) out.write(c)
        return MidiResult(out.toByteArray(), scores, padSec, endSec, warnings.filter { it.isNotEmpty() }.distinct())
    }

    // ---------- DTA ----------
    private val outfits = listOf("punk1", "alterna1", "glam1", "goth2", "metal1", "grim")
    private val venues = listOf("battle", "small1", "small2", "theatre", "fest", "arena")
    private val guitars = listOf("flying_v", "lespaul", "sg")

    private fun nums(l: List<Int>) = Dtb.pl(l.map { Dtb.i(it) })

    private fun songBlock(sym: String, name: String, channels: Map<String, List<Int>>, pans: List<Int>, vols: List<Int>, cores: List<Int>, hopo: Int?): Dtb.C {
        val kids = ArrayList<Dtb.C>()
        kids.add(Dtb.kv("name", Dtb.str(name)))
        kids.add(Dtb.kv("tracks", Dtb.pl(channels.map { (k, v) -> Dtb.kv(k, nums(v)) })))
        kids.add(Dtb.kv("pans", Dtb.pl(pans.map { Dtb.f(it.toFloat()) })))
        kids.add(Dtb.kv("vols", Dtb.pl(vols.map { Dtb.f(it.toFloat()) })))
        kids.add(Dtb.kv("cores", Dtb.pl(cores.map { Dtb.i(it) })))
        kids.add(Dtb.kv("midi_file", Dtb.str("songs/$sym/$sym.mid")))
        if (hopo != null) kids.add(Dtb.kv("hopo_threshold", Dtb.i(hopo)))
        return Dtb.T(0x10, (listOf(Dtb.sym("song")) + kids).toMutableList()).also { }
    }

    /** Isi entri lagu untuk config/gen/songs.dtb (tanpa simbol di depan). */
    fun songEntry(m: Meta, previewStartMs: Int, previewEndMs: Int, durationMs: Int): List<Dtb.C> {
        val sym = m.symbol
        val h = sym.hashCode().let { if (it == Int.MIN_VALUE) 0 else abs(it) }
        val coopName = m.coop
        val kids = ArrayList<Dtb.C>()
        kids.add(Dtb.kv("name", Dtb.str(latin(m.title))))
        val artistStr = Dtb.str(latin(m.artist))
        if (m.deluxe) {
            val setters = ArrayList<Dtb.C>()
            setters.add(Dtb.sym("do"))
            fun set(k: String, v: Dtb.C) = setters.add(Dtb.b(Dtb.sym("set"), Dtb.v(k), v))
            if (m.album.isNotBlank()) set("songalbum", Dtb.str(latin(m.album)))
            if (m.author.isNotBlank()) set("author", Dtb.str(latin(m.author)))
            if (m.year.isNotBlank()) set("songyear", Dtb.str(latin(m.year)))
            if (m.genre.isNotBlank()) set("songgenre", Dtb.str(latin(m.genre)))
            if (durationMs > 0) set("songduration", Dtb.i(durationMs))
            set("songguitarrank", Dtb.i(m.guitarRank))
            set("songbassrank", Dtb.i(m.bassRank))
            set("songrhythmrank", Dtb.i(m.bassRank))
            set("songdrumrank", Dtb.i(m.drumRank))
            set("songartist", artistStr)
            kids.add(Dtb.T(0x10, mutableListOf(Dtb.sym("artist"), Dtb.T(0x11, setters), artistStr)))
        } else {
            kids.add(Dtb.kv("artist", artistStr))
        }
        if (!m.cover) kids.add(Dtb.kv("caption", Dtb.sym("performed_by")))
        // saluran: 0,1 = band stereo, 2 = diam (mono)
        val main = linkedMapOf("guitar" to listOf(2), coopName to listOf(2))
        val mainPans = listOf(-1, 1, 0)
        val mainVols = listOf(0, 0, 0)
        val mainCores = listOf(-1, -1, -1)
        val withFake = LinkedHashMap(main).also { it["fake"] = listOf(0, 1) }
        val hopo = 163
        fun song(key: String, ch: Map<String, List<Int>>): Dtb.C {
            val s = songBlock(sym, "songs/$sym/$sym", ch, mainPans, mainVols, mainCores, hopo) as Dtb.T
            s.kids[0] = Dtb.sym(key)
            return s
        }
        kids.add(song("song", if (m.deluxe) withFake else main))
        if (m.deluxe) kids.add(song("song_vs", main))
        kids.add(Dtb.kv("anim_tempo", Dtb.sym("kTempoMedium")))
        kids.add(Dtb.kv("preview", Dtb.i(previewStartMs), Dtb.i(previewEndMs)))
        kids.add(
            Dtb.kv(
                "quickplay",
                Dtb.kv("character_outfit", Dtb.sym(outfits[h % outfits.size])),
                Dtb.kv("guitar", Dtb.sym(guitars[(h / 7) % guitars.size])),
                Dtb.kv("venue", Dtb.sym(venues[(h / 13) % venues.size]))
            )
        )
        kids.add(Dtb.kv("practice_speeds", Dtb.i(100), Dtb.i(90), Dtb.i(75), Dtb.i(60)))
        for ((i, sp) in listOf(90, 75, 60).withIndex()) {
            val pr = songBlock(
                sym, "songs/$sym/${sym}_empty", linkedMapOf("guitar" to listOf(0), coopName to listOf(0)),
                listOf(0), listOf(0), listOf(-1), hopo
            ) as Dtb.T
            pr.kids[0] = Dtb.sym("song_practice_${i + 1}")
            kids.add(pr)
        }
        return kids
    }

    fun coopScoresEntry(sym: String, scores: IntArray): Dtb.C = Dtb.p(Dtb.sym(sym), Dtb.pl(scores.map { Dtb.i(it) }))

    class Item(val meta: Meta, val entry: List<Dtb.C>, val scores: IntArray)

    /** Edit songs/coop/store/locale .dtb sekali untuk semua lagu. */
    fun installDtbs(ark: Ark, items: List<Item>): List<String> {
        val warn = ArrayList<String>()
        fun edit(path: String, required: Boolean, f: (MutableList<Dtb.C>) -> Unit) {
            val e = ark.find(path)
            if (e == null) {
                if (required) throw IllegalStateException("Tidak menemukan $path di ARK. Apakah ini GH2 (GEN)?")
                warn.add("Berkas opsional tidak ada: $path")
                return
            }
            val doc = Dtb.parseAny(ark.read(e))
            f(doc.root)
            ark.put(path, Dtb.writeAny(doc))
        }
        edit("config/gen/songs.dtb", true) { root ->
            for (it in items) {
                if (root.any { c -> Dtb.isEntry(c, it.meta.symbol) }) warn.add("Simbol '${it.meta.symbol}' sudah ada di songs.dtb dan ditimpa")
                Dtb.removeEntry(root, it.meta.symbol)
                val kids = it.entry.toMutableList()
                // lengkapi kunci yang ada di entri DX asli ISO ini tetapi tidak ada di entri kita (mis. band)
                val tpl = root.firstOrNull { c -> c is Dtb.T && c.type == 0x10 && c.kids.any { k -> k is Dtb.T && k.kids.isNotEmpty() && Dtb.symName(k.kids[0]) == "song_vs" } } as? Dtb.T
                if (tpl != null && it.meta.deluxe) {
                    val have = kids.mapNotNull { k -> if (k is Dtb.T && k.kids.isNotEmpty()) Dtb.symName(k.kids[0]) else null }.toSet()
                    for (k in tpl.kids.drop(1)) {
                        val key = if (k is Dtb.T && k.kids.isNotEmpty()) Dtb.symName(k.kids[0]) else null
                        if (key != null && key !in have && !key.startsWith("song_practice")) kids.add(Dtb.copy(k))
                    }
                }
                root.add(0, Dtb.T(0x10, (listOf(Dtb.sym(it.meta.symbol)) + kids).toMutableList()))
            }
        }
        edit("config/gen/coop_max_scores.dtb", false) { root ->
            for (it in items) {
                Dtb.removeEntry(root, it.meta.symbol)
                root.add(0, coopScoresEntry(it.meta.symbol, it.scores))
            }
        }
        edit("config/gen/store.dtb", false) { root ->
            var done = false
            for (c in root) {
                if (c is Dtb.T && c.type == 0x10 && c.kids.isNotEmpty() && Dtb.symName(c.kids[0]) == "song") {
                    for (it in items) {
                        c.kids.removeAll { k -> Dtb.isEntry(k, it.meta.symbol) }
                        c.kids.add(Dtb.p(Dtb.sym(it.meta.symbol), Dtb.p(Dtb.sym("price"), Dtb.i(0))))
                    }
                    done = true
                }
            }
            if (!done) warn.add("store.dtb: grup 'song' tidak ditemukan; lagu tidak masuk toko bonus")
        }
        edit("ui/eng/gen/locale.dtb", false) { root ->
            for (it in items) {
                val sym = it.meta.symbol
                for (k in listOf(sym, sym + "_shop_desc", sym + "_author", "loading_tip_$sym")) Dtb.removeEntry(root, k)
            }
            val add = ArrayList<Dtb.C>()
            for (it in items) {
                val m = it.meta
                add.add(Dtb.p(Dtb.sym(m.symbol), Dtb.str(latin(m.title))))
                add.add(Dtb.p(Dtb.sym(m.symbol + "_shop_desc"), Dtb.str(latin(m.artist))))
                if (m.deluxe && m.author.isNotBlank()) add.add(Dtb.p(Dtb.sym(m.symbol + "_author"), Dtb.str(latin(m.author))))
            }
            root.addAll(0, add)
        }
        return warn
    }

    /** Pemasangan satu lagu (kompatibilitas). */
    fun installInto(ark: Ark, m: Meta, entry: List<Dtb.C>, scores: IntArray, files: Map<String, ByteArray>): List<String> {
        for ((name, data) in files) ark.put("songs/${m.symbol}/$name", data)
        return installDtbs(ark, listOf(Item(m, entry, scores)))
    }
}
