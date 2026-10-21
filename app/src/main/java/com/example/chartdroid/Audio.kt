package com.example.chartdroid

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** Bunyi klik pendek (metronome / clap) yang dibuat sendiri. */
class Clicker(freq: Double, ms: Int, noise: Boolean, private var volume: Float = 1f) {
    private var track: AudioTrack? = null

    init {
        try {
            val rate = 22050
            val n = rate * ms / 1000
            val pcm = ShortArray(n)
            val rnd = java.util.Random(7)
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                val env = exp(-t * (if (noise) 60.0 else 45.0))
                val v = if (noise) (rnd.nextDouble() * 2 - 1) * 0.8 else sin(2 * PI * freq * t)
                pcm[i] = (v * env * 30000).toInt().toShort()
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, n)
            track = t
        } catch (e: Exception) {
            track = null
        }
    }

    fun setVolume(v: Float) {
        volume = v
        try {
            track?.setVolume(v)
        } catch (e: Exception) {
        }
    }

    fun play() {
        val t = track ?: return
        try {
            t.setVolume(volume)
            t.stop()
            t.setPlaybackHeadPosition(0)
            t.play()
        } catch (e: Exception) {
        }
    }

    fun release() {
        try {
            track?.release()
        } catch (e: Exception) {
        }
        track = null
    }
}

/** Puncak amplitudo per 1/perSec detik, untuk gambar waveform. */
class Waveform(val peaks: FloatArray, val perSec: Int) {
    fun at(sec: Double): Float {
        if (sec < 0) return 0f
        val i = (sec * perSec).toInt()
        return if (i < peaks.size) peaks[i] else 0f
    }
}

object WaveformLoader {
    fun load(context: Context, uri: Uri, perSec: Int = 100, cancelled: () -> Boolean = { false }): Waveform? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    idx = i
                    fmt = f
                    break
                }
            }
            if (idx < 0 || fmt == null) return null
            ex.selectTrack(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val out = ArrayList<Float>()
            var curMax = 0f
            var cnt = 0
            var framesPerBucket = max(1, rate / perSec)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (cancelled()) return null
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
                    val buf = codec.getOutputBuffer(oi)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val total = sb.remaining()
                        var i = 0
                        while (i + ch <= total) {
                            var m = 0
                            for (c in 0 until ch) {
                                val v = abs(sb.get(i + c).toInt())
                                if (v > m) m = v
                            }
                            if (m > curMax) curMax = m.toFloat()
                            cnt++
                            if (cnt >= framesPerBucket) {
                                out.add(curMax)
                                curMax = 0f
                                cnt = 0
                            }
                            i += ch
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    framesPerBucket = max(1, rate / perSec)
                }
            }
            var mx = 1f
            for (v in out) if (v > mx) mx = v
            val arr = FloatArray(out.size) { out[it] / mx }
            return Waveform(arr, perSec)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
            }
            ex.release()
        }
    }
}
