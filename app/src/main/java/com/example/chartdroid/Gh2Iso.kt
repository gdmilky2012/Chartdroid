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

    private var lastArk: SubBlob? = null

    /** Pandangan ARK; bagian terakhir dapat dipindah ke akhir image supaya bisa diperbesar. */
    fun arkParts(progress: (String) -> Unit): List<Blob> {
        val out = ArrayList<Blob>()
        for ((i, n) in arkNames.withIndex()) {
            val (lba, size) = recInfo(n)
            if (i < arkNames.size - 1) { out.add(SubBlob(iso, lba * SS, size)); continue }
            val end = roundUp(lba * SS + size)
            if (end >= roundUp(iso.length())) {
                val sb = SubBlob(iso, lba * SS, size)
                lastArk = sb
                out.add(sb)
            } else {
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
        var end = roundUp(maxOf(iso.length(), lba * SS + lastArkSize))
        val (hl, hs) = recInfo(hdrName)
        if (roundUp(hdr.size.toLong()) <= roundUp(hs)) {
            iso.write(hl * SS, hdr)
            setRec(hdrName, hl, hdr.size.toLong())
        } else {
            iso.write(end, hdr)
            setRec(hdrName, end / SS, hdr.size.toLong())
            end += roundUp(hdr.size.toLong())
        }
        // pad akhir ke kelipatan sektor dan perbarui ukuran volume
        if (iso.length() < end) iso.write(end - 1, byteArrayOf(0))
        val sectors = end / SS
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
        ark.len = lastArkSize
    }
}
