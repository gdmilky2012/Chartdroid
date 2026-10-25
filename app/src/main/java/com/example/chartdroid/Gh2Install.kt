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
            BufferedOutputStream(FileOutputStream(outFile), 1 shl 16).use { os ->
                fun emit(l: Float, r: Float) {
                    val a = (l.coerceIn(-1f, 1f) * 32767f).toInt()
                    val b = (r.coerceIn(-1f, 1f) * 32767f).toInt()
                    os.write(a and 0xFF); os.write((a shr 8) and 0xFF)
                    os.write(b and 0xFF); os.write((b shr 8) and 0xFF)
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
                        val ii = codec.dequeueInputBuffer(5000)
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
                    val oi = codec.dequeueOutputBuffer(info, 5000)
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
        val ins = files.map { BufferedInputStream(FileInputStream(it), 1 shl 16) }
        val lens = files.map { it.length() / 4 }
        val total = lens.max()
        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
            val b = ByteArray(4)
            for (f in 0 until total) {
                var l = 0
                var r = 0
                for ((i, s) in ins.withIndex()) {
                    if (f >= lens[i]) continue
                    if (s.read(b) < 4) continue
                    l += (b[0].toInt() and 0xFF) or (b[1].toInt() shl 8)
                    r += (b[2].toInt() and 0xFF) or (b[3].toInt() shl 8)
                }
                l = l.coerceIn(-32768, 32767)
                r = r.coerceIn(-32768, 32767)
                os.write(l and 0xFF); os.write((l shr 8) and 0xFF)
                os.write(r and 0xFF); os.write((r shr 8) and 0xFF)
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

object Gh2Installer {
    class Child(val id: String, val name: String)

    private fun children(ctx: Context, tree: Uri): List<Child> {
        val out = ArrayList<Child>()
        val parent = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(
            parent,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) out.add(Child(c.getString(0), c.getString(1)))
        }
        return out
    }

    private fun readAll(ctx: Context, uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Tidak bisa membaca berkas")

    /**
     * Pasang lagu. tree = folder GEN (berisi MAIN.HDR dan MAIN_0.ARK).
     * Mengembalikan ringkasan teks. Dipanggil dari thread latar.
     */
    fun install(
        ctx: Context, tree: Uri, song: Song, meta: Gh2.Meta,
        leadKey: String, coopKey: String?, audio: List<Uri>,
        log: (String) -> Unit, cancelled: () -> Boolean
    ): String {
        val kids = children(ctx, tree)
        val hdrChild = kids.firstOrNull { it.name.endsWith(".hdr", true) }
            ?: throw IllegalStateException("Folder ini tidak berisi berkas .HDR. Pilih folder GEN (berisi MAIN.HDR dan MAIN_0.ARK).")
        val base = hdrChild.name.substring(0, hdrChild.name.length - 4)
        val arkChildren = kids.filter { it.name.endsWith(".ark", true) && it.name.startsWith(base, true) }
            .sortedBy {
                val n = it.name.substring(base.length).removeSuffix(".ark").removeSuffix(".ARK").trimStart('_')
                n.toIntOrNull() ?: -1
            }
        if (arkChildren.isEmpty()) throw IllegalStateException("Berkas ${base}_0.ARK tidak ditemukan di folder yang sama dengan ${hdrChild.name}")
        val hdrUri = DocumentsContract.buildDocumentUriUsingTree(tree, hdrChild.id)
        val hdrBytes = readAll(ctx, hdrUri)

        // cadangan HDR (cukup, karena data ARK hanya ditambah di akhir)
        val bakName = hdrChild.name + ".bak"
        if (kids.none { it.name == bakName }) {
            val treeDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val bak = DocumentsContract.createDocument(ctx.contentResolver, treeDoc, "application/octet-stream", bakName)
                ?: throw IllegalStateException("Gagal membuat cadangan HDR")
            ctx.contentResolver.openOutputStream(bak, "wt")!!.use { it.write(hdrBytes) }
            log("Cadangan HDR dibuat: $bakName")
        } else log("Cadangan HDR sudah ada ($bakName), tidak ditimpa")

        val blobs = arkChildren.map { SafBlob(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, it.id)) }
        try {
            val ark = Ark.open(hdrBytes, blobs)
            // ukuran ARK terakhir di HDR harus <= panjang berkas
            for ((i, b) in blobs.withIndex()) {
                if (b.length() < ark.partSizes[i]) throw IllegalStateException("Ukuran ${arkChildren[i].name} lebih kecil dari yang tercatat di HDR (berkas rusak/tidak lengkap)")
            }

            // ---- audio ----
            val tmp = ArrayList<File>()
            var audioFrames = 0L
            var mixFile: File? = null
            if (audio.isNotEmpty()) {
                for ((i, u) in audio.withIndex()) {
                    log("Mendekode audio ${i + 1}/${audio.size}…")
                    val f = File(ctx.cacheDir, "gh2_dec_$i.pcm")
                    tmp.add(f)
                    PcmDecoder.decodeToFile(ctx, u, f, cancelled)
                }
                mixFile = File(ctx.cacheDir, "gh2_mix.pcm")
                audioFrames = PcmDecoder.mix(tmp, mixFile)
                tmp.add(mixFile)
            }
            try {
                val audioSec = audioFrames.toDouble() / PcmDecoder.RATE
                log("Menyusun MIDI GH2…")
                val coopIsBass = meta.coop == "bass"
                val mr = Gh2.buildMidi(song, leadKey, coopKey, coopIsBass, audioSec, song.offset.toDouble())
                val totalSec = mr.endSeconds + 5.0
                val shiftSec = mr.padSeconds - song.offset
                val shiftFrames = Math.round(shiftSec * PcmDecoder.RATE)

                log("Mengenkode VGS (PS2 ADPCM)…")
                val vgs = ByteArrayOutputStream()
                val bandSrc: Vag.Src = if (mixFile != null) {
                    val ins = BufferedInputStream(FileInputStream(mixFile), 1 shl 16)
                    val s = Vag.StreamSrc(2, PcmDecoder.RATE, ins, if (shiftFrames < 0) -shiftFrames else 0L)
                    if (shiftFrames > 0) ShiftSrc(s, shiftFrames) else s
                } else Vag.ZeroSrc(2, PcmDecoder.RATE)
                Vag.writeVgs(vgs, listOf(bandSrc, Vag.ZeroSrc(1, 11025)), totalSec)
                val empty = ByteArrayOutputStream()
                Vag.writeVgs(empty, listOf(Vag.ZeroSrc(1, 1024)), totalSec)

                val previewStart = (totalSec * 0.3 * 1000).toInt()
                val entry = Gh2.songEntry(meta, previewStart, previewStart + 30000, (mr.endSeconds * 1000).toInt())
                log("Menulis ke ARK…")
                val files = linkedMapOf(
                    "${meta.symbol}.mid" to mr.bytes,
                    "${meta.symbol}.vgs" to vgs.toByteArray(),
                    "${meta.symbol}_empty.vgs" to empty.toByteArray()
                )
                val warn = Gh2.installInto(ark, meta, entry, mr.scores, files)
                // pastikan data ARK tertulis sebelum HDR
                blobs.forEach { it.flush() }
                log("Menulis HDR…")
                ctx.contentResolver.openOutputStream(hdrUri, "wt")!!.use { it.write(ark.hdrBytes()) }
                val sb = StringBuilder()
                sb.append("Selesai. Lagu '${meta.title}' (simbol ${meta.symbol}) dipasang.\n")
                sb.append("Durasi chart ±${mr.endSeconds.toInt()} dtk, pad awal ${"%.1f".format(mr.padSeconds)} dtk.\n")
                for (w in mr.warnings + warn) sb.append("• $w\n")
                return sb.toString()
            } finally {
                tmp.forEach { it.delete() }
            }
        } finally {
            blobs.forEach { try { it.close() } catch (e: Exception) {} }
        }
    }
}
