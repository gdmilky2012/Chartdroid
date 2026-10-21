package com.example.chartdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Tampilan "highway" ala Moonscraper: not mengalir dari atas ke garis pukul di bawah. */
class HighwayView(context: Context) : View(context) {

    companion object {
        const val T_PAN = 0
        const val T_SELECT = 1
        const val T_ERASE = 2
        const val T_NOTE = 3
        const val T_STAR = 4
        const val T_BPM = 5
        const val T_TS = 6
        const val T_SECTION = 7
        const val T_EVENT = 8

        val FRET_COLORS = intArrayOf(
            0xFF22C55E.toInt(), 0xFFEF4444.toInt(), 0xFFEAB308.toInt(), 0xFF3B82F6.toInt(), 0xFFF97316.toInt()
        )
        const val OPEN_COLOR = 0xFFA855F7.toInt()
        private const val PERSP = 2.0
    }

    interface Listener {
        /** Dipanggil sebelum perubahan pertama pada satu gestur (untuk undo). */
        fun beforeEdit()
        fun onChanged()
        fun onPositionChanged(tick: Double)
        /** Alat BPM/TS/Section/Event diketuk. hit = indeks marker yang kena, atau -1. */
        fun onMarkerTap(tool: Int, tick: Int, hit: Int)
        fun onSelectionChanged()
    }

    var listener: Listener? = null
    var song: Song = Song()
        set(v) {
            field = v
            selection.clear()
            rebuild()
        }
    var trackKey: String = "ExpertSingle"
        set(v) {
            field = v
            selection.clear()
            rebuild()
        }
    var tool = T_PAN
        set(v) {
            field = v
            invalidate()
        }
    var snapDiv = 16
        set(v) {
            field = v
            invalidate()
        }
    var openMode = false
    var lengthTicks = 0
    var curTick = 0.0
        set(v) {
            field = max(0.0, v)
            listener?.onPositionChanged(field)
            invalidate()
        }
    /** Jumlah tick yang terlihat dari garis pukul sampai ujung atas. */
    var range = 768.0
        set(v) {
            field = v.coerceIn(96.0, 24000.0)
            invalidate()
        }

    val selection = LinkedHashSet<NoteObj>()

    var tempo = TempoMap(song)
        private set
    private var hopo = BooleanArray(0)
    private var maxSustain = 0

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()

    private var hitY = 0f
    private var topY = 0f
    private var cx = 0f
    private var laneW = 0f
    private var stripX = 0f

    // keadaan gestur
    private var downX = 0f
    private var downY = 0f
    private var downTick = 0
    private var downLane = 0
    private var curX = 0f
    private var curY = 0f
    private var dragging = false
    private var multi = false
    private var scrub = false
    private var changedInGesture = false
    private var lastTickUnderFinger = 0.0
    private var lastFocusY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            range = range / detector.scaleFactor
            return true
        }
    })

    fun track(): Track = song.track(trackKey)

    fun snapTicks(): Int = max(1, song.resolution * 4 / snapDiv)

    /** Panggil setelah data lagu/track berubah. */
    fun rebuild() {
        song.normalize()
        tempo = TempoMap(song)
        val notes = track().notes
        val n = notes.size
        hopo = BooleanArray(n)
        maxSustain = 0
        val hopoLimit = 65 * song.resolution / 192
        var i = 0
        var prevStart = -1
        var prevEnd = -1
        while (i < n) {
            var j = i
            while (j < n && notes[j].tick == notes[i].tick) j++
            val chord = (j - i) > 1
            if (!chord && prevStart >= 0) {
                val prevChord = (prevEnd - prevStart) > 1
                val prevTick = notes[prevStart].tick
                val same = !prevChord && notes[prevStart].fret == notes[i].fret
                val natural = !same && (notes[i].tick - prevTick) <= hopoLimit
                hopo[i] = if (notes[i].tap) false else (natural != notes[i].forced)
            } else {
                hopo[i] = false
            }
            for (k in i until j) {
                if (notes[k].length > maxSustain) maxSustain = notes[k].length
            }
            prevStart = i
            prevEnd = j
            i = j
        }
        invalidate()
    }

    private fun commitChange() {
        track().sortAll()
        rebuild()
        listener?.onChanged()
    }

    private fun beginEdit() {
        if (!changedInGesture) {
            listener?.beforeEdit()
            changedInGesture = true
        }
    }

    // ---------- geometri perspektif ----------
    private val kNorm = 1.0 - 1.0 / (1.0 + PERSP)

    private fun pOfZ(z: Double): Double = (1.0 - 1.0 / (1.0 + PERSP * z)) / kNorm

    private fun zOfP(pp: Double): Double {
        val q = pp * kNorm
        return (1.0 / (1.0 - q) - 1.0) / PERSP
    }

    private fun pOfTick(tick: Double): Double = pOfZ((tick - curTick) / range)

    private fun yOfP(pp: Double): Float = (hitY - pp * (hitY - topY)).toFloat()

    private fun scaleOfP(pp: Double): Float = (1.0 - 0.42 * pp).toFloat().coerceAtLeast(0.2f)

    private fun yOfTick(tick: Double): Float = yOfP(pOfTick(tick))

    private fun tickOfY(y: Float): Double {
        val pp = ((hitY - y) / (hitY - topY)).toDouble().coerceAtMost(0.98)
        return curTick + zOfP(pp) * range
    }

    private fun laneX(lane: Float, pp: Double): Float = cx + (lane - 2f) * laneW * scaleOfP(pp)

    private fun laneAt(x: Float, y: Float): Int {
        val pp = ((hitY - y) / (hitY - topY)).toDouble().coerceAtMost(0.98)
        val s = scaleOfP(pp)
        val l = ((x - cx) / (laneW * s) + 2.5f)
        return floor(l).coerceIn(0, 4)
    }

    private fun floor(v: Float): Int = if (v >= 0) v.toInt() else (v.toInt() - 1)

    private fun snapTick(t: Double): Int {
        val s = snapTicks()
        return (Math.round(t / s) * s).toInt().coerceAtLeast(0)
    }

    // ---------- ukuran ----------
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutGeometry(w, h)
    }

    private fun layoutGeometry(w: Int, h: Int) {
        hitY = h * 0.84f
        topY = h * 0.03f
        cx = (w - 30 * d) / 2f
        val hw = min((w - 30 * d) * 0.62f, h * 0.95f)
        laneW = hw / 5f
        stripX = w - 22 * d
    }

    // ---------- gambar ----------
    override fun onDraw(c: Canvas) {
        val w = width
        val h = height
        if (hitY == 0f) layoutGeometry(w, h)
        p.reset()
        p.isAntiAlias = true
        c.drawColor(0xFF161821.toInt())

        drawHighwayBody(c)
        drawBeatLines(c)
        drawMarkers(c)
        drawStarpower(c)
        drawNotes(c)
        drawStrikeLine(c)
        drawGhost(c)
        drawScrub(c)
    }

    private fun drawHighwayBody(c: Canvas) {
        val pTop = 1.0
        val pBot = (hitY - h()) / (hitY - topY).toDouble()
        path.reset()
        path.moveTo(laneX(0f, pBot) - laneW * 0.5f * scaleOfP(pBot), yOfP(pBot))
        path.lineTo(laneX(0f, pTop) - laneW * 0.5f * scaleOfP(pTop), yOfP(pTop))
        path.lineTo(laneX(4f, pTop) + laneW * 0.5f * scaleOfP(pTop), yOfP(pTop))
        path.lineTo(laneX(4f, pBot) + laneW * 0.5f * scaleOfP(pBot), yOfP(pBot))
        path.close()
        p.style = Paint.Style.FILL
        p.color = 0xFF0C0D13.toInt()
        c.drawPath(path, p)
        // pembatas jalur
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * d
        for (i in 0..5) {
            val lane = i - 0.5f
            p.color = if (i == 0 || i == 5) 0xFF5A5F73.toInt() else 0xFF2A2D3B.toInt()
            c.drawLine(laneX(lane, pBot), yOfP(pBot), laneX(lane, pTop), yOfP(pTop), p)
        }
    }

    private fun h(): Float = height.toFloat()

    private fun drawBeatLines(c: Canvas) {
        val pBot = (hitY - h()) / (hitY - topY).toDouble()
        val fromTick = (tickOfYForPBot(pBot)).toInt().coerceAtLeast(0)
        val toTick = (curTick + range).toInt() + 1
        var lines = BeatGrid.lines(song, fromTick, toTick)
        if (lines.size > 400) lines = lines.filter { it.isMeasure }
        p.textSize = 10f * d
        for (l in lines) {
            val pp = pOfTick(l.tick.toDouble())
            if (pp > 1.01) continue
            val y = yOfP(pp)
            val s = scaleOfP(pp)
            val left = laneX(0f, pp) - laneW * 0.5f * s
            val right = laneX(4f, pp) + laneW * 0.5f * s
            p.style = Paint.Style.STROKE
            p.strokeWidth = (if (l.isMeasure) 1.6f else 0.8f) * d
            p.color = if (l.isMeasure) 0xFFB5B9CC.toInt() else 0xFF3C4054.toInt()
            c.drawLine(left, y, right, y, p)
            if (l.isMeasure) {
                p.style = Paint.Style.FILL
                p.color = 0xFF9096AD.toInt()
                p.textAlign = Paint.Align.RIGHT
                c.drawText(l.measureNo.toString(), left - 4 * d, y + 3.5f * d, p)
            }
        }
    }

    private fun tickOfYForPBot(pBot: Double): Double = curTick + zOfP(pBot.coerceAtMost(0.0).coerceAtLeast(-0.2)) * range

    private fun drawMarkers(c: Canvas) {
        val pBot = (hitY - h()) / (hitY - topY).toDouble()
        val lo = tickOfYForPBot(pBot) - song.resolution
        val hi = curTick + range
        p.textAlign = Paint.Align.RIGHT
        p.textSize = 11f * d
        // BPM & TS di kiri highway
        for (b in song.bpms) {
            if (b.tick < lo || b.tick > hi) continue
            val pp = pOfTick(b.tick.toDouble())
            val y = yOfP(pp)
            val x = laneX(0f, pp) - laneW * 0.5f * scaleOfP(pp) - 4 * d
            val label = ChartIO.bpmText(b.milli)
            val tw = p.measureText(label)
            rf.set(x - tw - 10 * d, y - 18 * d, x, y - 2 * d)
            p.style = Paint.Style.FILL
            p.color = 0xFFD97706.toInt()
            c.drawRoundRect(rf, 4 * d, 4 * d, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawText(label, x - 5 * d, y - 6.5f * d, p)
        }
        for (t in song.ts) {
            if (t.tick < lo || t.tick > hi) continue
            val pp = pOfTick(t.tick.toDouble())
            val y = yOfP(pp)
            val x = laneX(0f, pp) - laneW * 0.5f * scaleOfP(pp) - 4 * d
            val label = "${t.num}/${1 shl t.denExp}"
            val tw = p.measureText(label)
            rf.set(x - tw - 10 * d, y + 2 * d, x, y + 18 * d)
            p.style = Paint.Style.FILL
            p.color = 0xFF2563EB.toInt()
            c.drawRoundRect(rf, 4 * d, 4 * d, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawText(label, x - 5 * d, y + 13.5f * d, p)
        }
        // Section di tepi kiri layar
        p.textAlign = Paint.Align.LEFT
        for (e in song.events) {
            if (e.tick < lo || e.tick > hi) continue
            val y = yOfTick(e.tick.toDouble())
            val label = if (e.text.startsWith("section ")) e.text.substring(8) else e.text
            val isSection = e.text.startsWith("section ")
            val tw = p.measureText(label)
            rf.set(4 * d, y - 9 * d, 4 * d + tw + 12 * d, y + 9 * d)
            p.style = Paint.Style.FILL
            p.color = if (isSection) 0xFF059669.toInt() else 0xFF6B7280.toInt()
            c.drawRoundRect(rf, 4 * d, 4 * d, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawText(label, 10 * d, y + 4 * d, p)
        }
        // Event track (solo dsb) di kanan highway
        for (e in track().events) {
            if (e.tick < lo || e.tick > hi) continue
            val pp = pOfTick(e.tick.toDouble())
            val y = yOfP(pp)
            val x = laneX(4f, pp) + laneW * 0.5f * scaleOfP(pp) + 14 * d
            val tw = p.measureText(e.text)
            rf.set(x, y - 9 * d, x + tw + 12 * d, y + 9 * d)
            p.style = Paint.Style.FILL
            p.color = 0xFF7C3AED.toInt()
            c.drawRoundRect(rf, 4 * d, 4 * d, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawText(e.text, x + 6 * d, y + 4 * d, p)
        }
    }

    private fun drawStarpower(c: Canvas) {
        val pBot = (hitY - h()) / (hitY - topY).toDouble()
        val lo = tickOfYForPBot(pBot)
        val hi = curTick + range
        p.style = Paint.Style.FILL
        for (ph in track().starpower) {
            val end = ph.tick + max(ph.length, 1)
            if (end < lo || ph.tick > hi) continue
            val p0 = pOfTick(max(ph.tick.toDouble(), lo)).coerceAtLeast(-0.2)
            val p1 = pOfTick(min(end.toDouble(), hi)).coerceAtMost(1.0)
            val x0 = laneX(4f, p0) + laneW * 0.5f * scaleOfP(p0) + 4 * d * scaleOfP(p0)
            val x1 = laneX(4f, p1) + laneW * 0.5f * scaleOfP(p1) + 4 * d * scaleOfP(p1)
            val w0 = 6 * d * scaleOfP(p0)
            val w1 = 6 * d * scaleOfP(p1)
            path.reset()
            path.moveTo(x0, yOfP(p0))
            path.lineTo(x0 + w0, yOfP(p0))
            path.lineTo(x1 + w1, yOfP(p1))
            path.lineTo(x1, yOfP(p1))
            path.close()
            p.color = 0xFF22D3EE.toInt()
            c.drawPath(path, p)
        }
    }

    private fun inStar(tick: Int): Boolean {
        for (ph in track().starpower) if (tick >= ph.tick && tick < ph.tick + max(ph.length, 1)) return true
        return false
    }

    private fun lowerBound(notes: List<NoteObj>, tick: Int): Int {
        var lo = 0
        var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].tick < tick) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun drawNotes(c: Canvas) {
        val notes = track().notes
        if (notes.isEmpty()) return
        val pBot = (hitY - h()) / (hitY - topY).toDouble()
        val lo = tickOfYForPBot(pBot)
        val hi = curTick + range
        var i = lowerBound(notes, (lo - maxSustain).toInt())
        // sustain dulu (di bawah not)
        val first = i
        while (i < notes.size && notes[i].tick <= hi) {
            val n = notes[i]
            if (n.length > 0 && n.tick + n.length >= lo) drawSustain(c, n, lo, hi)
            i++
        }
        i = first
        while (i < notes.size && notes[i].tick <= hi) {
            val n = notes[i]
            if (n.tick >= lo - song.resolution / 4) drawNote(c, n, i)
            i++
        }
    }

    private fun drawSustain(c: Canvas, n: NoteObj, lo: Double, hi: Double) {
        val p0 = pOfTick(max(n.tick.toDouble(), lo)).coerceAtLeast(-0.2)
        val p1 = pOfTick(min(n.tick + n.length.toDouble(), hi)).coerceAtMost(1.0)
        if (p1 <= p0) return
        val col = if (n.fret == 7) OPEN_COLOR else FRET_COLORS[n.fret.coerceIn(0, 4)]
        p.style = Paint.Style.FILL
        p.color = col
        p.alpha = 190
        if (n.fret == 7) {
            path.reset()
            val w = laneW * 2.3f
            path.moveTo(laneX(2f, p0) - w * scaleOfP(p0) / 2f, yOfP(p0))
            path.lineTo(laneX(2f, p0) + w * scaleOfP(p0) / 2f, yOfP(p0))
            path.lineTo(laneX(2f, p1) + w * scaleOfP(p1) / 2f, yOfP(p1))
            path.lineTo(laneX(2f, p1) - w * scaleOfP(p1) / 2f, yOfP(p1))
            path.close()
            p.alpha = 90
            c.drawPath(path, p)
        } else {
            val hw = laneW * 0.09f
            path.reset()
            path.moveTo(laneX(n.fret.toFloat(), p0) - hw * scaleOfP(p0), yOfP(p0))
            path.lineTo(laneX(n.fret.toFloat(), p0) + hw * scaleOfP(p0), yOfP(p0))
            path.lineTo(laneX(n.fret.toFloat(), p1) + hw * scaleOfP(p1), yOfP(p1))
            path.lineTo(laneX(n.fret.toFloat(), p1) - hw * scaleOfP(p1), yOfP(p1))
            path.close()
            c.drawPath(path, p)
        }
        p.alpha = 255
    }

    private fun drawNote(c: Canvas, n: NoteObj, idx: Int) {
        val pp = pOfTick(n.tick.toDouble())
        if (pp > 1.02) return
        val s = scaleOfP(pp)
        val y = yOfP(pp)
        val passed = n.tick < curTick - 1
        val alpha = if (passed) 110 else 255
        val r = laneW * 0.40f * s
        val sel = selection.contains(n)
        if (n.fret == 7) {
            rf.set(laneX(0f, pp) - laneW * 0.45f * s, y - r * 0.55f, laneX(4f, pp) + laneW * 0.45f * s, y + r * 0.55f)
            p.style = Paint.Style.FILL
            p.color = OPEN_COLOR
            p.alpha = alpha
            c.drawRoundRect(rf, r * 0.5f, r * 0.5f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d * s
            p.color = if (sel) 0xFFFFFF00.toInt() else 0xFFE9D5FF.toInt()
            p.alpha = alpha
            c.drawRoundRect(rf, r * 0.5f, r * 0.5f, p)
            p.alpha = 255
            return
        }
        val x = laneX(n.fret.toFloat(), pp)
        val col = FRET_COLORS[n.fret.coerceIn(0, 4)]
        val isH = idx >= 0 && idx < hopo.size && hopo[idx]
        p.style = Paint.Style.FILL
        if (n.tap) {
            p.color = 0xFF1F1B2E.toInt()
            p.alpha = alpha
            c.drawCircle(x, y, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.38f
            p.color = col
            p.alpha = alpha
            c.drawCircle(x, y, r * 0.78f, p)
            p.strokeWidth = 1.5f * d
            p.color = 0xFFC084FC.toInt()
            p.alpha = alpha
            c.drawCircle(x, y, r, p)
        } else {
            p.color = col
            p.alpha = alpha
            c.drawCircle(x, y, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d * s
            p.color = if (isH) 0xFFFFFFFF.toInt() else 0xFF0B0B10.toInt()
            p.alpha = alpha
            c.drawCircle(x, y, r, p)
            if (isH) {
                p.style = Paint.Style.FILL
                p.color = 0xFFFFFFFF.toInt()
                p.alpha = alpha
                c.drawCircle(x, y, r * 0.42f, p)
            } else {
                p.style = Paint.Style.FILL
                p.color = 0x55FFFFFF
                c.drawCircle(x, y, r * 0.35f, p)
            }
        }
        if (inStar(n.tick)) {
            p.style = Paint.Style.FILL
            p.color = 0xFF22D3EE.toInt()
            p.alpha = alpha
            c.drawCircle(x, y - r * 0.55f, r * 0.2f, p)
        }
        if (sel) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * d
            p.color = 0xFFFFFF00.toInt()
            p.alpha = 255
            c.drawCircle(x, y, r + 3 * d * s, p)
        }
        p.alpha = 255
    }

    private fun drawStrikeLine(c: Canvas) {
        val pp = 0.0
        val s = scaleOfP(pp)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.color = 0xFFFFFFFF.toInt()
        c.drawLine(laneX(0f, pp) - laneW * 0.5f, hitY, laneX(4f, pp) + laneW * 0.5f, hitY, p)
        for (i in 0..4) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * d
            p.color = FRET_COLORS[i]
            c.drawCircle(laneX(i.toFloat(), pp), hitY, laneW * 0.40f * s, p)
        }
    }

    private fun drawGhost(c: Canvas) {
        if (!dragging && !isTouching) return
        if (scrub || multi) return
        when (tool) {
            T_NOTE -> {
                val t0 = downTick
                var t1 = snapTick(tickOfY(curY))
                if (t1 < t0) t1 = t0
                val fret = if (openMode) 7 else downLane
                val ghost = NoteObj(t0, fret, if (dragging) t1 - t0 else 0)
                p.alpha = 140
                if (ghost.length > 0) drawSustain(c, ghost, -1e9, curTick + range)
                drawNote(c, ghost, -1)
                p.alpha = 255
            }
            T_STAR -> {
                val a = min(downTick, snapTick(tickOfY(curY)))
                val b = max(downTick, snapTick(tickOfY(curY)))
                val pa = pOfTick(a.toDouble())
                val pb = pOfTick(b.toDouble())
                p.style = Paint.Style.FILL
                p.color = 0x6622D3EE
                rf.set(laneX(4f, pa) + laneW * 0.5f, yOfP(pb), laneX(4f, pa) + laneW * 0.5f + 12 * d, yOfP(pa))
                c.drawRect(rf, p)
            }
            T_SELECT -> if (dragging) {
                p.style = Paint.Style.FILL
                p.color = 0x33FFFF00
                rf.set(min(downX, curX), min(downY, curY), max(downX, curX), max(downY, curY))
                c.drawRect(rf, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * d
                p.color = 0xFFFFFF00.toInt()
                c.drawRect(rf, p)
            }
            else -> {
                if (tool in T_BPM..T_EVENT) {
                    val y = yOfTick(snapTick(tickOfY(curY)).toDouble())
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 1.5f * d
                    p.color = 0xFFFFFFFF.toInt()
                    c.drawLine(laneX(0f, 0.3) - laneW, y, laneX(4f, 0.3) + laneW, y, p)
                }
            }
        }
    }

    private var isTouching = false

    private fun drawScrub(c: Canvas) {
        val top = 10 * d
        val bot = h() - 10 * d
        p.style = Paint.Style.FILL
        p.color = 0xFF232635.toInt()
        rf.set(stripX + 6 * d, top, stripX + 12 * d, bot)
        c.drawRoundRect(rf, 3 * d, 3 * d, p)
        val total = max(1, totalTicks()).toDouble()
        // penanda section
        p.color = 0xFF059669.toInt()
        for (e in song.events) {
            if (!e.text.startsWith("section ")) continue
            val y = (top + (bot - top) * (e.tick / total)).toFloat()
            c.drawRect(stripX + 3 * d, y - 0.8f * d, stripX + 15 * d, y + 0.8f * d, p)
        }
        val y = (top + (bot - top) * (curTick / total).coerceIn(0.0, 1.0)).toFloat()
        p.color = 0xFFE5E7EB.toInt()
        rf.set(stripX, y - 5 * d, stripX + 18 * d, y + 5 * d)
        c.drawRoundRect(rf, 3 * d, 3 * d, p)
    }

    fun totalTicks(): Int {
        var m = lengthTicks
        val n = track().notes
        if (n.isNotEmpty()) m = max(m, n[n.size - 1].tick + n[n.size - 1].length + song.resolution * 8)
        for (b in song.bpms) m = max(m, b.tick)
        for (e in song.events) m = max(m, e.tick)
        return max(m, song.resolution * 64)
    }

    // ---------- sentuhan ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isTouching = true
                downX = e.x
                downY = e.y
                curX = e.x
                curY = e.y
                dragging = false
                multi = false
                changedInGesture = false
                scrub = e.x >= stripX - 4 * d
                if (scrub) {
                    scrubTo(e.y)
                    return true
                }
                downTick = snapTick(tickOfY(e.y))
                downLane = laneAt(e.x, e.y)
                lastTickUnderFinger = tickOfY(e.y)
                if (tool == T_ERASE) eraseAt(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                dragging = false
                lastFocusY = e.focusYSafe()
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    val fy = e.focusYSafe()
                    val dy = fy - lastFocusY
                    lastFocusY = fy
                    // geser kira-kira satu "jalur" per tinggi layar
                    curTick = curTick + dy * (range / (hitY - topY)) * 0.9
                    return true
                }
                if (scrub) {
                    scrubTo(e.y)
                    return true
                }
                curX = e.x
                curY = e.y
                val moved = abs(e.x - downX) > 8 * d || abs(e.y - downY) > 8 * d
                if (moved) dragging = true
                when (tool) {
                    T_PAN -> if (dragging) {
                        val t = tickOfY(e.y)
                        curTick = curTick + (lastTickUnderFinger - t)
                        lastTickUnderFinger = tickOfY(e.y)
                    }
                    T_ERASE -> eraseAt(e.x, e.y)
                    else -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isTouching = false
                if (scrub) {
                    scrub = false
                    return true
                }
                if (!multi) finishGesture(e.x, e.y)
                dragging = false
                multi = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                dragging = false
                multi = false
                scrub = false
                invalidate()
            }
        }
        return true
    }

    private fun MotionEvent.focusYSafe(): Float {
        var sum = 0f
        val n = pointerCount
        for (i in 0 until n) sum += getY(i)
        return if (n > 0) sum / n else y
    }

    private fun scrubTo(y: Float) {
        val top = 10 * d
        val bot = h() - 10 * d
        val f = ((y - top) / (bot - top)).toDouble().coerceIn(0.0, 1.0)
        curTick = f * totalTicks()
    }

    private fun finishGesture(x: Float, y: Float) {
        when (tool) {
            T_NOTE -> {
                val tick = downTick
                var end = snapTick(tickOfY(y))
                if (end < tick) end = tick
                val len = if (dragging && end - tick >= snapTicks()) end - tick else 0
                placeNote(tick, if (openMode) 7 else downLane, len)
            }
            T_SELECT -> {
                if (dragging) {
                    val r = RectF(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                    selection.clear()
                    forEachVisibleNote { n, nx, ny ->
                        if (r.contains(nx, ny)) selection.add(n)
                    }
                } else {
                    val n = nearestNote(x, y, 34 * d)
                    if (n != null) {
                        if (!selection.remove(n)) selection.add(n)
                    } else selection.clear()
                }
                listener?.onSelectionChanged()
            }
            T_STAR -> {
                val a = downTick
                val b = snapTick(tickOfY(y))
                val lo = min(a, b)
                val hi = max(a, b)
                val tr = track()
                if (!dragging || hi - lo < snapTicks()) {
                    val existing = tr.starpower.firstOrNull { a >= it.tick && a < it.tick + max(it.length, 1) }
                    beginEdit()
                    if (existing != null) tr.starpower.remove(existing)
                    else tr.starpower.add(Phrase(a, song.resolution * 4))
                } else {
                    beginEdit()
                    // hapus phrase yang tumpang tindih
                    tr.starpower.removeAll { it.tick < hi && it.tick + max(it.length, 1) > lo }
                    tr.starpower.add(Phrase(lo, hi - lo))
                }
                commitChange()
            }
            T_BPM, T_TS, T_SECTION, T_EVENT -> {
                if (!dragging) {
                    val tick = snapTick(tickOfY(y))
                    listener?.onMarkerTap(tool, tick, findMarker(tool, x, y))
                }
            }
            else -> {}
        }
    }

    private fun findMarker(t: Int, x: Float, y: Float): Int {
        var best = -1
        var bd = 22 * d
        fun test(i: Int, tick: Int) {
            val dd = abs(yOfTick(tick.toDouble()) - y)
            if (dd < bd) {
                bd = dd
                best = i
            }
        }
        when (t) {
            T_BPM -> song.bpms.forEachIndexed { i, b -> test(i, b.tick) }
            T_TS -> song.ts.forEachIndexed { i, b -> test(i, b.tick) }
            T_SECTION -> song.events.forEachIndexed { i, b -> test(i, b.tick) }
            T_EVENT -> track().events.forEachIndexed { i, b -> test(i, b.tick) }
        }
        return best
    }

    private fun placeNote(tick: Int, fret: Int, len: Int) {
        val tr = track()
        val same = tr.notes.firstOrNull { it.tick == tick && it.fret == fret }
        beginEdit()
        if (same != null && len == 0) {
            tr.notes.remove(same)
            selection.remove(same)
        } else {
            if (same != null) {
                same.length = len
                selection.clear()
                selection.add(same)
            } else {
                // not open meniadakan not lain di tick yang sama dan sebaliknya
                val clash = tr.notes.filter { it.tick == tick && ((fret == 7) != (it.fret == 7)) }
                tr.notes.removeAll(clash.toSet())
                selection.removeAll(clash.toSet())
                val n = NoteObj(tick, fret, len)
                // warisi flag tap/forced dari not lain di tick yang sama
                val mate = tr.notes.firstOrNull { it.tick == tick }
                if (mate != null) {
                    n.tap = mate.tap
                    n.forced = mate.forced
                }
                tr.notes.add(n)
                selection.clear()
                selection.add(n)
            }
        }
        commitChange()
        listener?.onSelectionChanged()
    }

    private inline fun forEachVisibleNote(f: (NoteObj, Float, Float) -> Unit) {
        for (n in track().notes) {
            val pp = pOfTick(n.tick.toDouble())
            if (pp > 1.02 || pp < -0.25) continue
            val x = if (n.fret == 7) cx else laneX(n.fret.toFloat(), pp)
            f(n, x, yOfP(pp))
        }
    }

    private fun nearestNote(x: Float, y: Float, maxDist: Float): NoteObj? {
        var best: NoteObj? = null
        var bd = maxDist
        forEachVisibleNote { n, nx, ny ->
            val dx = if (n.fret == 7) 0f else nx - x
            val dist = Math.hypot(dx.toDouble(), (ny - y).toDouble()).toFloat()
            if (dist < bd) {
                bd = dist
                best = n
            }
        }
        return best
    }

    private fun eraseAt(x: Float, y: Float) {
        val n = nearestNote(x, y, 30 * d) ?: return
        beginEdit()
        track().notes.remove(n)
        selection.remove(n)
        commitChange()
    }

    // ---------- operasi untuk Activity ----------
    fun deleteSelection() {
        if (selection.isEmpty()) return
        listener?.beforeEdit()
        track().notes.removeAll(selection.toSet())
        selection.clear()
        commitChange()
        listener?.onSelectionChanged()
    }

    fun selectAll() {
        selection.clear()
        selection.addAll(track().notes)
        invalidate()
        listener?.onSelectionChanged()
    }

    fun toggleForced() {
        if (selection.isEmpty()) return
        listener?.beforeEdit()
        val setTo = !selection.all { it.forced }
        for (n in selection) {
            n.forced = setTo
            if (setTo) n.tap = false
        }
        commitChange()
    }

    fun toggleTap() {
        if (selection.isEmpty()) return
        listener?.beforeEdit()
        val setTo = !selection.all { it.tap }
        for (n in selection) {
            n.tap = setTo
            if (setTo) n.forced = false
        }
        commitChange()
    }

    /** Ubah panjang sustain semua not terpilih sebesar delta (dalam tick); hasil minimal 0. */
    fun changeSustain(delta: Int) {
        if (selection.isEmpty()) return
        listener?.beforeEdit()
        for (n in selection) n.length = max(0, n.length + delta)
        commitChange()
    }

    fun clearSustain() {
        if (selection.isEmpty()) return
        listener?.beforeEdit()
        for (n in selection) n.length = 0
        commitChange()
    }

    fun nudge(deltaTicks: Int) {
        if (selection.isEmpty()) return
        listener?.beforeEdit()
        val minTick = selection.minOf { it.tick }
        val dt = max(deltaTicks, -minTick)
        for (n in selection) n.tick += dt
        commitChange()
    }

    /** Salin not terpilih; kembalikan daftar salinan relatif terhadap tick terkecil. */
    fun copySelection(): List<NoteObj> {
        if (selection.isEmpty()) return emptyList()
        val base = selection.minOf { it.tick }
        return selection.sortedBy { it.tick }.map {
            val c = it.copy()
            c.tick -= base
            c
        }
    }

    fun paste(clip: List<NoteObj>, at: Int) {
        if (clip.isEmpty()) return
        listener?.beforeEdit()
        val tr = track()
        selection.clear()
        for (cn in clip) {
            val n = cn.copy()
            n.tick += at
            tr.notes.removeAll { it.tick == n.tick && it.fret == n.fret }
            tr.notes.add(n)
            selection.add(n)
        }
        commitChange()
        listener?.onSelectionChanged()
    }

    fun stepSnap(dir: Int) {
        val s = snapTicks()
        val cur = curTick
        val k = if (dir > 0) Math.floor(cur / s + 1e-6) + 1 else Math.ceil(cur / s - 1e-6) - 1
        curTick = max(0.0, k * s)
    }

    fun bpmAtCursor(): Int {
        var m = song.bpms.firstOrNull()?.milli ?: 120000
        for (b in song.bpms) if (b.tick <= curTick) m = b.milli
        return m
    }

    fun tsAtCursor(): TsEv {
        var t = song.ts.firstOrNull() ?: TsEv(0, 4, 2)
        for (x in song.ts) if (x.tick <= curTick) t = x
        return t
    }

    fun snapTickPublic(t: Double): Int = snapTick(t)

    fun bpmAtCursorAt(tick: Int): Int {
        var m = song.bpms.firstOrNull()?.milli ?: 120000
        for (b in song.bpms) if (b.tick <= tick) m = b.milli
        return m
    }

    fun tsAtCursorAt(tick: Int): TsEv {
        var t = song.ts.firstOrNull() ?: TsEv(0, 4, 2)
        for (x in song.ts) if (x.tick <= tick) t = x
        return t
    }

    fun roundInt(v: Double): Int = v.roundToInt()
}
