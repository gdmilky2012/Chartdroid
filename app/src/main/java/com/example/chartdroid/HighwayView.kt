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
        const val T_ROLL = 9
        private const val PERSP = 2.0
        const val OPEN_COLOR = 0xFFA855F7.toInt()
    }

    interface Listener {
        fun beforeEdit()
        fun onChanged()
        fun onPositionChanged(tick: Double)
        fun onMarkerTap(tool: Int, tick: Int, hit: Int)
        fun onSelectionChanged()
        fun onUserSeek()
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

    // opsi tempat not baru
    var barMode = false
    var placeType = 0       // 0 natural, 1 strum, 2 hopo, 3 tap
    var placeCymbal = true  // drum: yellow/blue/orange dibuat cymbal
    var placeDyn = 0        // 0 normal, 1 accent, 2 ghost
    var placeDouble = false // kick ganda
    var fillMode = false    // alat Star Power membuat drum fill
    var rollDouble = false  // alat roll membuat roll ganda
    var chordSelect = true
    var lefty = false
        set(v) {
            field = v
            invalidate()
        }
    var glow = false
    var loopA = -1
    var loopB = -1
    var extSustain = true
    var view3D = true
        set(v) {
            field = v
            invalidate()
        }
    var showWave = false
        set(v) {
            field = v
            invalidate()
        }
    var waveform: Waveform? = null
        set(v) {
            field = v
            invalidate()
        }
    var drum5Pref = false
        set(v) {
            field = v
            rebuild()
        }

    var lengthTicks = 0
    var curTick = 0.0
        set(v) {
            field = max(0.0, v)
            listener?.onPositionChanged(field)
            invalidate()
        }
    var range = 768.0
        set(v) {
            field = v.coerceIn(96.0, 24000.0)
            invalidate()
        }

    val selection = LinkedHashSet<NoteObj>()

    var tempo = TempoMap(song)
        private set
    var lanes = Lanes(Mode.GUITAR, false)
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
    private var isTouching = false
    private var grab = false
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

    fun instrument(): String = ChartIO.splitTrack(trackKey)?.second ?: "Single"

    fun mode(): Mode = Lanes.modeOf(instrument())

    fun snapTicks(): Int = max(1, song.resolution * 4 / snapDiv)

    fun rebuild() {
        song.normalize()
        tempo = TempoMap(song)
        val notes = track().notes
        val md = mode()
        val five = md == Mode.DRUMS && (drum5Pref || notes.any { it.fret == 5 })
        if (lanes.mode != md || lanes.drum5 != five) {
            lanes = Lanes(md, five)
            layoutGeometry(width, height)
        }
        hopo = if (md == Mode.DRUMS) BooleanArray(notes.size) else Hopo.effective(notes, song.resolution)
        maxSustain = 0
        for (n in notes) if (n.length > maxSustain) maxSustain = n.length
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

    // ---------- geometri ----------
    private val kNorm = 1.0 - 1.0 / (1.0 + PERSP)

    private fun pOfZ(z: Double): Double = if (view3D) (1.0 - 1.0 / (1.0 + PERSP * z)) / kNorm else z

    private fun zOfP(pp: Double): Double {
        if (!view3D) return pp
        val q = pp * kNorm
        return (1.0 / (1.0 - q) - 1.0) / PERSP
    }

    private fun pOfTick(tick: Double): Double = pOfZ((tick - curTick) / range)

    private fun yOfP(pp: Double): Float = (hitY - pp * (hitY - topY)).toFloat()

    private fun scaleOfP(pp: Double): Float = if (view3D) (1.0 - 0.42 * pp).toFloat().coerceAtLeast(0.2f) else 1f

    private fun yOfTick(tick: Double): Float = yOfP(pOfTick(tick))

    private fun pOfY(y: Float): Double = ((hitY - y) / (hitY - topY)).toDouble().coerceAtMost(0.98)

    private fun tickOfY(y: Float): Double = curTick + zOfP(pOfY(y)) * range

    private fun laneX(lane: Float, pp: Double): Float {
        val l = if (lefty) (lanes.count - 1) - lane else lane
        return cx + (l - (lanes.count - 1) / 2f) * laneW * scaleOfP(pp)
    }

    private fun leftEdge(pp: Double): Float = cx - lanes.count * laneW * 0.5f * scaleOfP(pp)

    private fun rightEdge(pp: Double): Float = cx + lanes.count * laneW * 0.5f * scaleOfP(pp)

    private fun laneAt(x: Float, y: Float): Int {
        val pp = pOfY(y)
        val s = scaleOfP(pp)
        val l = ((x - cx) / (laneW * s) + (lanes.count - 1) / 2f + 0.5f)
        val i = floorI(l).coerceIn(0, lanes.count - 1)
        return if (lefty) lanes.count - 1 - i else i
    }

    private fun floorI(v: Float): Int = if (v >= 0) v.toInt() else (v.toInt() - 1)

    fun snapTick(t: Double): Int {
        val s = snapTicks()
        return (Math.round(t / s) * s).toInt().coerceAtLeast(0)
    }

    private fun h(): Float = height.toFloat()

    private fun pBottom(): Double = (hitY - h()) / (hitY - topY).toDouble()

    private fun tickAtBottom(): Double = curTick + zOfP(pBottom().coerceIn(-0.2, 0.0)) * range

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutGeometry(w, h)
    }

    private fun layoutGeometry(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        hitY = h * 0.84f
        topY = h * 0.03f
        cx = (w - 30 * d) / 2f
        val hw = min((w - 30 * d) * 0.62f, h * (if (lanes.count > 5) 1.15f else 0.95f))
        laneW = hw / lanes.count
        stripX = w - 22 * d
    }

    // ---------- gambar ----------
    override fun onDraw(c: Canvas) {
        if (hitY == 0f) layoutGeometry(width, height)
        p.reset()
        p.isAntiAlias = true
        c.drawColor(0xFF15161D.toInt())
        drawHighwayBody(c)
        if (showWave) drawWave(c)
        drawBeatLines(c)
        drawPhraseBands(c)
        drawMarkers(c)
        drawLoop(c)
        drawStarpower(c)
        drawNotes(c)
        drawStrikeLine(c)
        drawGhost(c)
        drawScrub(c)
    }

    private fun drawHighwayBody(c: Canvas) {
        val pBot = pBottom()
        val pTop = 1.0
        path.reset()
        path.moveTo(leftEdge(pBot), yOfP(pBot))
        path.lineTo(leftEdge(pTop), yOfP(pTop))
        path.lineTo(rightEdge(pTop), yOfP(pTop))
        path.lineTo(rightEdge(pBot), yOfP(pBot))
        path.close()
        p.style = Paint.Style.FILL
        p.color = 0xFF0B0C11.toInt()
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * d
        for (i in 0..lanes.count) {
            val lane = i - 0.5f
            p.color = if (i == 0 || i == lanes.count) 0xFF646A82.toInt() else 0xFF262938.toInt()
            c.drawLine(laneX(lane, pBot), yOfP(pBot), laneX(lane, pTop), yOfP(pTop), p)
        }
    }

    private fun drawWave(c: Canvas) {
        val w = waveform ?: return
        val off = song.offset
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f * d
        p.color = 0x663B6FA8
        var y = hitY + 20 * d
        if (y > h()) y = h()
        while (y > topY) {
            val pp = pOfY(y)
            val tick = tickOfY(y)
            val sec = tempo.tickToSec(tick) + off
            val a = w.at(sec)
            if (a > 0.01f) {
                val half = (rightEdge(pp) - leftEdge(pp)) * 0.5f * a
                c.drawLine(cx - half, y, cx + half, y, p)
            }
            y -= 3 * d
        }
    }

    private fun drawBeatLines(c: Canvas) {
        val fromTick = tickAtBottom().toInt().coerceAtLeast(0)
        val toTick = (curTick + range).toInt() + 1
        var lines = BeatGrid.lines(song, fromTick, toTick)
        if (lines.size > 400) lines = lines.filter { it.isMeasure }
        p.textSize = 10f * d
        for (l in lines) {
            val pp = pOfTick(l.tick.toDouble())
            if (pp > 1.01) continue
            val y = yOfP(pp)
            val left = leftEdge(pp)
            val right = rightEdge(pp)
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

    private fun drawPhraseBands(c: Canvas) {
        val lo = tickAtBottom()
        val hi = curTick + range
        p.style = Paint.Style.FILL
        for (ph in track().starpower) {
            if (ph.kind == 2) continue
            val end = ph.tick + max(ph.length, 1)
            if (end < lo || ph.tick > hi) continue
            val p0 = pOfTick(max(ph.tick.toDouble(), lo)).coerceAtLeast(-0.2)
            val p1 = pOfTick(min(end.toDouble(), hi)).coerceAtMost(1.0)
            path.reset()
            path.moveTo(leftEdge(p0), yOfP(p0))
            path.lineTo(rightEdge(p0), yOfP(p0))
            path.lineTo(rightEdge(p1), yOfP(p1))
            path.lineTo(leftEdge(p1), yOfP(p1))
            path.close()
            p.color = if (ph.kind == 64) 0x33A855F7 else 0x3322C55E
            c.drawPath(path, p)
        }
    }

    private fun evColor(t: String): Int = when {
        t.startsWith("section ") -> 0xFF059669.toInt()
        t.startsWith("lyric ") -> 0xFFDB2777.toInt()
        t == "phrase_start" || t == "phrase_end" -> 0xFF0E7490.toInt()
        else -> 0xFF6B7280.toInt()
    }

    private fun drawMarkers(c: Canvas) {
        val lo = tickAtBottom() - song.resolution
        val hi = curTick + range
        p.textAlign = Paint.Align.RIGHT
        p.textSize = 11f * d
        for (b in song.bpms) {
            if (b.tick < lo || b.tick > hi) continue
            val pp = pOfTick(b.tick.toDouble())
            val y = yOfP(pp)
            val x = leftEdge(pp) - 4 * d
            val label = ChartIO.bpmText(b.milli) + (if (b.anchor != null) " ⚓" else "")
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
            val x = leftEdge(pp) - 4 * d
            val label = "${t.num}/${1 shl t.denExp}"
            val tw = p.measureText(label)
            rf.set(x - tw - 10 * d, y + 2 * d, x, y + 18 * d)
            p.style = Paint.Style.FILL
            p.color = 0xFF2563EB.toInt()
            c.drawRoundRect(rf, 4 * d, 4 * d, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawText(label, x - 5 * d, y + 13.5f * d, p)
        }
        p.textAlign = Paint.Align.LEFT
        for (e in song.events) {
            if (e.tick < lo || e.tick > hi) continue
            val y = yOfTick(e.tick.toDouble())
            val label = if (e.text.startsWith("section ")) e.text.substring(8) else e.text
            val tw = p.measureText(label)
            rf.set(4 * d, y - 9 * d, 4 * d + tw + 12 * d, y + 9 * d)
            p.style = Paint.Style.FILL
            p.color = evColor(e.text)
            c.drawRoundRect(rf, 4 * d, 4 * d, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawText(label, 10 * d, y + 4 * d, p)
        }
        for (e in track().events) {
            if (e.tick < lo || e.tick > hi) continue
            val pp = pOfTick(e.tick.toDouble())
            val y = yOfP(pp)
            val x = rightEdge(pp) + 14 * d
            val tw = p.measureText(e.text)
            rf.set(x, y - 9 * d, x + tw + 12 * d, y + 9 * d)
            p.style = Paint.Style.FILL
            p.color = 0xFF7C3AED.toInt()
            c.drawRoundRect(rf, 4 * d, 4 * d, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawText(e.text, x + 6 * d, y + 4 * d, p)
        }
    }

    private fun drawLoop(c: Canvas) {
        val lo = tickAtBottom()
        val hi = curTick + range
        if (loopA >= 0 && loopB > loopA && loopB >= lo && loopA <= hi) {
            val p0 = pOfTick(max(loopA.toDouble(), lo)).coerceAtLeast(-0.2)
            val p1 = pOfTick(min(loopB.toDouble(), hi)).coerceAtMost(1.0)
            path.reset()
            path.moveTo(leftEdge(p0), yOfP(p0))
            path.lineTo(rightEdge(p0), yOfP(p0))
            path.lineTo(rightEdge(p1), yOfP(p1))
            path.lineTo(leftEdge(p1), yOfP(p1))
            path.close()
            p.style = Paint.Style.FILL
            p.color = 0x22FACC15
            c.drawPath(path, p)
        }
        p.textSize = 11f * d
        p.textAlign = Paint.Align.LEFT
        for ((t, nm) in listOf(loopA to "A", loopB to "B")) {
            if (t < 0 || t < lo || t > hi) continue
            val pp = pOfTick(t.toDouble())
            val y = yOfP(pp)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d
            p.color = 0xFFFACC15.toInt()
            c.drawLine(leftEdge(pp), y, rightEdge(pp), y, p)
            p.style = Paint.Style.FILL
            c.drawText(nm, rightEdge(pp) + 4 * d, y + 4 * d, p)
        }
    }

    private fun drawStarpower(c: Canvas) {
        val lo = tickAtBottom()
        val hi = curTick + range
        p.style = Paint.Style.FILL
        for (ph in track().starpower) {
            if (ph.kind != 2) continue
            val end = ph.tick + max(ph.length, 1)
            if (end < lo || ph.tick > hi) continue
            val p0 = pOfTick(max(ph.tick.toDouble(), lo)).coerceAtLeast(-0.2)
            val p1 = pOfTick(min(end.toDouble(), hi)).coerceAtMost(1.0)
            val x0 = rightEdge(p0) + 4 * d * scaleOfP(p0)
            val x1 = rightEdge(p1) + 4 * d * scaleOfP(p1)
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
        for (ph in track().starpower) if (ph.kind == 2 && tick >= ph.tick && tick < ph.tick + max(ph.length, 1)) return true
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
        val lo = tickAtBottom()
        val hi = curTick + range
        var i = lowerBound(notes, (lo - maxSustain).toInt())
        val first = i
        while (i < notes.size && notes[i].tick <= hi) {
            val n = notes[i]
            if (n.length > 0 && n.tick + n.length >= lo) drawSustain(c, n, lo, hi)
            i++
        }
        i = first
        while (i < notes.size && notes[i].tick <= hi) {
            val n = notes[i]
            if (n.tick >= lo - song.resolution / 4) drawNote(c, n, i, 255)
            i++
        }
    }

    private fun drawSustain(c: Canvas, n: NoteObj, lo: Double, hi: Double) {
        val p0 = pOfTick(max(n.tick.toDouble(), lo)).coerceAtLeast(-0.2)
        val p1 = pOfTick(min(n.tick + n.length.toDouble(), hi)).coerceAtMost(1.0)
        if (p1 <= p0) return
        val bar = n.fret == lanes.barFret
        val lane = lanes.laneOf(n.fret)
        if (!bar && lane < 0) return
        p.style = Paint.Style.FILL
        p.color = lanes.color(n.fret)
        p.alpha = if (bar) 90 else 190
        val cxl = if (bar) (lanes.count - 1) / 2f else lane.toFloat()
        val hw = if (bar) laneW * lanes.count * 0.45f else laneW * 0.09f
        path.reset()
        path.moveTo(laneX(cxl, p0) - hw * scaleOfP(p0), yOfP(p0))
        path.lineTo(laneX(cxl, p0) + hw * scaleOfP(p0), yOfP(p0))
        path.lineTo(laneX(cxl, p1) + hw * scaleOfP(p1), yOfP(p1))
        path.lineTo(laneX(cxl, p1) - hw * scaleOfP(p1), yOfP(p1))
        path.close()
        c.drawPath(path, p)
        p.alpha = 255
    }

    private fun drawNote(c: Canvas, n: NoteObj, idx: Int, fa: Int) {
        val pp = pOfTick(n.tick.toDouble())
        if (pp > 1.02) return
        val s = scaleOfP(pp)
        val y = yOfP(pp)
        val passed = n.tick < curTick - 1
        val alpha = if (passed) min(110, fa) else fa
        val r = laneW * 0.40f * s * (if (lanes.count > 5) 0.92f else 1f)
        val sel = selection.contains(n)
        val md = lanes.mode
        val col = lanes.color(n.fret)

        if (n.fret == lanes.barFret) {
            rf.set(leftEdge(pp) + 2 * d, y - r * 0.5f, rightEdge(pp) - 2 * d, y + r * 0.5f)
            p.style = Paint.Style.FILL
            p.color = col
            p.alpha = alpha
            c.drawRoundRect(rf, r * 0.5f, r * 0.5f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d * s
            p.color = if (sel) 0xFFFFFF00.toInt() else 0xFFFFFFFF.toInt()
            p.alpha = alpha
            c.drawRoundRect(rf, r * 0.5f, r * 0.5f, p)
            if (md == Mode.DRUMS && n.dbl) {
                p.style = Paint.Style.FILL
                p.color = 0xFF111111.toInt()
                p.textAlign = Paint.Align.CENTER
                p.textSize = r * 0.8f
                c.drawText("2×", (rf.left + rf.right) / 2, y + r * 0.28f, p)
            }
            p.alpha = 255
            return
        }
        val lane = lanes.laneOf(n.fret)
        if (lane < 0) return
        val x = laneX(lane.toFloat(), pp)
        p.style = Paint.Style.FILL

        if (md == Mode.DRUMS) {
            val rr = if (n.ghost) r * 0.75f else r
            if (n.cymbal) {
                rf.set(x - rr * 1.15f, y - rr * 0.62f, x + rr * 1.15f, y + rr * 0.62f)
                p.color = col
                p.alpha = alpha
                c.drawOval(rf, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.2f * d * s
                p.color = if (n.accent) 0xFFFFFFFF.toInt() else 0xFF0B0B10.toInt()
                p.alpha = alpha
                c.drawOval(rf, p)
                p.style = Paint.Style.FILL
                p.color = 0x66FFFFFF
                c.drawCircle(x, y, rr * 0.22f, p)
            } else {
                p.color = col
                p.alpha = if (n.ghost) min(alpha, 150) else alpha
                c.drawCircle(x, y, rr, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = (if (n.accent) 3.5f else 2f) * d * s
                p.color = if (n.accent) 0xFFFFFFFF.toInt() else 0xFF0B0B10.toInt()
                p.alpha = alpha
                c.drawCircle(x, y, rr, p)
            }
            if (n.accent) {
                p.style = Paint.Style.FILL
                p.color = 0xFFFFFFFF.toInt()
                p.textAlign = Paint.Align.CENTER
                p.textSize = rr * 0.9f
                c.drawText(">", x, y + rr * 0.3f, p)
            }
            if (sel) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f * d
                p.color = 0xFFFFFF00.toInt()
                p.alpha = 255
                c.drawCircle(x, y, rr + 4 * d * s, p)
            }
            p.alpha = 255
            return
        }

        val isH = idx >= 0 && idx < hopo.size && hopo[idx]
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
            p.color = if (isH) 0xFFFFFFFF.toInt() else (if (md == Mode.GHL) 0xFF9CA3AF.toInt() else 0xFF0B0B10.toInt())
            p.alpha = alpha
            c.drawCircle(x, y, r, p)
            p.style = Paint.Style.FILL
            if (isH) {
                p.color = 0xFFFFFFFF.toInt()
                p.alpha = alpha
                c.drawCircle(x, y, r * 0.42f, p)
            } else {
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
        val s = scaleOfP(0.0)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.color = 0xFFFFFFFF.toInt()
        c.drawLine(leftEdge(0.0) - 4 * d, hitY, rightEdge(0.0) + 4 * d, hitY, p)
        if (glow) {
            val notes = track().notes
            var gi = lowerBound(notes, (curTick - song.resolution / 5).toInt())
            while (gi < notes.size && notes[gi].tick <= curTick) {
                val n = notes[gi]
                val age = ((curTick - n.tick) / (song.resolution / 5.0)).coerceIn(0.0, 1.0)
                val ln = lanes.laneOf(n.fret)
                p.style = Paint.Style.FILL
                p.color = lanes.color(n.fret)
                p.alpha = (200 * (1.0 - age)).toInt()
                if (n.fret == lanes.barFret) {
                    rf.set(leftEdge(0.0), hitY - 6 * d, rightEdge(0.0), hitY + 6 * d)
                    c.drawRoundRect(rf, 6 * d, 6 * d, p)
                } else if (ln >= 0) {
                    c.drawCircle(laneX(ln.toFloat(), 0.0), hitY, laneW * 0.55f * (1f + age.toFloat() * 0.5f), p)
                }
                p.alpha = 255
                gi++
            }
        }
        for (i in 0 until lanes.count) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * d
            p.color = lanes.color(lanes.frets[i])
            c.drawCircle(laneX(i.toFloat(), 0.0), hitY, laneW * 0.40f * s * (if (lanes.count > 5) 0.92f else 1f), p)
        }
    }

    private fun placementFlags(n: NoteObj) {
        if (lanes.mode == Mode.DRUMS) {
            if (n.fret in 2..4) n.cymbal = placeCymbal
            if (n.fret in 1..5) {
                n.accent = placeDyn == 1
                n.ghost = placeDyn == 2
            }
            if (n.fret == 0) n.dbl = placeDouble
        }
    }

    private fun drawGhost(c: Canvas) {
        if (!isTouching || scrub || multi) return
        when (tool) {
            T_NOTE -> {
                val t0 = downTick
                var t1 = snapTick(tickOfY(curY))
                if (t1 < t0) t1 = t0
                val fret = if (barMode) lanes.barFret else lanes.fretOf(downLane)
                val len = if (dragging && t1 - t0 >= snapTicks()) t1 - t0 else 0
                val ghost = NoteObj(t0, fret, len)
                placementFlags(ghost)
                if (placeType == 3 && lanes.mode != Mode.DRUMS) ghost.tap = true
                if (len > 0) drawSustain(c, ghost, -1e9, curTick + range)
                drawNote(c, ghost, -1, 150)
            }
            T_STAR, T_ROLL -> {
                val a = min(downTick, snapTick(tickOfY(curY)))
                val b = max(downTick, snapTick(tickOfY(curY)))
                val pa = pOfTick(a.toDouble())
                val pb = pOfTick(b.toDouble())
                p.style = Paint.Style.FILL
                p.color = 0x6622D3EE
                rf.set(rightEdge(pa) + 4 * d, yOfP(pb), rightEdge(pa) + 16 * d, yOfP(pa))
                c.drawRect(rf, p)
            }
            T_SELECT -> if (dragging) {
                if (grab) {
                    val dt = snapTick(tickOfY(curY)) - snapTick(tickOfY(downY))
                    val dl = laneAt(curX, curY) - downLane
                    for (n in selection) {
                        val g = n.copy()
                        g.tick = max(0, n.tick + dt)
                        if (n.fret != lanes.barFret) {
                            val ln = (lanes.laneOf(n.fret) + dl).coerceIn(0, lanes.count - 1)
                            g.fret = lanes.fretOf(ln)
                        }
                        drawNote(c, g, -1, 140)
                    }
                } else {
                    p.style = Paint.Style.FILL
                    p.color = 0x33FFFF00
                    rf.set(min(downX, curX), min(downY, curY), max(downX, curX), max(downY, curY))
                    c.drawRect(rf, p)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 1.5f * d
                    p.color = 0xFFFFFF00.toInt()
                    c.drawRect(rf, p)
                }
            }
            else -> {
                if (tool in T_BPM..T_EVENT) {
                    val y = yOfTick(snapTick(tickOfY(curY)).toDouble())
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 1.5f * d
                    p.color = 0xFFFFFFFF.toInt()
                    c.drawLine(leftEdge(0.3) - laneW, y, rightEdge(0.3) + laneW, y, p)
                }
            }
        }
    }

    private fun drawScrub(c: Canvas) {
        val top = 10 * d
        val bot = h() - 10 * d
        p.style = Paint.Style.FILL
        p.color = 0xFF232635.toInt()
        rf.set(stripX + 6 * d, top, stripX + 12 * d, bot)
        c.drawRoundRect(rf, 3 * d, 3 * d, p)
        val total = max(1, totalTicks()).toDouble()
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
                grab = false
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
                if (tool == T_SELECT) {
                    val n = nearestNote(e.x, e.y, 34 * d)
                    grab = n != null && selection.contains(n)
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                dragging = false
                grab = false
                lastFocusY = focusY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    val fy = focusY(e)
                    val dy = fy - lastFocusY
                    lastFocusY = fy
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
                grab = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                dragging = false
                multi = false
                scrub = false
                grab = false
                invalidate()
            }
        }
        return true
    }

    private fun focusY(e: MotionEvent): Float {
        var sum = 0f
        val n = e.pointerCount
        for (i in 0 until n) sum += e.getY(i)
        return if (n > 0) sum / n else e.y
    }

    private fun scrubTo(y: Float) {
        val top = 10 * d
        val bot = h() - 10 * d
        val f = ((y - top) / (bot - top)).toDouble().coerceIn(0.0, 1.0)
        listener?.onUserSeek()
        curTick = f * totalTicks()
    }

    private fun family(kind: Int) = when (kind) {
        2 -> 0
        64 -> 1
        else -> 2
    }

    private fun finishGesture(x: Float, y: Float) {
        when (tool) {
            T_NOTE -> {
                val tick = downTick
                var end = snapTick(tickOfY(y))
                if (end < tick) end = tick
                val len = if (dragging && end - tick >= snapTicks()) end - tick else 0
                placeNote(tick, if (barMode) lanes.barFret else lanes.fretOf(downLane), len)
            }
            T_SELECT -> finishSelect(x, y)
            T_STAR, T_ROLL -> {
                val kind = if (tool == T_ROLL) (if (rollDouble) 66 else 65) else (if (fillMode) 64 else 2)
                val a = downTick
                val b = snapTick(tickOfY(y))
                val lo = min(a, b)
                val hi = max(a, b)
                val tr = track()
                val fam = family(kind)
                beginEdit()
                if (!dragging || hi - lo < snapTicks()) {
                    val existing = tr.starpower.firstOrNull { family(it.kind) == fam && a >= it.tick && a < it.tick + max(it.length, 1) }
                    if (existing != null) tr.starpower.remove(existing)
                    else tr.starpower.add(Phrase(a, song.resolution * 4, kind))
                } else {
                    tr.starpower.removeAll { family(it.kind) == fam && it.tick < hi && it.tick + max(it.length, 1) > lo }
                    tr.starpower.add(Phrase(lo, hi - lo, kind))
                }
                commitChange()
            }
            T_BPM, T_TS, T_SECTION, T_EVENT -> {
                if (!dragging) {
                    val tick = snapTick(tickOfY(y))
                    listener?.onMarkerTap(tool, tick, findMarker(tool, y))
                }
            }
            else -> {}
        }
    }

    private fun finishSelect(x: Float, y: Float) {
        if (grab && dragging) {
            val dt = snapTick(tickOfY(y)) - snapTick(tickOfY(downY))
            val dl = laneAt(x, y) - downLane
            if (dt != 0 || dl != 0) {
                beginEdit()
                val minTick = selection.minOf { it.tick }
                val dtc = max(dt, -minTick)
                val tr = track()
                for (n in selection) {
                    n.tick += dtc
                    if (n.fret != lanes.barFret) {
                        val ln = (lanes.laneOf(n.fret) + dl).coerceIn(0, lanes.count - 1)
                        n.fret = lanes.fretOf(ln)
                    }
                }
                // singkirkan not yang tertimpa
                val keep = HashSet<String>()
                val dead = ArrayList<NoteObj>()
                for (n in selection) keep.add("${n.tick}:${n.fret}")
                for (n in tr.notes) if (!selection.contains(n) && keep.contains("${n.tick}:${n.fret}")) dead.add(n)
                tr.notes.removeAll(dead.toSet())
                commitChange()
                listener?.onSelectionChanged()
            }
            return
        }
        if (dragging) {
            val r = RectF(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
            selection.clear()
            forEachVisibleNote { n, nx, ny -> if (r.contains(nx, ny)) selection.add(n) }
        } else {
            val n = nearestNote(x, y, 34 * d)
            if (n != null) {
                val group = if (chordSelect) track().notes.filter { it.tick == n.tick } else listOf(n)
                if (selection.contains(n)) selection.removeAll(group.toSet()) else selection.addAll(group)
            } else selection.clear()
        }
        listener?.onSelectionChanged()
        invalidate()
    }

    private fun findMarker(t: Int, y: Float): Int {
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
            val target: NoteObj
            if (same != null) {
                same.length = len
                target = same
            } else {
                val bar = lanes.barFret
                if (lanes.mode != Mode.DRUMS) {
                    val clash = tr.notes.filter { it.tick == tick && ((fret == bar) != (it.fret == bar)) }
                    tr.notes.removeAll(clash.toSet())
                    selection.removeAll(clash.toSet())
                }
                target = NoteObj(tick, fret, len)
                placementFlags(target)
                tr.notes.add(target)
            }
            if (!extSustain && len > 0) for (n in tr.notes) if (n.tick == tick) n.length = len
            selection.clear()
            selection.add(target)
            tr.sortAll()
            if (lanes.mode != Mode.DRUMS && same == null) {
                if (placeType == 3) {
                    target.tap = true
                } else if (placeType == 1 || placeType == 2) {
                    rebuild()
                    applyType(listOf(target), placeType)
                }
            }
        }
        commitChange()
        listener?.onSelectionChanged()
    }

    private inline fun forEachVisibleNote(f: (NoteObj, Float, Float) -> Unit) {
        for (n in track().notes) {
            val pp = pOfTick(n.tick.toDouble())
            if (pp > 1.02 || pp < -0.25) continue
            val x = if (n.fret == lanes.barFret) cx else {
                val ln = lanes.laneOf(n.fret)
                if (ln < 0) continue
                laneX(ln.toFloat(), pp)
            }
            f(n, x, yOfP(pp))
        }
    }

    private fun nearestNote(x: Float, y: Float, maxDist: Float): NoteObj? {
        var best: NoteObj? = null
        var bd = maxDist
        forEachVisibleNote { n, nx, ny ->
            val dx = if (n.fret == lanes.barFret) 0f else nx - x
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

    fun selectNone() {
        selection.clear()
        invalidate()
        listener?.onSelectionChanged()
    }

    fun selectRange(a: Int, b: Int) {
        selection.clear()
        for (n in track().notes) if (n.tick >= a && n.tick < b) selection.add(n)
        invalidate()
        listener?.onSelectionChanged()
    }

    /** Pilih semua not di bagian (section) tempat kursor berada. */
    fun selectSectionAtCursor() {
        val secs = song.events.filter { it.text.startsWith("section ") }.map { it.tick }.sorted()
        val cur = curTick.toInt()
        var start = 0
        var end = Int.MAX_VALUE
        for (t in secs) {
            if (t <= cur) start = t else {
                end = t
                break
            }
        }
        selectRange(start, end)
    }

    private fun applyType(list: List<NoteObj>, type: Int) {
        val notes = track().notes
        val nat = Hopo.natural(notes, song.resolution)
        val index = HashMap<NoteObj, Int>()
        for ((i, n) in notes.withIndex()) index[n] = i
        for (n in list) {
            val i = index[n] ?: continue
            when (type) {
                0 -> {
                    n.tap = false
                    n.forced = false
                }
                1 -> {
                    n.tap = false
                    n.forced = nat[i]
                }
                2 -> {
                    n.tap = false
                    n.forced = !nat[i]
                }
                3 -> {
                    n.tap = true
                    n.forced = false
                }
            }
        }
    }

    /** 0 natural, 1 strum, 2 hopo, 3 tap — untuk not terpilih (gitar/GHL). */
    fun setType(type: Int) {
        if (selection.isEmpty() || lanes.mode == Mode.DRUMS) return
        listener?.beforeEdit()
        applyType(selection.toList(), type)
        commitChange()
    }

    fun toggleCymbal() {
        if (selection.isEmpty() || lanes.mode != Mode.DRUMS) return
        listener?.beforeEdit()
        val cand = selection.filter { it.fret in 2..4 }
        val to = !cand.all { it.cymbal }
        for (n in cand) n.cymbal = to
        commitChange()
    }

    /** 0 normal, 1 accent, 2 ghost */
    fun setDynamics(v: Int) {
        if (selection.isEmpty() || lanes.mode != Mode.DRUMS) return
        listener?.beforeEdit()
        for (n in selection) if (n.fret in 1..5) {
            n.accent = v == 1
            n.ghost = v == 2
        }
        commitChange()
    }

    fun toggleDouble() {
        if (selection.isEmpty() || lanes.mode != Mode.DRUMS) return
        listener?.beforeEdit()
        val cand = selection.filter { it.fret == 0 }
        val to = !cand.all { it.dbl }
        for (n in cand) n.dbl = to
        commitChange()
    }

    fun changeSustain(delta: Int) {
        if (selection.isEmpty()) return
        listener?.beforeEdit()
        val targets = if (extSustain) selection.toList() else selection.flatMap { s -> track().notes.filter { it.tick == s.tick } }
        for (n in targets) n.length = max(0, n.length + delta)
        commitChange()
    }

    fun setSustain(len: Int) {
        if (selection.isEmpty()) return
        listener?.beforeEdit()
        for (n in selection) n.length = max(0, len)
        commitChange()
    }

    fun clearSustain() = setSustain(0)

    fun nudge(deltaTicks: Int) {
        if (selection.isEmpty()) return
        listener?.beforeEdit()
        val minTick = selection.minOf { it.tick }
        val dt = max(deltaTicks, -minTick)
        for (n in selection) n.tick += dt
        commitChange()
    }

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

    fun mirror() {
        if (selection.isEmpty() || lanes.mode == Mode.DRUMS) return
        listener?.beforeEdit()
        val cnt = lanes.count
        for (n in selection) {
            if (n.fret == lanes.barFret) continue
            val ln = lanes.laneOf(n.fret)
            if (ln >= 0) n.fret = lanes.fretOf(cnt - 1 - ln)
        }
        commitChange()
    }

    /** Taruh / hapus not di posisi kursor pada jalur ke-lane (-1 = bar open/kick). */
    fun placeAtCursor(lane: Int) {
        val fret = if (lane < 0) lanes.barFret else lanes.fretOf(lane)
        changedInGesture = false
        placeNote(snapTick(curTick), fret, 0)
        changedInGesture = false
    }

    fun quantize() {
        if (selection.isEmpty()) return
        listener?.beforeEdit()
        for (n in selection) n.tick = snapTick(n.tick.toDouble())
        commitChange()
    }

    fun duplicate() {
        if (selection.isEmpty()) return
        val clip = copySelection()
        val at = selection.maxOf { it.tick } + snapTicks()
        paste(clip, at)
    }

    fun shiftLanes(dir: Int) {
        if (selection.isEmpty()) return
        val cand = selection.filter { it.fret != lanes.barFret }
        for (n in cand) {
            val ln = lanes.laneOf(n.fret) + dir
            if (ln < 0 || ln >= lanes.count) return
        }
        listener?.beforeEdit()
        for (n in cand) n.fret = lanes.fretOf(lanes.laneOf(n.fret) + dir)
        commitChange()
    }

    fun stats(): String {
        val notes = track().notes
        if (notes.isEmpty()) return "Track ini kosong."
        var chords = 0
        var maxChord = 0
        var sust = 0
        var i = 0
        val ticks = ArrayList<Int>()
        while (i < notes.size) {
            var j = i
            while (j < notes.size && notes[j].tick == notes[i].tick) j++
            ticks.add(notes[i].tick)
            if (j - i > 1) chords++
            if (j - i > maxChord) maxChord = j - i
            i = j
        }
        for (n in notes) if (n.length > 0) sust++
        val hop = hopo.count { it }
        val taps = notes.count { it.tap }
        val firstSec = tempo.tickToSec(ticks.first().toDouble())
        val lastSec = tempo.tickToSec(ticks.last().toDouble())
        val dur = max(0.001, lastSec - firstSec)
        var maxNps = 0
        var a = 0
        val secs = ticks.map { tempo.tickToSec(it.toDouble()) }
        for (b in secs.indices) {
            while (secs[b] - secs[a] > 1.0) a++
            if (b - a + 1 > maxNps) maxNps = b - a + 1
        }
        val sb = StringBuilder()
        sb.append("Not: ${notes.size}\n")
        sb.append("Posisi unik (tick): ${ticks.size}\n")
        sb.append("Chord: $chords (terbesar $maxChord not)\n")
        sb.append("Sustain: $sust\n")
        if (lanes.mode != Mode.DRUMS) sb.append("HOPO: $hop   Tap: $taps\n")
        sb.append("Star power: ${track().starpower.count { it.kind == 2 }}\n")
        if (lanes.mode == Mode.DRUMS) {
            sb.append("Fill: ${track().starpower.count { it.kind == 64 }}   Roll: ${track().starpower.count { it.kind >= 65 }}\n")
            sb.append("Cymbal: ${notes.count { it.cymbal }}  Accent: ${notes.count { it.accent }}  Ghost: ${notes.count { it.ghost }}\n")
        }
        sb.append(String.format(java.util.Locale.US, "Durasi chart: %.1f detik\n", dur))
        sb.append(String.format(java.util.Locale.US, "Not/detik rata-rata: %.2f\n", ticks.size / dur))
        sb.append("Not/detik puncak: $maxNps\n")
        return sb.toString()
    }

    /** Pindah kursor ke tick tertentu. */
    fun goToTick(t: Int) {
        listener?.onUserSeek()
        curTick = max(0, t).toDouble()
    }

    /** Potong sustain yang menabrak not berikutnya (sisakan jeda kecil). */
    fun trimSustains(): Int {
        val notes = track().notes
        val gap = max(1, song.resolution / 16)
        var changed = 0
        val ticks = notes.map { it.tick }.distinct().sorted()
        listener?.beforeEdit()
        for (n in notes) {
            if (n.length <= 0) continue
            var nx = -1
            for (t in ticks) if (t > n.tick) {
                nx = t
                break
            }
            if (nx < 0) continue
            if (n.tick + n.length > nx - gap) {
                val nl = nx - gap - n.tick
                n.length = if (nl > 0) nl else 0
                changed++
            }
        }
        commitChange()
        return changed
    }

    /** Hapus sustain yang lebih pendek dari ambang (default 1/3 ketukan). */
    fun removeShortSustains(): Int {
        val th = song.resolution / 3
        var changed = 0
        listener?.beforeEdit()
        for (n in track().notes) if (n.length in 1 until th) {
            n.length = 0
            changed++
        }
        commitChange()
        return changed
    }

    /** kind: 0 = lane (arg), 1 hopo, 2 tap, 3 chord, 4 sustain, 5 cymbal, 6 forced. */
    fun selectBy(kind: Int, arg: Int = 0) {
        selection.clear()
        val notes = track().notes
        val hop = hopo
        for ((i, n) in notes.withIndex()) {
            val ok = when (kind) {
                0 -> if (arg < 0) n.fret == lanes.barFret else lanes.laneOf(n.fret) == arg
                1 -> i < hop.size && hop[i]
                2 -> n.tap
                3 -> notes.count { it.tick == n.tick } > 1
                4 -> n.length > 0
                5 -> n.cymbal
                6 -> n.forced
                else -> false
            }
            if (ok) selection.add(n)
        }
        invalidate()
        listener?.onSelectionChanged()
    }

    fun stepSnap(dir: Int) {
        val s = snapTicks()
        val cur = curTick
        val k = if (dir > 0) Math.floor(cur / s + 1e-6) + 1 else Math.ceil(cur / s - 1e-6) - 1
        curTick = max(0.0, k * s)
    }

    fun moveMeasure(dir: Int) {
        val res = song.resolution
        val from = max(0, curTick.toInt() - res * 24)
        val lines = BeatGrid.lines(song, from, curTick.toInt() + res * 24).filter { it.isMeasure }
        if (dir > 0) {
            val t = lines.firstOrNull { it.tick > curTick + 0.5 }
            curTick = t?.tick?.toDouble() ?: (curTick + res * 4)
        } else {
            val t = lines.lastOrNull { it.tick < curTick - 0.5 }
            curTick = t?.tick?.toDouble() ?: max(0.0, curTick - res * 4)
        }
    }

    fun jumpSection(dir: Int) {
        val secs = song.events.filter { it.text.startsWith("section ") }.map { it.tick }.sorted()
        val t = if (dir > 0) secs.firstOrNull { it > curTick + 0.5 } else secs.lastOrNull { it < curTick - 0.5 }
        if (t != null) curTick = t.toDouble() else toast0()
    }

    private fun toast0() {}

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

    fun snapTickPublic(t: Double): Int = snapTick(t)

    /** Jumlah not (tick berbeda) yang dilewati kursor antara a (tidak termasuk) dan b (termasuk). */
    fun notesBetween(a: Double, b: Double): Int {
        val notes = track().notes
        var i = lowerBound(notes, Math.floor(a).toInt() + 1)
        var last = -1
        var cnt = 0
        while (i < notes.size && notes[i].tick <= b) {
            if (notes[i].tick != last) {
                cnt++
                last = notes[i].tick
            }
            i++
        }
        return cnt
    }

    fun selectionSummary(): String {
        if (selection.isEmpty()) return "-"
        if (selection.size == 1) {
            val n = selection.first()
            val nm = when {
                n.fret == lanes.barFret -> if (lanes.mode == Mode.DRUMS) "Kick" else "Open"
                else -> "Lane ${lanes.laneOf(n.fret) + 1}"
            }
            val fl = ArrayList<String>()
            if (n.tap) fl.add("tap")
            if (n.forced) fl.add("forced")
            if (n.cymbal) fl.add("cymbal")
            if (n.accent) fl.add("accent")
            if (n.ghost) fl.add("ghost")
            if (n.dbl) fl.add("2x")
            return "$nm @${n.tick}\npanjang ${n.length}" + (if (fl.isEmpty()) "" else "\n" + fl.joinToString(","))
        }
        return "${selection.size} not"
    }
}
