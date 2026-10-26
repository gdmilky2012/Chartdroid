package com.example.chartdroid

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pandangan sebagian dari sebuah Blob (berkas di dalam ISO). */
class SubBlob(private val base: Blob, var start: Long, var len: Long) : Blob {
    override fun length(): Long = len
    override fun read(pos: Long, len: Int): ByteArray = base.read(start + pos, len)
    override fun write(pos: Long, data: ByteArray) { base.write(start + pos, data) }
}

/**
 * Penambal ISO9660 untuk image PS2 GH2: menemukan berkas HDR dan ARK di folder GEN, memperbesar ARK terakhir
 * (memindahkannya ke akhir image bila perlu) dan menaruh HDR baru.
 */
class IsoGen private constructor(
    private val iso: Blob,
    private val trees: List<Tree>,
    private val hdrName: String,
    private val arkNames: List<String>
) {
    companion object {
        const val SS = 2048

        private class Rec(val recPos: Long, val lba: Long, val size: Long, val isDir: Boolean, val name: String)

        class Tree(val joliet: Boolean, val recs: MutableMap<String, Long>) // nama berkas -> posisi record

        private fun nameOf(b: ByteArray, off: Int, n: Int, joliet: Boolean): String {
            var s = if (joliet) String(b, off, n, Charsets.UTF_16BE) else String(b, off, n, Charsets.ISO_8859_1)
            val semi = s.indexOf(';')
            if (semi >= 0) s = s.substring(0, semi)
            return s
        }

        private fun readDir(iso: Blob, lba: Long, size: Long, joliet: Boolean): List<Rec> {
            val out = ArrayList<Rec>()
            if (size <= 0 || size > (64 shl 20)) return out
            val d = iso.read(lba * SS, size.toInt())
            var p = 0
            while (p < d.size) {
                val l = d[p].toInt() and 0xFF
                if (l == 0) { p = (p / SS + 1) * SS; continue }
                if (p + l > d.size) break
                val bb = ByteBuffer.wrap(d, p, l).order(ByteOrder.LITTLE_ENDIAN)
                val loc = bb.getInt(p + 2).toLong() and 0xFFFFFFFFL
                val sz = bb.getInt(p + 10).toLong() and 0xFFFFFFFFL
                val flags = d[p + 25].toInt()
                val nl = d[p + 32].toInt() and 0xFF
                val isSpecial = nl == 1 && (d[p + 33].toInt() == 0 || d[p + 33].toInt() == 1)
                if (!isSpecial) out.add(Rec(lba * SS + p, loc, sz, (flags and 2) != 0, nameOf(d, p + 33, nl, joliet)))
                p += l
            }
            return out
        }

        fun open(iso: Blob): IsoGen {
            val trees = ArrayList<Tree>()
            var hdrName: String? = null
            var arks: List<String> = emptyList()
            var vd = 16L
            while (vd < 64) {
                val sec = iso.read(vd * SS, SS)
                if (String(sec, 1, 5, Charsets.ISO_8859_1) != "CD001") break
                val type = sec[0].toInt() and 0xFF
                if (type == 255) break
                if (type == 1 || type == 2) {
                    val joliet = type == 2
                    val bb = ByteBuffer.wrap(sec).order(ByteOrder.LITTLE_ENDIAN)
                    val rootLba = bb.getInt(156 + 2).toLong() and 0xFFFFFFFFL
                    val rootSize = bb.getInt(156 + 10).toLong() and 0xFFFFFFFFL
                    // cari direktori yang berisi .hdr (kedalaman <= 3)
                    var found: Tree? = null
                    fun walk(lba: Long, size: Long, depth: Int) {
                        if (found != null || depth > 3) return
                        val rs = readDir(iso, lba, size, joliet)
                        val h = rs.firstOrNull { !it.isDir && it.name.endsWith(".hdr", true) }
                        if (h != null) {
                            val base = h.name.substring(0, h.name.length - 4)
                            val ar = rs.filter { !it.isDir && it.name.endsWith(".ark", true) && it.name.startsWith(base, true) }
                            val m = HashMap<String, Long>()
                            m[h.name] = h.recPos
                            for (a in ar) m[a.name] = a.recPos
                            found = Tree(joliet, m)
                            if (!joliet) {
                                hdrName = h.name
                                arks = ar.sortedBy { x ->
                                    x.name.substring(base.length).dropLast(4).trimStart('_').toIntOrNull() ?: -1
                                }.map { it.name }
                            }
                            return
                        }
                        for (r in rs.filter { it.isDir }) walk(r.lba, r.size, depth + 1)
                    }
                    walk(rootLba, rootSize, 0)
                    found?.let { trees.add(it) }
                }
                vd++
            }
            val h = hdrName ?: throw IllegalStateException("ISO tidak berisi berkas .HDR (bukan image GH2 PS2 yang didukung)")
            if (arks.isEmpty()) throw IllegalStateException("ISO tidak berisi berkas .ARK di folder yang sama dengan $h")
            return IsoGen(iso, trees, h, arks)
        }
    }

    private fun tree0() = trees.first { !it.joliet }

    private fun recInfo(name: String): Pair<Long, Long> {
        val pos = tree0().recs[name]!!
        val b = iso.read(pos, 34)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return (bb.getInt(2).toLong() and 0xFFFFFFFFL) to (bb.getInt(10).toLong() and 0xFFFFFFFFL)
    }

    private fun setRec(name: String, lba: Long, size: Long) {
        for (t in trees) {
            // nama di tree Joliet sama (setelah normalisasi); bila tidak ada, lewati
            val pos = t.recs[name] ?: continue
            // lokasi: LE@2, BE@6 ; ukuran: LE@10, BE@14
            val loc = ByteBuffer.allocate(8)
            loc.order(ByteOrder.LITTLE_ENDIAN).putInt(0, lba.toInt())
            loc.order(ByteOrder.BIG_ENDIAN).putInt(4, lba.toInt())
            iso.write(pos + 2, loc.array())
            val sz = ByteBuffer.allocate(8)
            sz.order(ByteOrder.LITTLE_ENDIAN).putInt(0, size.toInt())
            sz.order(ByteOrder.BIG_ENDIAN).putInt(4, size.toInt())
            iso.write(pos + 10, sz.array())
        }
    }

    private fun roundUp(v: Long) = (v + SS - 1) / SS * SS

    val arkFileNames: List<String> get() = arkNames

    fun readHdr(): ByteArray {
        val (lba, size) = recInfo(hdrName)
        return iso.read(lba * SS, size.toInt())
    }

<<<<<<< HEAD
    private var lastArk: SubBlob? = null
<<<<<<< HEAD
=======
    private var hdrOverwritten = false
>>>>>>> 58c523f (v0.6.3)
=======
    private class TailFile(val size: Long, val recs: List<Long>, val tmp: java.io.File)
    private var tail: List<TailFile> = emptyList()

    private fun setRecAt(pos: Long, lba: Long) {
        val loc = ByteBuffer.allocate(8)
        loc.order(ByteOrder.LITTLE_ENDIAN).putInt(0, lba.toInt())
        loc.order(ByteOrder.BIG_ENDIAN).putInt(4, lba.toInt())
        iso.write(pos + 2, loc.array())
    }

    /**
     * Bila semua yang berada SETELAH ARK hanyalah berkas biasa yang kecil, pindahkan berkas itu (disimpan sementara)
     * ke ujung image setelah ARK membesar, sehingga ARK tidak perlu disalin ulang dan ukuran ISO hanya bertambah
     * sebesar lagu baru. Mengembalikan null bila tidak aman (ada direktori/path table setelah ARK, atau terlalu besar).
     */
    private fun planTail(arkLba: Long, arkSize: Long, progress: (String) -> Unit): List<TailFile>? {
        val arkEndSec = (arkLba * SS + roundUp(arkSize)) / SS
        val files = HashMap<Long, Pair<Long, MutableList<Long>>>()
        var blocked = false
        var vd = 16L
        while (vd < 64 && !blocked) {
            val sec = iso.read(vd * SS, SS)
            if (String(sec, 1, 5, Charsets.ISO_8859_1) != "CD001") break
            val type = sec[0].toInt() and 0xFF
            if (type == 255) break
            if (type == 1 || type == 2) {
                val joliet = type == 2
                val bb = ByteBuffer.wrap(sec)
                val le = bb.order(ByteOrder.LITTLE_ENDIAN)
                // path table
                for (off in intArrayOf(140, 144)) {
                    val pl = le.getInt(off).toLong() and 0xFFFFFFFFL
                    if (pl != 0L && pl >= arkEndSec) blocked = true
                }
                val bg = ByteBuffer.wrap(sec).order(ByteOrder.BIG_ENDIAN)
                for (off in intArrayOf(148, 152)) {
                    val pl = bg.getInt(off).toLong() and 0xFFFFFFFFL
                    if (pl != 0L && pl >= arkEndSec) blocked = true
                }
                val rootLba = le.getInt(156 + 2).toLong() and 0xFFFFFFFFL
                val rootSize = le.getInt(156 + 10).toLong() and 0xFFFFFFFFL
                if (rootLba >= arkEndSec) blocked = true
                val seen = HashSet<Long>()
                fun walk(lba: Long, size: Long) {
                    if (blocked || !seen.add(lba)) return
                    if (lba >= arkEndSec) { blocked = true; return }
                    for (r in readDir(iso, lba, size, joliet)) {
                        if (r.isDir) walk(r.lba, r.size)
                        else if (r.lba >= arkEndSec && r.size > 0) {
                            val e = files.getOrPut(r.lba) { r.size to ArrayList() }
                            if (e.first != r.size) { blocked = true; return }
                            e.second.add(r.recPos)
                        } else if (r.lba >= arkEndSec) {
                            // berkas kosong: lokasi tidak penting, tapi catat agar tidak menunjuk ke area yang ditimpa
                            val e = files.getOrPut(r.lba) { 0L to ArrayList() }
                            e.second.add(r.recPos)
                        }
                    }
                }
                walk(rootLba, rootSize)
            }
            vd++
        }
        if (blocked) return null
        val total = files.values.sumOf { it.first }  // termasuk HDR (batas kasar)
        if (total > minOf(maxOf(arkSize / 2, 1L shl 20), 512L shl 20)) return null
        val hdrPos = trees.mapNotNull { it.recs[hdrName] }.toSet()
        val out = ArrayList<TailFile>()
        try {
            for ((lba, e) in files.entries.sortedBy { it.key }) {
                if (e.second.any { it in hdrPos }) continue  // HDR ditulis ulang oleh commit
                val tmp = java.io.File.createTempFile("gh2tail", ".bin")
                tmp.deleteOnExit()
                java.io.FileOutputStream(tmp).use { fo ->
                    var done = 0L
                    while (done < e.first) {
                        val n = minOf(4L shl 20, e.first - done).toInt()
                        fo.write(iso.read(lba * SS + done, n))
                        done += n
                    }
                }
                out.add(TailFile(e.first, e.second, tmp))
            }
        } catch (t: Throwable) { out.forEach { it.tmp.delete() }; return null }
        progress("Berkas setelah ARK (${out.size} berkas, ${total / 1024} KB) akan dipindah ke ujung image; ARK tidak perlu disalin ulang.")
        return out
    }

    private var lastArk: SubBlob? = null
    private var hdrOverwritten = false
>>>>>>> b2ef2b1 (v0.6.3)

    /** Pandangan ARK; bagian terakhir dapat dipindah ke akhir image supaya bisa diperbesar. */
    fun arkParts(progress: (String) -> Unit, allowRelocate: Boolean = true): List<Blob> {
        val out = ArrayList<Blob>()
        for ((i, n) in arkNames.withIndex()) {
            val (lba, size) = recInfo(n)
            if (i < arkNames.size - 1) { out.add(SubBlob(iso, lba * SS, size)); continue }
            val end = roundUp(lba * SS + size)
<<<<<<< HEAD
<<<<<<< HEAD
            if (end >= roundUp(iso.length())) {
=======
=======
>>>>>>> b2ef2b1 (v0.6.3)
            val (hl, hs) = recInfo(hdrName)
            val hdrIsTail = hl * SS == end && roundUp(hl * SS + hs) >= roundUp(iso.length())
            if (hdrIsTail) {
                // pola dari pemasangan sebelumnya: ARK lalu HDR di ujung image. HDR lama boleh ditimpa
                // (isinya sudah dibaca ke memori; jurnal menyimpan aslinya), HDR baru ditulis di akhir.
                hdrOverwritten = true
                val sb = SubBlob(iso, lba * SS, size)
                lastArk = sb
                out.add(sb)
            } else if (end >= roundUp(iso.length())) {
<<<<<<< HEAD
>>>>>>> 58c523f (v0.6.3)
=======
>>>>>>> b2ef2b1 (v0.6.3)
                val sb = SubBlob(iso, lba * SS, size)
                lastArk = sb
                out.add(sb)
            } else {
                if (!allowRelocate) { out.add(SubBlob(iso, lba * SS, size)); continue }
<<<<<<< HEAD
=======
                val tl = planTail(lba, size, progress)
                if (tl != null) {
                    tail = tl
                    // HDR yang berada di setelah ARK ikut dipindah/ditulis ulang oleh commit
                    val hdrLba = recInfo(hdrName).first
                    if (hdrLba * SS >= end) hdrOverwritten = true
                    val sb = SubBlob(iso, lba * SS, size)
                    lastArk = sb
                    out.add(sb)
                    continue
                }
>>>>>>> b2ef2b1 (v0.6.3)
                // pindahkan ke akhir image
                progress("ARK bukan berkas terakhir di ISO; menyalin ke akhir image (butuh ruang kosong sebesar ARK)…")
                val newStart = roundUp(iso.length())
                var done = 0L
                val chunk = 4 shl 20
                while (done < size) {
                    val n2 = minOf(chunk.toLong(), size - done).toInt()
                    iso.write(newStart + done, iso.read(lba * SS + done, n2))
                    done += n2
                    progress("Menyalin ARK… ${done * 100 / size}%")
                }
                setRec(n, newStart / SS, size)
                val relEnd = roundUp(newStart + size)
                if (iso.length() < relEnd) iso.write(relEnd - 1, byteArrayOf(0))
                setVolumeSectors(relEnd / SS)
                val sb = SubBlob(iso, newStart, size)
                lastArk = sb
                out.add(sb)
            }
        }
        return out
    }

    /** Tulis HDR baru, perbarui ukuran ARK dan ukuran volume. */
    fun commit(hdr: ByteArray, lastArkSize: Long) {
        val name = arkNames.last()
        val ark = lastArk ?: throw IllegalStateException("arkParts() belum dipanggil")
        val (lba, _) = recInfo(name)
        setRec(name, lba, lastArkSize)
<<<<<<< HEAD
<<<<<<< HEAD
        var end = roundUp(maxOf(iso.length(), lba * SS + lastArkSize))
        val (hl, hs) = recInfo(hdrName)
        if (roundUp(hdr.size.toLong()) <= roundUp(hs)) {
            iso.write(hl * SS, hdr)
            setRec(hdrName, hl, hdr.size.toLong())
        } else {
            iso.write(end, hdr)
            setRec(hdrName, end / SS, hdr.size.toLong())
            end += roundUp(hdr.size.toLong())
=======
        val arkEnd = roundUp(lba * SS + lastArkSize)
        var end = roundUp(maxOf(iso.length(), arkEnd))
=======
        val arkEnd = roundUp(lba * SS + lastArkSize)
        var end = roundUp(maxOf(iso.length(), arkEnd))
        var cursor = arkEnd
>>>>>>> b2ef2b1 (v0.6.3)
        val (hl, hs) = recInfo(hdrName)
        if (!hdrOverwritten && roundUp(hdr.size.toLong()) <= roundUp(hs)) {
            iso.write(hl * SS, hdr)
            setRec(hdrName, hl, hdr.size.toLong())
        } else {
            val pos = if (hdrOverwritten) arkEnd else end
            iso.write(pos, hdr)
            setRec(hdrName, pos / SS, hdr.size.toLong())
<<<<<<< HEAD
            end = maxOf(end, pos + roundUp(hdr.size.toLong()))
>>>>>>> 58c523f (v0.6.3)
        }
=======
            cursor = pos + roundUp(hdr.size.toLong())
            end = maxOf(end, cursor)
        }
        // berkas yang tadinya di setelah ARK ditulis ulang tepat setelah ARK/HDR baru
        for (tf in tail) {
            val at = cursor
            java.io.FileInputStream(tf.tmp).use { fi ->
                val buf = ByteArray(4 shl 20)
                var done = 0L
                while (done < tf.size) {
                    val n = fi.read(buf, 0, minOf(buf.size.toLong(), tf.size - done).toInt())
                    if (n <= 0) break
                    iso.write(at + done, if (n == buf.size) buf else buf.copyOf(n))
                    done += n
                }
            }
            for (rp in tf.recs) setRecAt(rp, at / SS)
            cursor = at + roundUp(tf.size)
            tf.tmp.delete()
        }
        tail = emptyList()
        end = maxOf(end, cursor)
>>>>>>> b2ef2b1 (v0.6.3)
        // pad akhir ke kelipatan sektor dan perbarui ukuran volume
        if (iso.length() < end) iso.write(end - 1, byteArrayOf(0))
        setVolumeSectors(end / SS)
        ark.len = lastArkSize
    }

    /** Tulis ukuran volume (dalam sektor) ke semua descriptor PVD/SVD. */
    fun setVolumeSectors(sectors: Long) {
        var vd = 16L
        while (vd < 64) {
            val sec = iso.read(vd * SS, 8)
            if (String(sec, 1, 5, Charsets.ISO_8859_1) != "CD001") break
            val type = sec[0].toInt() and 0xFF
            if (type == 255) break
            if (type == 1 || type == 2) {
                val b = ByteBuffer.allocate(8)
                b.order(ByteOrder.LITTLE_ENDIAN).putInt(0, sectors.toInt())
                b.order(ByteOrder.BIG_ENDIAN).putInt(4, sectors.toInt())
                iso.write(vd * SS + 80, b.array())
            }
            vd++
        }
    }

    private fun volumeSectors(): Long {
        val sec = iso.read(16L * SS, 88)
        return ByteBuffer.wrap(sec, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    /** Periksa konsistensi; kembalikan daftar masalah (kosong = baik). */
    fun verify(expectPaths: List<String>): List<String> {
        val problems = ArrayList<String>()
        val len = iso.length()
        val need = roundUp(len) / SS
        val vol = volumeSectors()
        if (vol != need) problems.add("Ukuran volume di PVD = $vol sektor, ukuran file = $need sektor")
        val names = listOf(hdrName) + arkNames
        for (n in names) {
            val (lba, size) = recInfo(n)
            if (lba * SS + size > len) problems.add("$n: extent (${lba * SS + size}) melewati akhir file ($len)")
        }
        try {
            val ark = Ark.open(readHdr(), arkParts({}, false))
            val total = ark.partSizes.sum()
            for ((i, n) in arkNames.withIndex()) {
                val (_, size) = recInfo(n)
                if (ark.partSizes[i] > size) problems.add("$n: ukuran di HDR (${ark.partSizes[i]}) > ukuran di ISO ($size)")
            }
            for (e in ark.entries) if (e.offset + e.size > total) problems.add("Entri ${e.path} di luar ARK")
            for (p in expectPaths) {
                val e = ark.find(p)
                if (e == null) problems.add("Entri $p tidak ada di HDR") else ark.read(e)
            }
            val sd = ark.find("config/gen/songs.dtb")
            if (sd == null) problems.add("songs.dtb tidak ada") else Dtb.parseAny(ark.read(sd))
        } catch (e: Exception) {
            problems.add("Gagal membaca ARK/HDR: ${e.message}")
        }
        return problems
    }

    /** Perbaiki ukuran volume agar sama dengan ukuran file (aman bila file bertambah di akhir). */
    fun repairVolumeSize() { setVolumeSectors(roundUp(iso.length()) / SS) }

    fun describe(): String {
        val sb = StringBuilder()
        val len = iso.length()
        sb.append("Ukuran file: $len byte (${roundUp(len) / SS} sektor)\n")
        sb.append("Ukuran volume di PVD: ${volumeSectors()} sektor\n")
        for (n in listOf(hdrName) + arkNames) {
            val (lba, size) = recInfo(n)
            sb.append("$n: sektor $lba, $size byte, berakhir di ${lba * SS + size}\n")
        }
        val p = verify(emptyList())
        if (p.isEmpty()) sb.append("Struktur ISO/HDR/ARK tampak konsisten.\n") else p.forEach { sb.append("• $it\n") }
        return sb.toString()
    }
}

/** Jurnal perubahan ISO: menyimpan isi asli sebelum ditimpa supaya bisa dipulihkan. */
class JournalBlob(private val base: Blob, private val out: java.io.DataOutputStream) : Blob {
    private val origLen = base.length()
    init { out.writeLong(origLen); out.flush() }
    override fun length(): Long = base.length()
    override fun read(pos: Long, len: Int): ByteArray = base.read(pos, len)
    override fun write(pos: Long, data: ByteArray) {
        if (pos < origLen) {
            val n = minOf(data.size.toLong(), origLen - pos).toInt()
            val old = base.read(pos, n)
            out.writeLong(pos); out.writeInt(n); out.write(old); out.flush()
        }
        base.write(pos, data)
    }
    override fun truncate(len: Long) = base.truncate(len)

    companion object {
        /** Terapkan jurnal (urutan terbalik) lalu potong ke ukuran asli. */
        fun restore(base: Blob, inp: java.io.InputStream) {
            val din = java.io.DataInputStream(java.io.BufferedInputStream(inp))
            val origLen = din.readLong()
            class R(val pos: Long, val data: ByteArray)
            val recs = ArrayList<R>()
            try {
                while (true) {
                    val pos = din.readLong()
                    val n = din.readInt()
                    val d = ByteArray(n)
                    din.readFully(d)
                    recs.add(R(pos, d))
                }
            } catch (e: java.io.EOFException) {
            }
            for (r in recs.reversed()) base.write(r.pos, r.data)
            base.truncate(origLen)
        }
    }
}
