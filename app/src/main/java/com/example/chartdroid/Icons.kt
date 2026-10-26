package com.example.chartdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Ikon vektor buatan sendiri (digambar dengan Canvas, tanpa emoji). */
class IconView(context: Context, var kind: Int, var tint: Int = 0xFFFFFFFF.toInt()) : View(context) {

    companion object {
        const val PAN = 0
        const val CURSOR = 1
        const val ERASER = 2
        const val NOTE = 3
        const val STAR = 4
        const val ROLL = 5
        const val BPM = 6
        const val TS = 7
        const val SECTION = 8
        const val EVENT = 9
        const val PLAY = 10
        const val PAUSE = 11
        const val TO_START = 12
        const val PREV_MEASURE = 13
        const val STEP_BACK = 14
        const val STEP_FWD = 15
        const val NEXT_MEASURE = 16
        const val METRO = 17
        const val CLAP = 18
        const val ZOOM_IN = 19
        const val ZOOM_OUT = 20
        const val PANEL = 21
        const val UNDO = 22
        const val REDO = 23
        const val SAVE = 24
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) * 0.34f
        val cx = w / 2f
        val cy = h / 2f
        p.reset()
        p.isAntiAlias = true
        p.color = tint
        p.strokeWidth = r * 0.22f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND

        fun poly(vararg pts: Float, fill: Boolean = true) {
            path.reset()
            var i = 0
            while (i < pts.size) {
                val x = cx + pts[i] * r
                val y = cy + pts[i + 1] * r
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                i += 2
            }
            path.close()
            p.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            c.drawPath(path, p)
        }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            p.style = Paint.Style.STROKE
            c.drawLine(cx + x1 * r, cy + y1 * r, cx + x2 * r, cy + y2 * r, p)
        }

        when (kind) {
            PAN -> {
                line(-1f, 0f, 1f, 0f)
                line(0f, -1f, 0f, 1f)
                poly(-1.15f, 0f, -0.7f, -0.35f, -0.7f, 0.35f)
                poly(1.15f, 0f, 0.7f, -0.35f, 0.7f, 0.35f)
                poly(0f, -1.15f, -0.35f, -0.7f, 0.35f, -0.7f)
                poly(0f, 1.15f, -0.35f, 0.7f, 0.35f, 0.7f)
            }
            CURSOR -> poly(-0.6f, -0.95f, -0.6f, 0.75f, -0.2f, 0.4f, 0.1f, 1.0f, 0.4f, 0.85f, 0.12f, 0.3f, 0.65f, 0.3f)
            ERASER -> {
                poly(-1f, 0.35f, -0.2f, -0.75f, 1f, 0.05f, 0.2f, 1.1f, fill = false)
                poly(-1f, 0.35f, -0.6f, -0.2f, 0.6f, 0.62f, 0.2f, 1.1f)
            }
            NOTE -> {
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, r * 0.85f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = r * 0.16f
                p.color = 0x44000000
                c.drawCircle(cx, cy, r * 0.5f, p)
            }
            STAR -> {
                path.reset()
                for (i in 0 until 10) {
                    val a = -PI / 2 + i * PI / 5
                    val rr = if (i % 2 == 0) 1.15f else 0.5f
                    val x = cx + (cos(a) * rr * r).toFloat()
                    val y = cy + (sin(a) * rr * r).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                p.style = Paint.Style.FILL
                c.drawPath(path, p)
            }
            ROLL -> {
                p.style = Paint.Style.FILL
                c.drawCircle(cx - r * 0.85f, cy + r * 0.5f, r * 0.38f, p)
                c.drawCircle(cx, cy, r * 0.38f, p)
                c.drawCircle(cx + r * 0.85f, cy - r * 0.5f, r * 0.38f, p)
            }
            BPM -> {
                p.style = Paint.Style.FILL
                rf.set(cx - r * 0.95f, cy + r * 0.2f, cx + r * 0.15f, cy + r * 1.0f)
                c.drawOval(rf, p)
                line(0.1f, 0.6f, 0.1f, -1.0f)
                line(0.1f, -1.0f, 0.8f, -0.5f)
            }
            TS -> {
                p.style = Paint.Style.FILL
                p.textAlign = Paint.Align.CENTER
                p.textSize = r * 1.15f
                c.drawText("4", cx, cy - r * 0.1f, p)
                c.drawText("4", cx, cy + r * 1.05f, p)
                line(-0.8f, 0.15f, 0.8f, 0.15f)
            }
            SECTION -> {
                poly(-0.7f, -1f, 0.7f, -1f, 0.7f, 1f, 0f, 0.45f, -0.7f, 1f)
            }
            EVENT -> {
                line(-0.7f, -1f, -0.7f, 1f)
                poly(-0.7f, -1f, 0.9f, -0.5f, -0.7f, 0f)
            }
            PLAY -> poly(-0.7f, -1f, 1f, 0f, -0.7f, 1f)
            PAUSE -> {
                p.style = Paint.Style.FILL
                rf.set(cx - r * 0.8f, cy - r, cx - r * 0.2f, cy + r)
                c.drawRect(rf, p)
                rf.set(cx + r * 0.2f, cy - r, cx + r * 0.8f, cy + r)
                c.drawRect(rf, p)
            }
            TO_START -> {
                line(-0.9f, -0.9f, -0.9f, 0.9f)
                poly(0.9f, -0.9f, -0.5f, 0f, 0.9f, 0.9f)
            }
            STEP_BACK -> poly(0.7f, -0.9f, -0.8f, 0f, 0.7f, 0.9f)
            STEP_FWD -> poly(-0.7f, -0.9f, 0.8f, 0f, -0.7f, 0.9f)
            PREV_MEASURE -> {
                poly(0.15f, -0.9f, -0.95f, 0f, 0.15f, 0.9f)
                poly(1.05f, -0.9f, -0.05f, 0f, 1.05f, 0.9f)
            }
            NEXT_MEASURE -> {
                poly(-0.15f, -0.9f, 0.95f, 0f, -0.15f, 0.9f)
                poly(-1.05f, -0.9f, 0.05f, 0f, -1.05f, 0.9f)
            }
            METRO -> {
                poly(-0.55f, 1f, 0.55f, 1f, 0.3f, -1f, -0.3f, -1f, fill = false)
                line(0f, 0.7f, 0.5f, -0.6f)
            }
            CLAP -> {
                p.style = Paint.Style.STROKE
                c.drawCircle(cx, cy, r * 0.45f, p)
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    c.drawLine(
                        cx + (cos(a) * r * 0.8f).toFloat(), cy + (sin(a) * r * 0.8f).toFloat(),
                        cx + (cos(a) * r * 1.15f).toFloat(), cy + (sin(a) * r * 1.15f).toFloat(), p
                    )
                }
            }
            ZOOM_IN, ZOOM_OUT -> {
                p.style = Paint.Style.STROKE
                c.drawCircle(cx - r * 0.15f, cy - r * 0.15f, r * 0.72f, p)
                line(0.4f, 0.4f, 1.05f, 1.05f)
                line(-0.55f, -0.15f, 0.25f, -0.15f)
                if (kind == ZOOM_IN) line(-0.15f, -0.55f, -0.15f, 0.25f)
            }
            PANEL -> {
                p.style = Paint.Style.STROKE
                rf.set(cx - r, cy - r * 0.8f, cx + r, cy + r * 0.8f)
                c.drawRoundRect(rf, r * 0.15f, r * 0.15f, p)
                p.style = Paint.Style.FILL
                rf.set(cx + r * 0.3f, cy - r * 0.8f, cx + r, cy + r * 0.8f)
                c.drawRect(rf, p)
            }
            UNDO, REDO -> {
                val sgn = if (kind == UNDO) 1f else -1f
                p.style = Paint.Style.STROKE
                rf.set(cx - r * 0.9f, cy - r * 0.7f, cx + r * 0.9f, cy + r * 1.1f)
                if (kind == UNDO) c.drawArc(rf, 200f, 250f, false, p) else c.drawArc(rf, -270f, 250f, false, p)
                p.style = Paint.Style.FILL
                path.reset()
                val ax = cx - sgn * r * 0.85f
                val ay = cy - r * 0.55f
                path.moveTo(ax - sgn * r * 0.15f, ay - r * 0.55f)
                path.lineTo(ax - sgn * r * 0.15f, ay + r * 0.5f)
                path.lineTo(ax + sgn * r * 0.7f, ay)
                path.close()
                c.drawPath(path, p)
            }
            SAVE -> {
                p.style = Paint.Style.STROKE
                rf.set(cx - r, cy - r, cx + r, cy + r)
                c.drawRoundRect(rf, r * 0.2f, r * 0.2f, p)
                p.style = Paint.Style.FILL
                rf.set(cx - r * 0.5f, cy - r, cx + r * 0.5f, cy - r * 0.2f)
                c.drawRect(rf, p)
                rf.set(cx - r * 0.55f, cy + r * 0.1f, cx + r * 0.55f, cy + r)
                c.drawRect(rf, p)
            }
        }
    }
}
