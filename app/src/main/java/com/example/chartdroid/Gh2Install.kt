package com.example.chartdroid

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Dekode audio apa pun yang didukung Android menjadi PCM 16-bit stereo 44100 Hz (berkas mentah). */
object PcmDecoder {
    const val RATE = 44100

    /** Mengembalikan jumlah frame yang ditulis. */
    fun decodeToFile(ctx: Context, uri: Uri, outFile: File, cancelled: () -> Boolean = { false }): Long {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { idx = i; fmt = f; break }
            }
            if (idx < 0 || fmt == null) throw IllegalStateException("Tidak ada trek audio")
            ex.selectTrack(idx)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else RATE
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var isFloat = false
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var frames = 0L
            // resampler linear streaming
            var pos = 0.0 // posisi sumber (frame) relatif ke sampel terakhir yang dibaca
            var prevL = 0f
            var prevR = 0f
            var havePrev = false
            var srcIndex = 0L
            var nextOutSrc = 0.0
            FileOutputStream(outFile).use { os ->
                val ob = ByteArray(1 shl 16)
                var op = 0
                fun emit(l: Float, r: Float) {
                    val a = (l.coerceIn(-1f, 1f) * 32767f).toInt()
                    val b = (r.coerceIn(-1f, 1f) * 32767f).toInt()
                    ob[op++] = a.toByte(); ob[op++] = (a shr 8).toByte()
                    ob[op++] = b.toByte(); ob[op++] = (b shr 8).toByte()
                    if (op == ob.size) { os.write(ob, 0, op); op = 0 }
                    frames++
                }
                fun push(l: Float, r: Float) {
                    // sampel sumber ke-srcIndex = (l, r)
                    if (rate == RATE) { emit(l, r); srcIndex++; return }
                    if (!havePrev) { prevL = l; prevR = r; havePrev = true; srcIndex = 1; return }
                    // keluaran dengan posisi sumber nextOutSrc di antara (srcIndex-1) dan srcIndex
                    val step = rate.toDouble() / RATE
                    while (nextOutSrc <= srcIndex.toDouble()) {
                        val t = (nextOutSrc - (srcIndex - 1)).toFloat()
                        emit(prevL + (l - prevL) * t, prevR + (r - prevR) * t)
                        nextOutSrc += step
                    }
                    prevL = l; prevR = r; srcIndex++
                }
                while (!outDone) {
                    if (cancelled()) throw InterruptedException("dibatalkan")
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(2000)
                        if (ii >= 0) {
                            val buf = codec.getInputBuffer(ii)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 2000)
                    if (oi >= 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val b = ob.order(ByteOrder.LITTLE_ENDIAN)
                        if (isFloat) {
                            val fb = b.asFloatBuffer()
                            while (fb.remaining() >= ch) {
                                val l = fb.get()
                                var r = l
                                if (ch >= 2) r = fb.get()
                                for (k in 2 until ch) fb.get()
                                push(l, r)
                            }
                        } else {
                            val sb = b.asShortBuffer()
                            while (sb.remaining() >= ch) {
                                val l = sb.get() / 32768f
                                var r = l
                                if (ch >= 2) r = sb.get() / 32768f
                                for (k in 2 until ch) sb.get()
                                push(l, r)
                            }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val nf = codec.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = nf.containsKey("pcm-encoding") && nf.getInteger("pcm-encoding") == 4
                    }
                }
                if (op > 0) os.write(ob, 0, op)
            }
            return frames
        } finally {
            try { codec?.stop() } catch (e: Exception) {}
            try { codec?.release() } catch (e: Exception) {}
            ex.release()
        }
    }

    /** Campur beberapa berkas PCM (stereo 44100) menjadi satu berkas; panjang = yang terpanjang. */
    fun mix(files: List<File>, out: File): Long {
        if (files.size == 1) { files[0].copyTo(out, true); return out.length() / 4 }
        val ins = files.map { java.io.RandomAccessFile(it, "r") }
        val lens = files.map { it.length() / 4 }
        val total = lens.max()
        val chunk = 1 shl 14 // frame
        val acc = IntArray(chunk * 2)
        val rb = ByteArray(chunk * 4)
        val ob = ByteArray(chunk * 4)
        FileOutputStream(out).use { os ->
            var done = 0L
            while (done < total) {
                val n = minOf(chunk.toLong(), total - done).toInt()
                java.util.Arrays.fill(acc, 0, n * 2, 0)
                for ((i, s) in ins.withIndex()) {
                    val have = minOf(n.toLong(), lens[i] - done).toInt()
                    if (have <= 0) continue
                    s.seek(done * 4)
                    s.readFully(rb, 0, have * 4)
                    for (k in 0 until have * 2) {
                        acc[k] += ((rb[2 * k].toInt() and 0xFF) or (rb[2 * k + 1].toInt() shl 8))
                    }
                }
                for (k in 0 until n * 2) {
                    val v = acc[k].coerceIn(-32768, 32767)
                    ob[2 * k] = v.toByte(); ob[2 * k + 1] = (v shr 8).toByte()
                }
                os.write(ob, 0, n * 4)
                done += n
            }
        }
        ins.forEach { it.close() }
        return total
    }
}

/** Akses acak ke berkas SAF (mode rw). */
class SafBlob(ctx: Context, uri: Uri) : Blob, AutoCloseable {
    private val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "rw")
        ?: throw IllegalStateException("Tidak bisa membuka $uri untuk tulis")
    private val fin = FileInputStream(pfd.fileDescriptor)
    private val fout = FileOutputStream(pfd.fileDescriptor)
    override fun length(): Long = fin.channel.size()
    override fun read(pos: Long, len: Int): ByteArray {
        val bb = ByteBuffer.allocate(len)
        var p = pos
        while (bb.hasRemaining()) {
            val n = fin.channel.read(bb, p)
            if (n < 0) throw IllegalStateException("Akhir berkas ARK terlalu cepat")
            p += n.toLong()
        }
        return bb.array()
    }
    override fun write(pos: Long, data: ByteArray) {
        val bb = ByteBuffer.wrap(data)
        var p = pos
        while (bb.hasRemaining()) p += fout.channel.write(bb, p).toLong()
    }
    override fun truncate(len: Long) { fout.channel.truncate(len) }
    fun flush() { try { fout.channel.force(true) } catch (e: Exception) {} }
    override fun close() { flush(); try { fin.close() } catch (e: Exception) {}; try { fout.close() } catch (e: Exception) {}; try { pfd.close() } catch (e: Exception) {} }
}

private class ShiftSrc(private val inner: Vag.Src, private var leadFrames: Long) : Vag.Src(inner.channels, inner.rate) {
    override fun read(buf: ShortArray, frames: Int) {
        if (leadFrames <= 0) { inner.read(buf, frames); return }
        if (leadFrames >= frames) {
            java.util.Arrays.fill(buf, 0, frames * channels, 0)
            leadFrames -= frames
            return
        }
        val z = leadFrames.toInt()
        val tmp = ShortArray(frames * channels)
        inner.read(tmp, frames - z)
        java.util.Arrays.fill(buf, 0, frames * channels, 0)
        System.arraycopy(tmp, 0, buf, z * channels, (frames - z) * channels)
        leadFrames = 0
    }
}

class Gh2Job(val label: String, val song: Song, val meta: Gh2.Meta, val lead: String, val coop: String?, val audio: List<Uri>)

object Gh2Installer {
    class Child(val id: String, val name: String, val isDir: Boolean)

    fun children(ctx: Context, tree: Uri, parentDocId: String? = null): List<Child> {
        val out = ArrayList<Child>()
        val parent = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId ?: DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(
            parent,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) out.add(Child(c.getString(0), c.getString(1), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
        }
        return out
    }

    private fun readAll(ctx: Context, uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Tidak bisa membaca berkas")

    class Prepared(val item: Gh2.Item, val files: Map<String, ByteArray>, val warnings: List<String>)

    /** Proses satu lagu (CPU/IO berat, aman dijalankan paralel): hasilkan data tanpa menyentuh ARK. */
    private fun prepareJob(ctx: Context, idx: Int, job: Gh2Job, log: (String) -> Unit, cancelled: () -> Boolean): Prepared {
        val tmp = ArrayList<File>()
        try {
            var audioFrames = 0L
            var mixFile: File? = null
            if (job.audio.isNotEmpty()) {
                log("${job.label}: mendekode ${job.audio.size} audio…")
                val files = job.audio.indices.map { File(ctx.cacheDir, "gh2_dec_${idx}_$it.pcm") }
                tmp.addAll(files)
                val errs = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
                val threads = ArrayList<Thread>()
                for (i in job.audio.indices) {
                    val u = job.audio[i]
                    val th = Thread {
                        try { PcmDecoder.decodeToFile(ctx, u, files[i], cancelled) } catch (e: Throwable) { errs.add(e) }
                    }
                    th.start(); threads.add(th)
                }
                threads.forEach { it.join() }
                if (errs.isNotEmpty()) throw errs[0]
                mixFile = File(ctx.cacheDir, "gh2_mix_$idx.pcm")
                audioFrames = PcmDecoder.mix(files, mixFile)
                tmp.add(mixFile)
            }
            val meta = job.meta
            val audioSec = audioFrames.toDouble() / PcmDecoder.RATE
            val mr = Gh2.buildMidi(job.song, job.lead, job.coop, meta.coop == "bass", audioSec, job.song.offset.toDouble())
            val totalSec = mr.endSeconds + 5.0
            val shiftFrames = Math.round((mr.padSeconds - job.song.offset) * PcmDecoder.RATE)
            log("${job.label}: mengenkode audio PS2…")
            val vgs = ByteArrayOutputStream(1 shl 22)
            var ins: BufferedInputStream? = null
            try {
                val bandSrc: Vag.Src = if (mixFile != null) {
                    ins = BufferedInputStream(FileInputStream(mixFile), 1 shl 18)
                    val s = Vag.StreamSrc(2, PcmDecoder.RATE, ins, if (shiftFrames < 0) -shiftFrames else 0L)
                    if (shiftFrames > 0) ShiftSrc(s, shiftFrames) else s
                } else Vag.ZeroSrc(2, PcmDecoder.RATE)
                Vag.writeVgs(vgs, listOf(bandSrc, Vag.ZeroSrc(1, 11025)), totalSec)
            } finally {
                try { ins?.close() } catch (e: Exception) {}
            }
            val empty = ByteArrayOutputStream()
            Vag.writeVgs(empty, listOf(Vag.ZeroSrc(1, 1024)), totalSec)
            val previewStart = (totalSec * 0.3 * 1000).toInt()
            val entry = Gh2.songEntry(meta, previewStart, previewStart + 30000, (mr.endSeconds * 1000).toInt())
            val files = linkedMapOf(
                "${meta.symbol}.mid" to mr.bytes,
                "${meta.symbol}.vgs" to vgs.toByteArray(),
                "${meta.symbol}_empty.vgs" to empty.toByteArray()
            )
            return Prepared(Gh2.Item(meta, entry, mr.scores), files, mr.warnings.map { "${job.label}: $it" })
        } finally {
            tmp.forEach { it.delete() }
        }
    }

    /**
     * Pasang beberapa lagu sekaligus. target = folder GEN (tree) atau berkas ISO.
     * Dipanggil dari thread latar.
     */
    fun installMany(
        ctx: Context, target: Uri, isIso: Boolean, jobs: List<Gh2Job>,
        log: (String) -> Unit, cancelled: () -> Boolean
    ): String {
        val closers = ArrayList<SafBlob>()
        var journalOut: java.io.DataOutputStream? = null
        var journalFile: File? = null
        var isoRaw: SafBlob? = null
        var isoGen: IsoGen? = null
        try {
            val ark: Ark
            val commit: (Ark) -> Unit
            if (isIso) {
                log("Membuka ISO…")
                val raw = SafBlob(ctx, target)
                closers.add(raw)
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                val jf = File(dir, "gh2_iso_journal_${System.currentTimeMillis()}.bin")
                val jos = java.io.DataOutputStream(FileOutputStream(jf))
                journalOut = jos
                journalFile = jf
                val sb = JournalBlob(raw, jos)
                isoRaw = raw
                val g = IsoGen.open(sb)
                val hdr = g.readHdr()
                val parts = g.arkParts(log)
                ark = Ark.open(hdr, parts)
                commit = { a ->
                    raw.flush()
                    g.commit(a.hdrBytes(), a.partSizes.last())
                    raw.flush()
                }
                isoGen = g
            } else {
                val kids = children(ctx, target)
                val hdrChild = kids.firstOrNull { it.name.endsWith(".hdr", true) }
                    ?: throw IllegalStateException("Folder ini tidak berisi berkas .HDR. Pilih folder GEN (berisi MAIN.HDR dan MAIN_0.ARK).")
                val base = hdrChild.name.substring(0, hdrChild.name.length - 4)
                val arkChildren = kids.filter { it.name.endsWith(".ark", true) && it.name.startsWith(base, true) }
                    .sortedBy { (it.name.substring(base.length).dropLast(4).trimStart('_')).toIntOrNull() ?: -1 }
                if (arkChildren.isEmpty()) throw IllegalStateException("Berkas ${base}_0.ARK tidak ditemukan di folder yang sama dengan ${hdrChild.name}")
                val hdrUri = DocumentsContract.buildDocumentUriUsingTree(target, hdrChild.id)
                val hdrBytes = readAll(ctx, hdrUri)
                val bakName = hdrChild.name + ".bak"
                if (kids.none { it.name == bakName }) {
                    val treeDoc = DocumentsContract.buildDocumentUriUsingTree(target, DocumentsContract.getTreeDocumentId(target))
                    val bak = DocumentsContract.createDocument(ctx.contentResolver, treeDoc, "application/octet-stream", bakName)
                        ?: throw IllegalStateException("Gagal membuat cadangan HDR")
                    ctx.contentResolver.openOutputStream(bak, "wt")!!.use { it.write(hdrBytes) }
                    log("Cadangan HDR dibuat: $bakName")
                }
                val blobs = arkChildren.map { SafBlob(ctx, DocumentsContract.buildDocumentUriUsingTree(target, it.id)) }
                closers.addAll(blobs)
                ark = Ark.open(hdrBytes, blobs)
                for ((i, b) in blobs.withIndex()) {
                    if (b.length() < ark.partSizes[i]) throw IllegalStateException("Ukuran ${arkChildren[i].name} lebih kecil dari yang tercatat di HDR (berkas rusak/tidak lengkap)")
                }
                commit = { a ->
                    blobs.forEach { it.flush() }
                    ctx.contentResolver.openOutputStream(hdrUri, "wt")!!.use { it.write(a.hdrBytes()) }
                }
            }
            val items = ArrayList<Gh2.Item>()
            val warns = ArrayList<String>()
            val failed = ArrayList<String>()
            val pool = minOf(3, maxOf(1, Runtime.getRuntime().availableProcessors() - 1))
            val exec = java.util.concurrent.Executors.newFixedThreadPool(pool)
            val queue = java.util.concurrent.LinkedBlockingQueue<Pair<Int, java.util.concurrent.Future<Prepared>?>>()
            val gate = java.util.concurrent.Semaphore(pool + 1)
            val producer = Thread {
                try {
                    for ((i, job) in jobs.withIndex()) {
                        if (cancelled()) break
                        gate.acquire()
                        queue.put(i to exec.submit<Prepared> {
                            log("[${i + 1}/${jobs.size}] ${job.label}")
                            prepareJob(ctx, i, job, { m -> log("[${i + 1}/${jobs.size}] $m") }, cancelled)
                        })
                    }
                } catch (e: InterruptedException) {
                }
                queue.put(-1 to null)
            }
            producer.start()
            try {
                while (true) {
                    val (i, fut) = queue.take()
                    if (fut == null) break
                    val job = jobs[i]
                    try {
                        val p = fut.get()
                        log("[${i + 1}/${jobs.size}] ${job.label}: menulis ke ${if (isIso) "ISO" else "ARK"}…")
                        for ((name, data) in p.files) ark.put("songs/${p.item.meta.symbol}/$name", data)
                        items.add(p.item)
                        warns.addAll(p.warnings)
                    } catch (e: java.util.concurrent.ExecutionException) {
                        val c = e.cause ?: e
                        failed.add("${job.label}: ${if (c is InterruptedException) "dibatalkan" else (c.message ?: c.javaClass.simpleName)}")
                    } finally {
                        gate.release()
                    }
                }
            } finally {
                producer.interrupt()
                exec.shutdownNow()
            }
            if (items.isEmpty()) throw IllegalStateException("Tidak ada lagu yang berhasil diproses.\n" + failed.joinToString("\n"))
            log("Memperbarui daftar lagu (DTB)…")
            warns.addAll(Gh2.installDtbs(ark, items))
            log("Menulis HDR…")
            commit(ark)
            val gg = isoGen
            if (gg != null) {
                log("Memeriksa ISO hasil…")
                val problems = gg.verify(items.map { "songs/${it.meta.symbol}/${it.meta.symbol}.mid" })
                if (problems.isNotEmpty()) throw IllegalStateException("Verifikasi ISO gagal:\n" + problems.joinToString("\n") { "• $it" })
            }
            val sb = StringBuilder()
            sb.append("Selesai: ${items.size} lagu dipasang").append(if (failed.isNotEmpty()) ", ${failed.size} gagal/dilewati" else "").append(".\n")
            for (it in items) sb.append("✓ ${it.meta.title} (${it.meta.symbol})\n")
            for (f in failed) sb.append("✗ $f\n")
            for (w in warns.distinct()) sb.append("• $w\n")
            return sb.toString()
        } catch (e: Throwable) {
            val jf = journalFile
            val raw = isoRaw
            if (jf != null && raw != null) {
                try {
                    journalOut?.close()
                    jf.inputStream().use { JournalBlob.restore(raw, it) }
                    raw.flush()
                } catch (x: Throwable) {
                    throw IllegalStateException((e.message ?: e.javaClass.simpleName) + "\n(PERINGATAN: pemulihan otomatis ISO gagal: ${x.message}. Jurnal: ${jf.absolutePath})")
                }
                throw IllegalStateException((e.message ?: e.javaClass.simpleName) + "\nISO sudah dikembalikan ke kondisi semula.")
            }
            throw e
        } finally {
            try { journalOut?.close() } catch (e: Exception) {}
            closers.forEach { try { it.close() } catch (e: Exception) {} }
        }
    }

    /** Daftar jurnal pemulihan ISO (terbaru dulu). */
    /**
     * Uji isolasi layar hitam: lakukan HANYA operasi wadah (ARK/HDR dipindah & ditulis ulang) tanpa menambah lagu.
     * withDtb: tulis juga 4 DTB penting apa adanya (isi tidak berubah). Jurnal disimpan sehingga bisa dipulihkan.
     */
    fun containerTest(ctx: Context, iso: Uri, withDtb: Boolean): String {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val jf = File(dir, "gh2_iso_journal_${System.currentTimeMillis()}.bin")
        val jos = java.io.DataOutputStream(FileOutputStream(jf))
        val log = StringBuilder()
        SafBlob(ctx, iso).use { raw ->
            try {
                val g = IsoGen.open(JournalBlob(raw, jos))
                val parts = g.arkParts({ log.append(it).append('\n') })
                val ark = Ark.open(g.readHdr(), parts)
                if (withDtb) {
                    for (p in listOf("config/gen/songs.dtb", "config/gen/coop_max_scores.dtb", "config/gen/store.dtb", "ui/eng/gen/locale.dtb")) {
                        val e = ark.find(p) ?: continue
                        ark.put(p, Dtb.writeAny(Dtb.parseAny(ark.read(e))))
                    }
                }
                g.commit(ark.hdrBytes(), ark.partSizes.last())
                raw.flush()
                jos.close()
                return "Selesai (" + (if (withDtb) "wadah + DTB apa adanya" else "wadah saja") + "). Tidak ada lagu ditambahkan.\n" + log + "\n" + g.describe()
            } catch (t: Throwable) {
                try { jos.close() } catch (e: Exception) {}
                try { jf.inputStream().use { JournalBlob.restore(raw, it) } } catch (e: Exception) {}
                throw t
            }
        }
    }

    fun journals(ctx: Context): List<File> {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return (dir.listFiles { f -> f.name.startsWith("gh2_iso_journal_") } ?: emptyArray<File>()).sortedByDescending { it.name }
    }

    fun restoreIso(ctx: Context, iso: Uri, journal: File) {
        SafBlob(ctx, iso).use { b ->
            journal.inputStream().use { JournalBlob.restore(b, it) }
            b.flush()
        }
    }

    fun diagnoseIso(ctx: Context, iso: Uri, repair: Boolean): String {
        SafBlob(ctx, iso).use { b ->
            val g = IsoGen.open(b)
            if (repair) { g.repairVolumeSize(); b.flush() }
            val sb = StringBuilder()
            if (repair) sb.append("Ukuran volume diperbaiki.\n")
            sb.append(g.describe())
            try { sb.append("\n\n").append(dtbReport(ctx, g)) } catch (e: Throwable) { sb.append("\n\nLaporan DTB gagal: ").append(e.message) }
            return sb.toString()
        }
    }

    /** Uji keandalan penulis DTB pada data asli: baca lalu tulis ulang tanpa perubahan, bandingkan byte. Juga simpan salinan DTB. */
    private fun dtbReport(ctx: Context, g: IsoGen): String {
        val ark = Ark.open(g.readHdr(), g.arkParts({ }, false))
        val dir = File(ctx.getExternalFilesDir(null), "gh2_dtb").also { it.mkdirs() }
        val sb = StringBuilder("Uji DTB (baca → tulis ulang tanpa perubahan):\n")
        for (p in listOf("config/gen/songs.dtb", "config/gen/coop_max_scores.dtb", "config/gen/store.dtb", "ui/eng/gen/locale.dtb")) {
            val e = ark.find(p)
            if (e == null) { sb.append("- $p: TIDAK ADA\n"); continue }
            val raw = ark.read(e)
            File(dir, p.replace('/', '_')).writeBytes(raw)
            try {
                val doc = Dtb.parseAny(raw)
                val back = Dtb.writeAny(doc)
                val same = back.contentEquals(raw)
                sb.append("- $p: ${raw.size} B, enkripsi=${doc.crypt}, anak=${doc.root.size}, tulis-ulang ${if (same) "IDENTIK" else "BERBEDA (${back.size} B)"}\n")
            } catch (t: Throwable) { sb.append("- $p: gagal dibaca: ${t.message}\n") }
        }
        sb.append("Salinan DTB asli disimpan di: ${dir.absolutePath}")
        return sb.toString()
    }
}

/** Pindai folder berisi banyak lagu gaya Clone Hero (tiap lagu: notes.chart/.mid + audio + song.ini). */
object Gh2Library {
    class Entry(val name: String, val chartDoc: Gh2Installer.Child, val isMid: Boolean, val ini: Map<String, String>, val audio: List<Uri>)

    private val audioExt = listOf(".ogg", ".opus", ".mp3", ".wav", ".m4a", ".flac")

    fun parseIni(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val l = raw.trim().trimStart('\uFEFF')
            if (l.isEmpty() || l.startsWith("[") || l.startsWith(";")) continue
            val i = l.indexOf('=')
            if (i <= 0) continue
            out[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        return out
    }

    fun scan(ctx: Context, tree: Uri, maxSongs: Int = 500): List<Entry> {
        val out = ArrayList<Entry>()
        fun walk(docId: String, name: String, depth: Int) {
            if (out.size >= maxSongs || depth > 4) return
            val kids = Gh2Installer.children(ctx, tree, docId)
            val chart = kids.firstOrNull { !it.isDir && (it.name.equals("notes.chart", true) || it.name.equals("notes.mid", true)) }
                ?: kids.firstOrNull { !it.isDir && (it.name.endsWith(".chart", true)) }
                ?: kids.firstOrNull { !it.isDir && it.name.endsWith(".mid", true) }
            if (chart != null) {
                val iniChild = kids.firstOrNull { !it.isDir && it.name.equals("song.ini", true) }
                val ini = try {
                    if (iniChild != null) parseIni(String(readAllStatic(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, iniChild.id)), Charsets.UTF_8)) else emptyMap()
                } catch (e: Exception) { emptyMap() }
                val audio = kids.filter { k ->
                    !k.isDir && audioExt.any { k.name.endsWith(it, true) } &&
                        !k.name.startsWith("preview", true) && !k.name.startsWith("crowd", true)
                }.map { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
                out.add(Entry(name, chart, chart.name.endsWith(".mid", true), ini, audio))
                return
            }
            for (k in kids.filter { it.isDir }) walk(k.id, k.name, depth + 1)
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "(folder)", 0)
        return out
    }

    private fun readAllStatic(ctx: Context, uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

    /** Ubah entri menjadi job (parse chart). Mengembalikan job atau alasan lewati. */
    fun toJob(ctx: Context, tree: Uri, e: Entry, usedSymbols: MutableSet<String>, author: String, deluxe: Boolean): Pair<Gh2Job?, String?> {
        return try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.chartDoc.id)
            val bytes = readAllStatic(ctx, uri)
            val song = if (e.isMid) MidiIO.read(bytes) else ChartIO.parse(String(bytes, Charsets.UTF_8).trimStart('\uFEFF'))
            val parts = Gh2.availableParts(song)
            if (parts.isEmpty()) return null to "${e.name}: tidak ada track 5-fret berisi not"
            val lead = if ("Single" in parts) "Single" else parts.first()
            val coopKey = listOf("DoubleRhythm", "DoubleBass", "DoubleGuitar", "Single").firstOrNull { it in parts && it != lead }
            val m = Gh2.Meta()
            m.title = e.ini["name"] ?: song.meta["Name"]?.trim('"') ?: e.name
            m.artist = e.ini["artist"] ?: song.meta["Artist"]?.trim('"') ?: ""
            m.album = e.ini["album"] ?: song.meta["Album"]?.trim('"') ?: ""
            m.year = (e.ini["year"] ?: song.meta["Year"]?.trim('"') ?: "").trim(' ', ',')
            m.genre = e.ini["genre"] ?: song.meta["Genre"]?.trim('"') ?: ""
            m.author = e.ini["charter"] ?: e.ini["frets"] ?: author
            m.coop = if (coopKey == "DoubleBass") "bass" else "rhythm"
            m.deluxe = deluxe
            m.guitarRank = e.ini["diff_guitar"]?.toIntOrNull()?.coerceIn(-1, 6) ?: 3
            m.bassRank = (e.ini["diff_bass"] ?: e.ini["diff_rhythm"])?.toIntOrNull()?.coerceIn(-1, 6) ?: 3
            var base = Gh2.sanitizeSymbol(m.title)
            var sym = base
            var n = 2
            while (!usedSymbols.add(sym)) { sym = base.take(21) + n; n++ }
            m.symbol = sym
            if (song.name.isEmpty()) song.name = m.title
            Gh2Job("${m.artist} - ${m.title}".trim(' ', '-'), song, m, lead, coopKey, e.audio) to null
        } catch (ex: Exception) {
            null to "${e.name}: ${ex.message}"
        }
    }
}
