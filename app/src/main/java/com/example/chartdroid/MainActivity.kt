package com.example.chartdroid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity(), HighwayView.Listener {

    private lateinit var highway: HighwayView
    private var song = Song()

    private var chartUri: Uri? = null
    private var dirty = false

    private var diffIdx = 3
    private var inst = "Single"

    private val undoStack = ArrayList<String>()
    private val redoStack = ArrayList<String>()
    private var clip: List<NoteObj> = emptyList()

    // ----- audio -----
    private class Stem(val uri: Uri, val name: String, val player: MediaPlayer)

    private val stems = ArrayList<Stem>()
    private var playing = false
    private var audioStarted = false
    private var speed = 1.0f
    private var startChartSec = 0.0
    private var wallStart = 0L
    private var lastTick = 0.0
    private var wave: Waveform? = null
    private var waveJob = 0
    private var metronomeHi: Clicker? = null
    private var metronomeLo: Clicker? = null
    private var clap: Clicker? = null

    // ----- pengaturan -----
    private var setMetronome = false
    private var setClap = false
    private var setWave = false
    private var set3D = true
    private var setExt = true
    private var setChord = true
    private var setDrum5 = false
    private var setVolume = 100
    private var setClickVol = 70
    private var setCalib = 0
    private var panelVisible = true
    private var setLefty = false

    // ----- tampilan -----
    private val toolButtons = HashMap<Int, View>()
    private lateinit var diffChip: TextView
    private lateinit var instChip: TextView
    private lateinit var snapText: TextView
    private lateinit var infoText: TextView
    private lateinit var selText: TextView
    private lateinit var timeText: TextView
    private lateinit var titleText: TextView
    private lateinit var playBtn: IconView
    private lateinit var speedBtn: TextView
    private lateinit var metBtn: View
    private lateinit var clapBtn: View
    private lateinit var liveRow: LinearLayout
    private var lastUi = 0L
    private var loopOn = false
    private lateinit var rightPanel: View
    private lateinit var guitarPlace: LinearLayout
    private lateinit var drumPlace: LinearLayout
    private lateinit var guitarSel: LinearLayout
    private lateinit var drumSel: LinearLayout
    private lateinit var fillBtn: TextView
    private lateinit var rollBtn: TextView
    private lateinit var barBtn: TextView
    private lateinit var kickBtn: TextView
    private lateinit var cymBtn: TextView
    private lateinit var placeTypeBtns: List<TextView>
    private lateinit var placeDynBtns: List<TextView>

    private val snaps = intArrayOf(1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 192)
    private var snapIdx = 5

    private val cBar = 0xFF2B2D3A.toInt()
    private val cPanel = 0xFF1C1E29.toInt()
    private val cBtn = 0xFF3A3E50.toInt()
    private val cSel = 0xFF3B82F6.toInt()

    private var pending = 0
    private val RQ_OPEN_CHART = 1
    private val RQ_OPEN_FOLDER = 2
    private val RQ_OPEN_AUDIO = 3
    private val RQ_SAVE_AS = 4
    private val RQ_EXPORT = 5
    private val RQ_ADD_STEM = 6
    private val RQ_EXPORT_MID = 7
    private val RQ_GH2 = 8
    private val RQ_GH2_ISO = 10
    private val RQ_GH2_SRC = 11
    private val RQ_GH2_CHECK = 12

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val rq = pending
        pending = 0
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = res.data ?: return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult
        try {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
            }
            when (rq) {
                RQ_OPEN_CHART -> loadChartFromUri(uri)
                RQ_OPEN_FOLDER -> loadFolder(uri)
                RQ_OPEN_AUDIO -> {
                    releaseStems()
                    addStem(uri, displayName(uri))
                    afterAudioChange()
                }
                RQ_ADD_STEM -> {
                    addStem(uri, displayName(uri))
                    afterAudioChange()
                }
                RQ_SAVE_AS -> {
                    chartUri = uri
                    rememberRecent(uri)
                    saveChart()
                }
                RQ_EXPORT -> exportFolder(uri)
                RQ_GH2 -> runGh2Jobs(uri, false)
                RQ_GH2_ISO -> runGh2Jobs(uri, true)
                RQ_GH2_SRC -> scanGh2Folder(uri)
                RQ_GH2_CHECK -> runGh2Check(uri)
                RQ_EXPORT_MID -> {
                    val os = contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("tidak bisa menulis")
                    os.use { it.write(MidiIO.write(song)) }
                    toast("File .mid diekspor")
                }
            }
        } catch (e: Exception) {
            toast("Gagal: ${e.message}")
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } catch (e: Exception) {
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadPrefs()

        highway = HighwayView(this)
        highway.listener = this
        highway.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && playing) pausePlayback()
            false
        }

        metronomeHi = Clicker(this, "met_hi", 1800.0, 50, false)
        metronomeLo = Clicker(this, "met_lo", 1100.0, 50, false)
        clap = Clicker(this, "clap", 0.0, 80, true)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF15161D.toInt())
        root.addView(buildTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.HORIZONTAL
        mid.addView(buildToolColumn(), LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.MATCH_PARENT))
        mid.addView(highway, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        rightPanel = buildRightPanel()
        mid.addView(rightPanel, LinearLayout.LayoutParams(dp(176), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildPlayBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        setContentView(root)

        applySettingsToView()
        selectTool(HighwayView.T_NOTE)
        applySong(Song(), keepAudio = false)
        updateSnapText()
        updateInfo()
        goImmersive()
        toast("Bantuan > Panduan menjelaskan semua alat")
        handleViewIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ===================== pengaturan =====================
    private fun prefs() = getSharedPreferences("chartdroid", Context.MODE_PRIVATE)

    private fun loadPrefs() {
        val p = prefs()
        setMetronome = p.getBoolean("metronome", false)
        setClap = p.getBoolean("clap", false)
        setWave = p.getBoolean("wave", false)
        set3D = p.getBoolean("view3d", true)
        setExt = p.getBoolean("ext", true)
        setChord = p.getBoolean("chord", true)
        setDrum5 = p.getBoolean("drum5", false)
        setVolume = p.getInt("vol", 100)
        setClickVol = p.getInt("clickvol", 70)
        setCalib = p.getInt("calib", 0)
        panelVisible = p.getBoolean("panel", true)
        setLefty = p.getBoolean("lefty", false)
    }

    private fun savePrefs() {
        prefs().edit()
            .putBoolean("metronome", setMetronome).putBoolean("clap", setClap).putBoolean("wave", setWave)
            .putBoolean("view3d", set3D).putBoolean("ext", setExt).putBoolean("chord", setChord)
            .putBoolean("drum5", setDrum5).putInt("vol", setVolume).putInt("clickvol", setClickVol)
            .putInt("calib", setCalib).putBoolean("panel", panelVisible).putBoolean("lefty", setLefty).apply()
    }

    private fun applySettingsToView() {
        highway.view3D = set3D
        highway.extSustain = setExt
        highway.chordSelect = setChord
        highway.drum5Pref = setDrum5
        highway.showWave = setWave
        highway.lefty = setLefty
        val cv = setClickVol / 100f
        metronomeHi?.setVolume(cv)
        metronomeLo?.setVolume(cv)
        clap?.setVolume(cv)
        for (s in stems) try {
            s.player.setVolume(setVolume / 100f, setVolume / 100f)
        } catch (e: Exception) {
        }
        if (::rightPanel.isInitialized) rightPanel.visibility = if (panelVisible) View.VISIBLE else View.GONE
        if (::metBtn.isInitialized) {
            metBtn.background = bg(if (setMetronome) cSel else cBtn)
            clapBtn.background = bg(if (setClap) cSel else cBtn)
        }
        if (setWave && wave == null) loadWaveform()
        savePrefs()
    }

    private fun showSettings() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), dp(4))
        fun check(t: String, v: Boolean, f: (Boolean) -> Unit): CheckBox {
            val c = CheckBox(this)
            c.text = t
            c.isChecked = v
            c.setOnCheckedChangeListener { _, b ->
                f(b)
                applySettingsToView()
            }
            col.addView(c)
            return c
        }
        check("Tampilan 3D (perspektif)", set3D) { set3D = it }
        check("Tampilkan waveform", setWave) { setWave = it }
        check("Metronome saat memutar", setMetronome) { setMetronome = it }
        check("Clap (bunyi tiap not) saat memutar", setClap) { setClap = it }
        check("Extended sustains (tiap not dalam chord punya panjang sendiri)", setExt) { setExt = it }
        check("Pilih seluruh chord saat mengetuk not (Chord select)", setChord) { setChord = it }
        check("Drum 5 lane (jika kosong, otomatis dari isi chart)", setDrum5) { setDrum5 = it }
        check("Mode kidal (jalur dibalik kiri-kanan)", setLefty) { setLefty = it }
        fun slider(t: String, v: Int, f: (Int) -> Unit) {
            val tv = TextView(this)
            tv.text = "$t: $v%"
            tv.textSize = 12f
            col.addView(tv)
            val sb = SeekBar(this)
            sb.max = 100
            sb.progress = v
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    f(p)
                    tv.text = "$t: $p%"
                    applySettingsToView()
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            col.addView(sb)
        }
        slider("Volume musik", setVolume) { setVolume = it }
        slider("Volume metronome/clap", setClickVol) { setClickVol = it }
        val ct = TextView(this)
        ct.text = "Kalibrasi audio (ms) — geser tampilan terhadap suara"
        ct.textSize = 12f
        col.addView(ct)
        val ce = EditText(this)
        ce.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        ce.setText(setCalib.toString())
        col.addView(ce)
        val sv = ScrollView(this)
        sv.addView(col)
        AlertDialog.Builder(this).setTitle("Pengaturan").setView(sv)
            .setPositiveButton("Tutup") { _, _ ->
                setCalib = ce.text.toString().toIntOrNull() ?: 0
                savePrefs()
            }.show()
    }

    // ===================== pembuat UI =====================
    private fun bg(color: Int, r: Int = 6): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun btn(text: String, sizeSp: Float = 12f, onClick: (View) -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.background = bg(cBtn)
        t.setPadding(dp(6), dp(3), dp(6), dp(3))
        t.isClickable = true
        t.setOnClickListener { onClick(it) }
        return t
    }

    private fun ibtn(kind: Int, onClick: (View) -> Unit): IconView {
        val v = IconView(this, kind)
        v.background = bg(cBtn)
        v.isClickable = true
        v.setOnClickListener { onClick(it) }
        return v
    }

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 10f
        t.setTextColor(0xFF8B93A7.toInt())
        t.setPadding(0, dp(7), 0, dp(2))
        return t
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, m: Int = 2): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(w, h, weight)
        l.setMargins(dp(m), dp(m), dp(m), dp(m))
        return l
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(cBar)
        bar.gravity = Gravity.CENTER_VERTICAL
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val menus = LinearLayout(this)
        menus.orientation = LinearLayout.HORIZONTAL
        for (n in listOf("File", "Edit", "View", "Song", "Tools", "Options", "Help")) {
            val t = TextView(this)
            t.text = n
            t.textSize = 13f
            t.setTextColor(Color.WHITE)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(11), 0, dp(11), 0)
            t.setOnClickListener { showMenu(it, n) }
            menus.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        hs.addView(menus)
        bar.addView(hs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        titleText = TextView(this)
        titleText.textSize = 11f
        titleText.setTextColor(0xFFA7ADBF.toInt())
        titleText.gravity = Gravity.CENTER
        titleText.maxLines = 1
        titleText.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(titleText, LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.MATCH_PARENT))
        diffChip = btn("Expert ▾", 12f) { showDiffMenu(it) }
        instChip = btn("Guitar ▾", 12f) { showInstMenu(it) }
        bar.addView(diffChip, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 0f, 3))
        bar.addView(instChip, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 0f, 3))
        bar.addView(ibtn(IconView.UNDO) { undo() }, lp(dp(38), ViewGroup.LayoutParams.MATCH_PARENT, 0f, 3))
        bar.addView(ibtn(IconView.REDO) { redo() }, lp(dp(38), ViewGroup.LayoutParams.MATCH_PARENT, 0f, 3))
        bar.addView(ibtn(IconView.SAVE) { saveChart() }, lp(dp(38), ViewGroup.LayoutParams.MATCH_PARENT, 0f, 3))
        return bar
    }

    private fun buildToolColumn(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(cPanel)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(3), dp(3), dp(3), dp(3))
        val defs = listOf(
            Triple(HighwayView.T_PAN, IconView.PAN, "Geser"),
            Triple(HighwayView.T_SELECT, IconView.CURSOR, "Pilih"),
            Triple(HighwayView.T_ERASE, IconView.ERASER, "Hapus"),
            Triple(HighwayView.T_NOTE, IconView.NOTE, "Not"),
            Triple(HighwayView.T_STAR, IconView.STAR, "Star Power"),
            Triple(HighwayView.T_ROLL, IconView.ROLL, "Drum Roll"),
            Triple(HighwayView.T_BPM, IconView.BPM, "BPM"),
            Triple(HighwayView.T_TS, IconView.TS, "Birama"),
            Triple(HighwayView.T_SECTION, IconView.SECTION, "Section"),
            Triple(HighwayView.T_EVENT, IconView.EVENT, "Event")
        )
        for (def in defs) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(4), 0, dp(4), 0)
            row.isClickable = true
            row.setOnClickListener { selectTool(def.first) }
            row.addView(IconView(this, def.second), LinearLayout.LayoutParams(dp(28), dp(28)))
            val t = TextView(this)
            t.text = def.third
            t.textSize = 12f
            t.setTextColor(Color.WHITE)
            t.setPadding(dp(6), 0, 0, 0)
            t.maxLines = 1
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            toolButtons[def.first] = row
            col.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(34), 0f, 1))
        }
        sv.addView(col)
        return sv
    }

    private fun seg(labels: List<String>, onClick: (Int) -> Unit): Pair<LinearLayout, List<TextView>> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val list = ArrayList<TextView>()
        for ((i, t) in labels.withIndex()) {
            val b = btn(t, 11f) { onClick(i) }
            list.add(b)
            row.addView(b, lp(0, dp(34), 1f, 1))
        }
        return row to list
    }

    private fun setSegSel(list: List<TextView>, idx: Int) {
        for (i in list.indices) list[i].background = bg(if (i == idx) cSel else cBtn)
    }

    private fun buildRightPanel(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(cPanel)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(8), dp(0), dp(8), dp(8))
        val full = ViewGroup.LayoutParams.MATCH_PARENT

        col.addView(label("SNAP (STEP)"))
        val sr = LinearLayout(this)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.gravity = Gravity.CENTER_VERTICAL
        sr.addView(btn("−", 16f) { changeSnap(-1) }, lp(dp(40), dp(36), 0f, 1))
        snapText = TextView(this)
        snapText.setTextColor(Color.WHITE)
        snapText.textSize = 14f
        snapText.gravity = Gravity.CENTER
        sr.addView(snapText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sr.addView(btn("+", 16f) { changeSnap(1) }, lp(dp(40), dp(36), 0f, 1))
        col.addView(sr)

        col.addView(label("TARUH DI KURSOR"))
        liveRow = LinearLayout(this)
        liveRow.orientation = LinearLayout.HORIZONTAL
        col.addView(liveRow, LinearLayout.LayoutParams(full, dp(40)))

        // ---- tipe not baru (placement) ----
        col.addView(label("TIPE NOT BARU"))
        guitarPlace = LinearLayout(this)
        guitarPlace.orientation = LinearLayout.VERTICAL
        val (pt, ptb) = seg(listOf("Nat", "Strum", "HOPO", "Tap")) { i ->
            highway.placeType = i
            setSegSel(placeTypeBtns, i)
        }
        placeTypeBtns = ptb
        guitarPlace.addView(pt)
        barBtn = btn("Open (bar)", 11f) {
            highway.barMode = !highway.barMode
            updateToggles()
        }
        guitarPlace.addView(barBtn, lp(full, dp(34), 0f, 1))
        col.addView(guitarPlace)

        drumPlace = LinearLayout(this)
        drumPlace.orientation = LinearLayout.VERTICAL
        cymBtn = btn("Cymbal (Y/B/O)", 11f) {
            highway.placeCymbal = !highway.placeCymbal
            updateToggles()
        }
        drumPlace.addView(cymBtn, lp(full, dp(34), 0f, 1))
        val (pd, pdb) = seg(listOf("Normal", "Accent", "Ghost")) { i ->
            highway.placeDyn = i
            setSegSel(placeDynBtns, i)
        }
        placeDynBtns = pdb
        drumPlace.addView(pd)
        val barRow = LinearLayout(this)
        barRow.orientation = LinearLayout.HORIZONTAL
        barRow.addView(btn("Kick", 11f) {
            highway.barMode = !highway.barMode
            updateToggles()
        }.also { kickBtn = it }, lp(0, dp(34), 1f, 1))
        val dbl = btn("2× Kick", 11f) {
            highway.placeDouble = !highway.placeDouble
            updateToggles()
        }
        barRow.addView(dbl, lp(0, dp(34), 1f, 1))
        drumPlace.addView(barRow)
        fillBtn = btn("SP tool → Fill", 11f) {
            highway.fillMode = !highway.fillMode
            updateToggles()
        }
        drumPlace.addView(fillBtn, lp(full, dp(34), 0f, 1))
        rollBtn = btn("Roll ganda", 11f) {
            highway.rollDouble = !highway.rollDouble
            updateToggles()
        }
        drumPlace.addView(rollBtn, lp(full, dp(34), 0f, 1))
        col.addView(drumPlace)

        // ---- not terpilih ----
        col.addView(label("NOT TERPILIH"))
        guitarSel = LinearLayout(this)
        guitarSel.orientation = LinearLayout.VERTICAL
        guitarSel.addView(seg(listOf("Nat", "Strum", "HOPO", "Tap")) { highway.setType(it) }.first)
        col.addView(guitarSel)
        drumSel = LinearLayout(this)
        drumSel.orientation = LinearLayout.VERTICAL
        drumSel.addView(btn("Tom ⇄ Cymbal", 11f) { highway.toggleCymbal() }, lp(full, dp(34), 0f, 1))
        drumSel.addView(seg(listOf("Normal", "Accent", "Ghost")) { highway.setDynamics(it) }.first)
        drumSel.addView(btn("2× Kick", 11f) { highway.toggleDouble() }, lp(full, dp(34), 0f, 1))
        col.addView(drumSel)
        val sus = LinearLayout(this)
        sus.orientation = LinearLayout.HORIZONTAL
        sus.addView(btn("Sust −", 11f) { highway.changeSustain(-highway.snapTicks()) }, lp(0, dp(34), 1f, 1))
        sus.addView(btn("Sust +", 11f) { highway.changeSustain(highway.snapTicks()) }, lp(0, dp(34), 1f, 1))
        col.addView(sus)
        val ops = LinearLayout(this)
        ops.orientation = LinearLayout.HORIZONTAL
        ops.addView(btn("Hapus sust", 11f) { highway.clearSustain() }, lp(0, dp(34), 1f, 1))
        ops.addView(btn("Hapus", 11f) { highway.deleteSelection() }, lp(0, dp(34), 1f, 1))
        col.addView(ops)

        col.addView(label("INSPEKTUR"))
        selText = TextView(this)
        selText.setTextColor(0xFFE5E7EB.toInt())
        selText.textSize = 11f
        selText.typeface = Typeface.MONOSPACE
        col.addView(selText)
        col.addView(label("POSISI"))
        infoText = TextView(this)
        infoText.setTextColor(0xFFD1D5DB.toInt())
        infoText.textSize = 11f
        infoText.typeface = Typeface.MONOSPACE
        col.addView(infoText)
        sv.addView(col)
        return sv
    }

    private fun buildPlayBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(cBar)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(4), 0, dp(4), 0)
        val h = ViewGroup.LayoutParams.MATCH_PARENT
        bar.addView(ibtn(IconView.TO_START) { pausePlayback(); highway.goToTick(0) }, lp(dp(40), h, 0f, 2))
        bar.addView(ibtn(IconView.PREV_MEASURE) { pausePlayback(); highway.moveMeasure(-1) }, lp(dp(40), h, 0f, 2))
        bar.addView(ibtn(IconView.STEP_BACK) { pausePlayback(); highway.stepSnap(-1) }, lp(dp(40), h, 0f, 2))
        playBtn = ibtn(IconView.PLAY) { togglePlay() }
        bar.addView(playBtn, lp(dp(56), h, 0f, 2))
        bar.addView(ibtn(IconView.STEP_FWD) { pausePlayback(); highway.stepSnap(1) }, lp(dp(40), h, 0f, 2))
        bar.addView(ibtn(IconView.NEXT_MEASURE) { pausePlayback(); highway.moveMeasure(1) }, lp(dp(40), h, 0f, 2))
        timeText = TextView(this)
        timeText.setTextColor(Color.WHITE)
        timeText.textSize = 13f
        timeText.typeface = Typeface.MONOSPACE
        timeText.gravity = Gravity.CENTER
        bar.addView(timeText, LinearLayout.LayoutParams(0, h, 1f))
        metBtn = ibtn(IconView.METRO) {
            setMetronome = !setMetronome
            applySettingsToView()
        }
        clapBtn = ibtn(IconView.CLAP) {
            setClap = !setClap
            applySettingsToView()
        }
        bar.addView(metBtn, lp(dp(40), h, 0f, 2))
        bar.addView(clapBtn, lp(dp(40), h, 0f, 2))
        speedBtn = btn("1.0x", 12f) { cycleSpeed() }
        bar.addView(speedBtn, lp(dp(48), h, 0f, 2))
        bar.addView(ibtn(IconView.ZOOM_OUT) { highway.range = highway.range * 1.35 }, lp(dp(38), h, 0f, 2))
        bar.addView(ibtn(IconView.ZOOM_IN) { highway.range = highway.range / 1.35 }, lp(dp(38), h, 0f, 2))
        bar.addView(ibtn(IconView.PANEL) {
            panelVisible = !panelVisible
            applySettingsToView()
        }, lp(dp(38), h, 0f, 2))
        return bar
    }

    private fun updateToggles() {
        if (!::barBtn.isInitialized) return
        barBtn.background = bg(if (highway.barMode) HighwayView.OPEN_COLOR else cBtn)
        kickBtn.background = bg(if (highway.barMode) 0xFFF59E0B.toInt() else cBtn)
        cymBtn.background = bg(if (highway.placeCymbal) cSel else cBtn)
        fillBtn.background = bg(if (highway.fillMode) cSel else cBtn)
        rollBtn.background = bg(if (highway.rollDouble) cSel else cBtn)
        setSegSel(placeTypeBtns, highway.placeType)
        setSegSel(placeDynBtns, highway.placeDyn)
        val drums = highway.mode() == Mode.DRUMS
        guitarPlace.visibility = if (drums) View.GONE else View.VISIBLE
        guitarSel.visibility = if (drums) View.GONE else View.VISIBLE
        drumPlace.visibility = if (drums) View.VISIBLE else View.GONE
        drumSel.visibility = if (drums) View.VISIBLE else View.GONE
        barBtn.text = "Open (bar)"
        liveRow.removeAllViews()
        val ln = highway.lanes
        for (i in 0 until ln.count) {
            val b = TextView(this)
            b.background = bg(ln.color(ln.frets[i]), 5)
            b.isClickable = true
            b.setOnClickListener { highway.placeAtCursor(i) }
            liveRow.addView(b, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f, 1))
        }
        val bb = TextView(this)
        bb.text = if (ln.mode == Mode.DRUMS) "K" else "O"
        bb.textSize = 11f
        bb.gravity = Gravity.CENTER
        bb.setTextColor(Color.BLACK)
        bb.background = bg(ln.color(ln.barFret), 5)
        bb.isClickable = true
        bb.setOnClickListener { highway.placeAtCursor(-1) }
        liveRow.addView(bb, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f, 1))
    }

    // ===================== menu =====================
    private fun showMenu(anchor: View, name: String) {
        val items: List<Pair<String, () -> Unit>> = when (name) {
            "File" -> listOf(
                "Baru…" to { newChart() },
                "Buka chart (.chart / .mid)…" to { launchOpen(RQ_OPEN_CHART) },
                "Buka folder lagu (chart + audio)…" to { launchTree(RQ_OPEN_FOLDER) },
                "Buka terakhir" to { openRecent() },
                "Simpan" to { saveChart() },
                "Simpan sebagai…" to { saveAs() },
                "Ekspor .mid…" to { exportMid() },
                "Ekspor ke folder Clone Hero…" to { launchTree(RQ_EXPORT) },
                "Pasang ke GH2 Deluxe (PS2)…" to { gh2Dialog() },
                "Pasang BANYAK lagu ke GH2 (folder)…" to { launchTree(RQ_GH2_SRC) },
                "Periksa / pulihkan ISO GH2…" to { gh2CheckDialog() },
                "Pulihkan autosave" to { restoreAutosave() }
            )
            "Edit" -> listOf(
                "Undo" to { undo() },
                "Redo" to { redo() },
                "Pilih semua" to { highway.selectAll() },
                "Batal pilih" to { highway.selectNone() },
                "Pilih seluruh section ini" to { highway.selectSectionAtCursor() },
                "Salin" to { copySel() },
                "Potong" to { copySel(); highway.deleteSelection() },
                "Tempel di posisi sekarang" to { highway.paste(clip, highway.snapTickPublic(highway.curTick)) },
                "Hapus terpilih" to { highway.deleteSelection() },
                "Geser terpilih ▲ (1 snap)" to { highway.nudge(highway.snapTicks()) },
                "Geser terpilih ▼ (1 snap)" to { highway.nudge(-highway.snapTicks()) },
                "Cerminkan lane terpilih" to { highway.mirror() },
                "Geser lane ke kiri" to { highway.shiftLanes(-1) },
                "Geser lane ke kanan" to { highway.shiftLanes(1) },
                "Kuantisasi terpilih ke snap" to { highway.quantize() },
                "Duplikat setelah terpilih" to { highway.duplicate() },
                "Salin dari tingkat lain…" to { copyFromDifficulty() },
                "Kosongkan track ini…" to { confirm("Kosongkan semua not di track ini?") { clearTrack() } },
                "BPM +1 di kursor" to { bumpBpm(1000) },
                "BPM −1 di kursor" to { bumpBpm(-1000) },
                "Anchor BPM di kursor (on/off)" to { toggleAnchor() },
                "Pilih semua berdasarkan…" to { selectByDialog() },
                "Potong sustain yang menabrak not berikutnya" to { toast("${highway.trimSustains()} sustain dipotong") },
                "Hapus sustain terlalu pendek" to { toast("${highway.removeShortSustains()} sustain dihapus") }
            )
            "View" -> listOf(
                "Zoom +" to { highway.range = highway.range / 1.35 },
                "Zoom −" to { highway.range = highway.range * 1.35 },
                "Reset zoom" to { highway.range = 768.0 },
                "Ke awal lagu" to { highway.curTick = 0.0 },
                "Ke not terakhir" to { toLastNote() },
                "Section sebelumnya" to { highway.jumpSection(-1) },
                "Section berikutnya" to { highway.jumpSection(1) },
                "Birama sebelumnya" to { highway.moveMeasure(-1) },
                "Birama berikutnya" to { highway.moveMeasure(1) },
                "Tampilan 3D / 2D" to { set3D = !set3D; applySettingsToView() },
                "Waveform on/off" to { setWave = !setWave; applySettingsToView() },
                "Tampilkan / sembunyikan panel kanan" to { panelVisible = !panelVisible; applySettingsToView() }
            )
            "Song" -> listOf(
                "Properti lagu…" to { songProperties() },
                "Offset audio…" to { offsetDialog() },
                "Buka audio utama…" to { launchOpen(RQ_OPEN_AUDIO) },
                "Tambah stem audio…" to { launchOpen(RQ_ADD_STEM) },
                "Daftar audio…" to { audioList() },
                "Muat ulang audio" to { reloadAudio() },
                "Set preview mulai = kursor" to { setPreview("PreviewStart") },
                "Set preview selesai = kursor" to { setPreview("PreviewEnd") }
            )
            "Tools" -> listOf(
                "Validasi lagu…" to { validate() },
                "Tap BPM…" to { tapBpm() },
                "Ketuk lirik (stempel)…" to { lyricStamp() },
                "Daftar event global…" to { eventList() },
                "Statistik chart…" to { showStats() },
                "Tag song.ini (Clone Hero)…" to { iniTags() },
                "Loop: set A = kursor" to { setLoop(true) },
                "Loop: set B = kursor" to { setLoop(false) },
                "Loop on/off" to { loopOn = !loopOn; toast(if (loopOn) "Loop aktif" else "Loop mati") },
                "Loop: hapus A/B" to { highway.loopA = -1; highway.loopB = -1; loopOn = false; highway.invalidate() },
                "Pergi ke…" to { goToDialog() },
                "Daftar section…" to { sectionList() },
                "Resolusi chart…" to { toast("Resolusi: ${song.resolution} tick per ketukan") }
            )
            "Options" -> listOf(
                "Pengaturan…" to { showSettings() },
                "Metronome on/off" to { setMetronome = !setMetronome; applySettingsToView() },
                "Clap on/off" to { setClap = !setClap; applySettingsToView() }
            )
            else -> listOf(
                "Panduan" to { showGuide() },
                "Tentang" to { showAbout() }
            )
        }
        val m = PopupMenu(this, anchor)
        for ((i, it) in items.withIndex()) m.menu.add(0, i, i, it.first)
        m.setOnMenuItemClickListener { item ->
            items[item.itemId].second()
            true
        }
        m.show()
    }

    private fun showDiffMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        for ((i, dname) in ChartIO.difficulties.withIndex()) m.menu.add(0, i, i, dname)
        m.setOnMenuItemClickListener { item ->
            diffIdx = item.itemId
            applyTrack()
            true
        }
        m.show()
    }

    private fun showInstMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val keys = ArrayList<String>()
        for ((i, e) in ChartIO.instruments.entries.withIndex()) {
            m.menu.add(0, i, i, e.value)
            keys.add(e.key)
        }
        m.setOnMenuItemClickListener { item ->
            inst = keys[item.itemId]
            applyTrack()
            true
        }
        m.show()
    }

    private fun copySel() {
        clip = highway.copySelection()
        toast("${clip.size} not disalin")
    }

    private fun toLastNote() {
        val n = highway.track().notes
        highway.curTick = if (n.isEmpty()) 0.0 else n[n.size - 1].tick.toDouble()
    }

    // ===================== alat / track =====================
    private fun selectTool(t: Int) {
        highway.tool = t
        for ((k, b) in toolButtons) b.background = bg(if (k == t) cSel else cBtn)
    }

    private fun applyTrack() {
        highway.trackKey = ChartIO.trackKey(ChartIO.difficulties[diffIdx], inst)
        diffChip.text = ChartIO.difficulties[diffIdx] + " ▾"
        instChip.text = (ChartIO.instruments[inst] ?: inst) + " ▾"
        highway.barMode = false
        updateToggles()
        updateInfo()
    }

    private fun changeSnap(dir: Int) {
        snapIdx = (snapIdx + dir).coerceIn(0, snaps.size - 1)
        updateSnapText()
    }

    private fun updateSnapText() {
        highway.snapDiv = snaps[snapIdx]
        snapText.text = "1/${snaps[snapIdx]}"
    }

    private fun updateTitle() {
        val n = if (song.name.isEmpty()) "(tanpa judul)" else song.name
        titleText.text = n + if (dirty) " *" else ""
    }

    // ===================== Listener HighwayView =====================
    override fun beforeEdit() {
        pushUndo()
        dirty = true
        updateTitle()
    }

    override fun onChanged() {
        updateInfo()
    }

    override fun onPositionChanged(tick: Double) {
        updateInfo()
    }

    override fun onSelectionChanged() {
        updateInfo()
    }

    override fun onUserSeek() {
        if (playing) pausePlayback()
    }

    override fun onMarkerTap(tool: Int, tick: Int, hit: Int) {
        when (tool) {
            HighwayView.T_BPM -> {
                val cur = if (hit >= 0) song.bpms[hit].milli else highway.bpmAtCursorAt(tick)
                val ev = if (hit >= 0) song.bpms[hit] else null
                form(
                    if (ev != null) "Ubah BPM (tick ${ev.tick})" else "BPM baru di tick $tick",
                    listOf("BPM" to ChartIO.bpmText(cur)),
                    listOf(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
                    if (ev != null && ev.tick != 0) "Hapus" else null,
                    onNeutral = {
                        beforeEdit()
                        if (ev != null) song.bpms.remove(ev)
                        afterSongEdit(null)
                    }
                ) { v ->
                    val bpm = v[0].replace(',', '.').toDoubleOrNull()
                    if (bpm == null || bpm < 1.0 || bpm > 2000.0) {
                        toast("BPM tidak valid")
                    } else {
                        beforeEdit()
                        val milli = Math.round(bpm * 1000).toInt()
                        val target: BpmEv
                        if (ev != null) {
                            ev.milli = milli
                            target = ev
                        } else {
                            target = BpmEv(tick, milli)
                            song.bpms.add(target)
                        }
                        afterSongEdit(target)
                    }
                }
            }
            HighwayView.T_TS -> {
                val ev = if (hit >= 0) song.ts[hit] else null
                val base = ev ?: highway.tsAtCursorAt(tick)
                form(
                    if (ev != null) "Ubah birama (tick ${ev.tick})" else "Birama baru di tick $tick",
                    listOf("Ketukan per birama" to base.num.toString(), "Nilai ketukan (1,2,4,8,16)" to (1 shl base.denExp).toString()),
                    listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_NUMBER),
                    if (ev != null && ev.tick != 0) "Hapus" else null,
                    onNeutral = {
                        beforeEdit()
                        if (ev != null) song.ts.remove(ev)
                        afterSongEdit(null)
                    }
                ) { v ->
                    val num = v[0].toIntOrNull()
                    val exp = when (v[1].toIntOrNull()) {
                        1 -> 0
                        2 -> 1
                        4 -> 2
                        8 -> 3
                        16 -> 4
                        32 -> 5
                        else -> -1
                    }
                    if (num == null || num < 1 || num > 64 || exp < 0) {
                        toast("Birama tidak valid")
                    } else {
                        beforeEdit()
                        if (ev != null) {
                            ev.num = num
                            ev.denExp = exp
                        } else song.ts.add(TsEv(tick, num, exp))
                        afterSongEdit(null)
                    }
                }
            }
            HighwayView.T_SECTION -> {
                if (hit >= 0) {
                    val ev = song.events[hit]
                    form(
                        "Ubah event global (tick ${ev.tick})",
                        listOf("Teks (mis. \"section Intro\", \"lyric kata\", \"phrase_start\")" to ev.text),
                        listOf(InputType.TYPE_CLASS_TEXT),
                        "Hapus",
                        onNeutral = {
                            beforeEdit()
                            song.events.remove(ev)
                            afterSongEdit(null)
                        }
                    ) { v ->
                        val s = v[0].trim()
                        if (s.isNotEmpty()) {
                            beforeEdit()
                            ev.text = s
                            afterSongEdit(null)
                        }
                    }
                } else {
                    val items = arrayOf("Section", "Lirik (lyric)", "Awal frasa (phrase_start)", "Akhir frasa (phrase_end)", "Event global kustom…")
                    AlertDialog.Builder(this).setTitle("Tambah di tick $tick").setItems(items) { _, which ->
                        when (which) {
                            0 -> form("Section baru", listOf("Nama (mis. Intro, Verse 1, Chorus)" to ""), listOf(InputType.TYPE_CLASS_TEXT), null, null) { v ->
                                if (v[0].isNotBlank()) addGlobal(tick, "section ${v[0].trim()}")
                            }
                            1 -> form("Lirik", listOf("Suku kata / kata (akhiri dengan - bila bersambung)" to ""), listOf(InputType.TYPE_CLASS_TEXT), null, null) { v ->
                                if (v[0].isNotBlank()) addGlobal(tick, "lyric ${v[0].trim()}")
                            }
                            2 -> addGlobal(tick, "phrase_start")
                            3 -> addGlobal(tick, "phrase_end")
                            else -> form("Event global", listOf("Teks" to ""), listOf(InputType.TYPE_CLASS_TEXT), null, null) { v ->
                                if (v[0].isNotBlank()) addGlobal(tick, v[0].trim())
                            }
                        }
                    }.show()
                }
            }
            HighwayView.T_EVENT -> {
                val tr = highway.track()
                if (hit >= 0) {
                    val ev = tr.events[hit]
                    AlertDialog.Builder(this)
                        .setTitle("Event \"${ev.text}\" di tick ${ev.tick}")
                        .setPositiveButton("Hapus") { _, _ ->
                            beforeEdit()
                            tr.events.remove(ev)
                            afterSongEdit(null)
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                } else {
                    val items = arrayOf("solo (awal solo)", "soloend (akhir solo)", "Teks kustom…")
                    AlertDialog.Builder(this)
                        .setTitle("Event di tick $tick")
                        .setItems(items) { _, which ->
                            when (which) {
                                0 -> addTrackEvent(tick, "solo")
                                1 -> addTrackEvent(tick, "soloend")
                                else -> form("Event kustom", listOf("Teks" to ""), listOf(InputType.TYPE_CLASS_TEXT), null, null) { v ->
                                    val s = v[0].trim()
                                    if (s.isNotEmpty()) addTrackEvent(tick, s)
                                }
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun addGlobal(tick: Int, text: String) {
        beforeEdit()
        song.events.add(TextEv(tick, text))
        afterSongEdit(null)
    }

    private fun addTrackEvent(tick: Int, text: String) {
        beforeEdit()
        highway.track().events.add(TextEv(tick, text))
        afterSongEdit(null)
    }

    private fun afterSongEdit(editedBpm: BpmEv?) {
        song.normalize()
        fixAnchors(editedBpm)
        highway.rebuild()
        updateAudioLength()
        updateInfo()
    }

    // ===================== BPM =====================
    private fun bumpBpm(delta: Int) {
        val tick = highway.curTick.toInt()
        var target: BpmEv? = null
        for (b in song.bpms) if (b.tick <= tick) target = b
        val t = target ?: return
        beforeEdit()
        t.milli = (t.milli + delta).coerceAtLeast(1000)
        afterSongEdit(t)
    }

    private fun toggleAnchor() {
        val tick = highway.snapTickPublic(highway.curTick)
        val b = song.bpms.firstOrNull { it.tick == tick }
        if (b == null) {
            toast("Letakkan kursor tepat di penanda BPM")
            return
        }
        beforeEdit()
        if (b.anchor != null) b.anchor = null
        else b.anchor = Math.round(TempoMap(song).tickToSec(b.tick.toDouble()) * 1_000_000.0)
        highway.rebuild()
        toast(if (b.anchor != null) "BPM di-anchor" else "Anchor dilepas")
    }

    /** Jaga agar BPM ber-anchor tetap di waktunya: ubah BPM sebelumnya. */
    private fun fixAnchors(skip: BpmEv?) {
        val list = song.bpms
        for (k in 1 until list.size) {
            val a = list[k].anchor ?: continue
            val prev = list[k - 1]
            if (prev === skip) continue
            val tm = TempoMap(song)
            val t0 = tm.tickToSec(prev.tick.toDouble())
            val dt = a / 1_000_000.0 - t0
            if (dt <= 0.0) continue
            val milli = Math.round(60.0 * (list[k].tick - prev.tick) / (song.resolution * dt) * 1000.0).toInt()
            if (milli in 1000..2000000) prev.milli = milli
        }
    }

    private fun tapBpm() {
        val taps = ArrayList<Long>()
        val result = TextView(this)
        result.textSize = 22f
        result.gravity = Gravity.CENTER
        result.text = "Ketuk mengikuti ketukan"
        var bpmVal = 0.0
        val tap = btn("TAP", 26f) {
            val now = SystemClock.elapsedRealtime()
            if (taps.isNotEmpty() && now - taps.last() > 2500) taps.clear()
            taps.add(now)
            while (taps.size > 12) taps.removeAt(0)
            if (taps.size >= 2) {
                val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
                bpmVal = 60000.0 / avg
                result.text = String.format(java.util.Locale.US, "%.1f BPM", bpmVal)
            }
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(10), dp(20), dp(10))
        col.addView(result, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        col.addView(tap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
        AlertDialog.Builder(this).setTitle("Tap BPM").setView(col)
            .setPositiveButton("Terapkan di kursor") { _, _ ->
                if (bpmVal >= 20) {
                    val tick = highway.snapTickPublic(highway.curTick)
                    beforeEdit()
                    val existing = song.bpms.firstOrNull { it.tick == tick }
                    val milli = Math.round(bpmVal * 1000).toInt()
                    val t: BpmEv
                    if (existing != null) {
                        existing.milli = milli
                        t = existing
                    } else {
                        t = BpmEv(tick, milli)
                        song.bpms.add(t)
                    }
                    afterSongEdit(t)
                }
            }
            .setNegativeButton("Tutup", null).show()
    }

    // ===================== lirik =====================
    private fun lyricStamp() {
        val et = EditText(this)
        et.hint = "Satu baris = satu frasa. Pisahkan kata/suku kata dengan spasi."
        et.minLines = 6
        et.gravity = Gravity.TOP
        AlertDialog.Builder(this).setTitle("Lirik").setView(et)
            .setPositiveButton("Mulai") { _, _ ->
                val lines = et.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) return@setPositiveButton
                runLyricStamper(lines.map { l -> l.split(" ").filter { it.isNotEmpty() } })
            }.setNegativeButton("Batal", null).show()
    }

    private fun runLyricStamper(lines: List<List<String>>) {
        var li = 0
        var wi = 0
        var prevPhraseOpen = false
        val tv = TextView(this)
        tv.textSize = 20f
        tv.gravity = Gravity.CENTER
        fun refresh() {
            tv.text = if (li >= lines.size) "Selesai" else "Berikut: ${lines[li][wi]}\n(baris ${li + 1}/${lines.size})"
        }
        refresh()
        val stamp = btn("STEMPEL di kursor", 18f) {
            if (li < lines.size) {
                val tick = highway.snapTickPublic(highway.curTick)
                beforeEdit()
                if (wi == 0) {
                    if (prevPhraseOpen) song.events.add(TextEv(tick, "phrase_end"))
                    song.events.add(TextEv(tick, "phrase_start"))
                    prevPhraseOpen = true
                }
                song.events.add(TextEv(tick, "lyric ${lines[li][wi]}"))
                wi++
                if (wi >= lines[li].size) {
                    wi = 0
                    li++
                    if (li >= lines.size) song.events.add(TextEv(tick + highway.snapTicks() * 2, "phrase_end"))
                }
                afterSongEdit(null)
                refresh()
            }
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(10), dp(20), dp(10))
        col.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
        col.addView(stamp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
        AlertDialog.Builder(this).setTitle("Ketuk lirik (putar musik / geser kursor)").setView(col)
            .setPositiveButton("Tutup", null).show()
    }

    private fun eventList() {
        val evs = song.events.sortedBy { it.tick }
        if (evs.isEmpty()) {
            toast("Belum ada event global")
            return
        }
        val names = evs.map { "${it.tick}: ${it.text}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Event global (ketuk untuk hapus / loncat)")
            .setItems(names) { _, i ->
                val ev = evs[i]
                AlertDialog.Builder(this).setTitle(ev.text)
                    .setPositiveButton("Loncat ke sini") { _, _ -> highway.curTick = ev.tick.toDouble() }
                    .setNegativeButton("Hapus") { _, _ ->
                        beforeEdit()
                        song.events.remove(ev)
                        afterSongEdit(null)
                    }
                    .setNeutralButton("Batal", null).show()
            }.setNegativeButton("Tutup", null).show()
    }

    // ===================== undo / redo =====================
    private fun pushUndo() {
        try {
            undoStack.add(ChartIO.write(song))
            if (undoStack.size > 60) undoStack.removeAt(0)
            redoStack.clear()
        } catch (e: Exception) {
        }
    }

    private fun undo() {
        if (undoStack.isEmpty()) {
            toast("Tidak ada yang bisa di-undo")
            return
        }
        redoStack.add(ChartIO.write(song))
        restore(undoStack.removeAt(undoStack.size - 1))
    }

    private fun redo() {
        if (redoStack.isEmpty()) {
            toast("Tidak ada yang bisa di-redo")
            return
        }
        undoStack.add(ChartIO.write(song))
        restore(redoStack.removeAt(redoStack.size - 1))
    }

    private fun restore(text: String) {
        val s = ChartIO.parse(text)
        song = s
        highway.song = s
        dirty = true
        updateAudioLength()
        updateTitle()
        updateInfo()
    }

    // ===================== file =====================
    private fun launchOpen(rq: Int) {
        pending = rq
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        if (rq == RQ_OPEN_AUDIO || rq == RQ_ADD_STEM) i.type = "audio/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        launcher.launch(i)
    }

    private fun launchTree(rq: Int) {
        pending = rq
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        launcher.launch(i)
    }

    private fun saveAs() {
        pending = RQ_SAVE_AS
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/octet-stream"
        i.putExtra(Intent.EXTRA_TITLE, "notes.chart")
        launcher.launch(i)
    }

    private fun exportMid() {
        pending = RQ_EXPORT_MID
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/midi"
        i.putExtra(Intent.EXTRA_TITLE, "notes.mid")
        launcher.launch(i)
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment ?: ""
    }

    private fun readBytes(uri: Uri): ByteArray {
        val ins = contentResolver.openInputStream(uri) ?: throw IllegalStateException("tidak bisa membuka file")
        return ins.use { it.readBytes() }
    }

    private fun writeText(uri: Uri, text: String) {
        val os = contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("tidak bisa menulis file")
        os.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun loadChartFromUri(uri: Uri) {
        val name = displayName(uri)
        val bytes = readBytes(uri)
        if (name.endsWith(".mid", true) || name.endsWith(".midi", true)) {
            val s = MidiIO.read(bytes)
            chartUri = null
            applySong(s, keepAudio = true)
            dirty = true
            updateTitle()
            toast("MIDI diimpor. Gunakan Simpan sebagai… untuk menyimpan ke .chart")
            return
        }
        val s = ChartIO.parse(bytes.toString(Charsets.UTF_8))
        chartUri = uri
        rememberRecent(uri)
        applySong(s, keepAudio = true)
        toast("Chart dibuka: $name")
    }

    private class Child(val id: String, val name: String)

    private fun listChildren(tree: Uri): List<Child> {
        val out = ArrayList<Child>()
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) out.add(Child(c.getString(0), c.getString(1) ?: ""))
        }
        return out
    }

    private fun loadFolder(tree: Uri) {
        val kids = listChildren(tree)
        val chart = kids.firstOrNull { it.name.equals("notes.chart", true) }
            ?: kids.firstOrNull { it.name.endsWith(".chart", true) }
        val mid = kids.firstOrNull { it.name.equals("notes.mid", true) } ?: kids.firstOrNull { it.name.endsWith(".mid", true) }
        if (chart == null && mid == null) {
            toast("Tidak ada file .chart / .mid di folder ini")
            return
        }
        val s: Song
        if (chart != null) {
            val cu = DocumentsContract.buildDocumentUriUsingTree(tree, chart.id)
            chartUri = cu
            rememberRecent(cu)
            s = ChartIO.parse(readBytes(cu).toString(Charsets.UTF_8))
        } else {
            chartUri = null
            s = MidiIO.read(readBytes(DocumentsContract.buildDocumentUriUsingTree(tree, mid!!.id)))
        }
        // song.ini
        val ini = kids.firstOrNull { it.name.equals("song.ini", true) }
        if (ini != null) {
            try {
                val text = readBytes(DocumentsContract.buildDocumentUriUsingTree(tree, ini.id)).toString(Charsets.UTF_8)
                val map = HashMap<String, String>()
                for (l in text.lines()) {
                    val i = l.indexOf('=')
                    if (i > 0) map[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                for ((k, v) in map) if (k != "name" && k != "artist" && k != "album" && k != "genre" && k != "charter" && k != "year" && k != "[song]") s.ini[k] = v
                fun fill(key: String, ini: String) {
                    if ((s.meta[key] ?: "").isEmpty() && !map[ini].isNullOrEmpty()) s.meta[key] = map[ini]!!
                }
                fill("Name", "name")
                fill("Artist", "artist")
                fill("Album", "album")
                fill("Genre", "genre")
                fill("Charter", "charter")
                fill("Year", "year")
            } catch (e: Exception) {
            }
        }
        applySong(s, keepAudio = false)
        if (mid != null && chart == null) {
            dirty = true
            updateTitle()
        }
        val exts = listOf(".ogg", ".opus", ".mp3", ".wav", ".m4a")
        val audios = kids.filter { k -> exts.any { k.name.endsWith(it, true) } && !k.name.startsWith("preview", true) }
        val main = audios.firstOrNull { it.name.startsWith("song.", true) } ?: audios.firstOrNull()
        if (main != null) {
            addStem(DocumentsContract.buildDocumentUriUsingTree(tree, main.id), main.name)
            for (a in audios) if (a !== main) addStem(DocumentsContract.buildDocumentUriUsingTree(tree, a.id), a.name)
            afterAudioChange()
        } else toast("Chart dibuka, audio tidak ditemukan — pilih lewat Song > Buka audio utama")
    }

    private fun saveChart() {
        val u = chartUri
        if (u == null) {
            saveAs()
            return
        }
        try {
            writeText(u, ChartIO.write(song))
            dirty = false
            updateTitle()
            toast("Tersimpan")
        } catch (e: Exception) {
            toast("Tidak bisa menimpa file asli, pilih lokasi simpan")
            saveAs()
        }
    }

    private fun exportFolder(tree: Uri) {
        val text = ChartIO.write(song)
        val mainStem = stems.firstOrNull()
        val extra = stems.drop(1).map { it.uri to it.name }
        val kids = listChildren(tree)
        val m = song.meta
        Thread {
            try {
                val treeDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                fun target(name: String, mime: String): Uri {
                    val ex = kids.firstOrNull { it.name == name }
                    return if (ex != null) DocumentsContract.buildDocumentUriUsingTree(tree, ex.id)
                    else DocumentsContract.createDocument(contentResolver, treeDoc, mime, name)
                        ?: throw IllegalStateException("gagal membuat $name")
                }
                fun copyAudio(u: Uri, outName: String) {
                    val t = target(outName, "application/octet-stream")
                    contentResolver.openInputStream(u)?.use { ins ->
                        contentResolver.openOutputStream(t, "wt")?.use { os -> ins.copyTo(os) }
                    }
                }
                val files = ArrayList<String>()
                if (mainStem != null) {
                    val ext = if (mainStem.name.contains('.')) mainStem.name.substringAfterLast('.').lowercase() else "ogg"
                    copyAudio(mainStem.uri, "song.$ext")
                    files.add("song.$ext")
                    for ((u, n) in extra) {
                        copyAudio(u, n)
                        files.add(n)
                    }
                }
                val tc = target("notes.chart", "application/octet-stream")
                contentResolver.openOutputStream(tc, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                val ini = StringBuilder()
                ini.append("[song]\r\n")
                ini.append("name = ").append(m["Name"] ?: "").append("\r\n")
                ini.append("artist = ").append(m["Artist"] ?: "").append("\r\n")
                ini.append("album = ").append(m["Album"] ?: "").append("\r\n")
                ini.append("genre = ").append(m["Genre"] ?: "rock").append("\r\n")
                ini.append("year = ").append((m["Year"] ?: "").removePrefix(",").trim()).append("\r\n")
                ini.append("charter = ").append(m["Charter"] ?: "").append("\r\n")
                val extraIni = LinkedHashMap(song.ini)
                if (!extraIni.containsKey("diff_guitar")) extraIni["diff_guitar"] = m["Difficulty"] ?: "0"
                val ps = ((m["PreviewStart"]?.toDoubleOrNull() ?: 0.0) * 1000).toInt()
                if (!extraIni.containsKey("preview_start_time")) extraIni["preview_start_time"] = ps.toString()
                for ((k, v) in extraIni) ini.append(k).append(" = ").append(v).append("\r\n")
                val ti = target("song.ini", "text/plain")
                contentResolver.openOutputStream(ti, "wt")?.use { it.write(ini.toString().toByteArray(Charsets.UTF_8)) }
                runOnUiThread { toast("Diekspor: notes.chart, song.ini" + (if (files.isNotEmpty()) ", " + files.joinToString(", ") else "")) }
            } catch (e: Exception) {
                runOnUiThread { toast("Ekspor gagal: ${e.message}") }
            }
        }.start()
    }

    private fun autosaveFile() = File(filesDir, "autosave.chart")

    override fun onStop() {
        super.onStop()
        pausePlayback()
        if (dirty) {
            try {
                autosaveFile().writeText(ChartIO.write(song))
            } catch (e: Exception) {
            }
        }
    }

    private fun restoreAutosave() {
        val f = autosaveFile()
        if (!f.exists()) {
            toast("Belum ada autosave")
            return
        }
        confirm("Ganti chart sekarang dengan autosave terakhir?") {
            applySong(ChartIO.parse(f.readText()), keepAudio = true)
            dirty = true
            updateTitle()
        }
    }

    private fun newChart() {
        form(
            "Chart baru",
            listOf("Judul lagu" to "", "Artis" to "", "Charter" to ""),
            listOf(InputType.TYPE_CLASS_TEXT, InputType.TYPE_CLASS_TEXT, InputType.TYPE_CLASS_TEXT),
            null, null
        ) { v ->
            val s = Song()
            s.meta["Name"] = v[0]
            s.meta["Artist"] = v[1]
            s.meta["Charter"] = v[2]
            chartUri = null
            applySong(s, keepAudio = true)
            toast("Atur BPM dengan alat BPM, lalu muat audio lewat Song > Buka audio utama")
        }
    }

    private fun applySong(s: Song, keepAudio: Boolean) {
        pausePlayback()
        song = s
        undoStack.clear()
        redoStack.clear()
        if (!keepAudio) releaseStems()
        var chosen: Pair<Int, String>? = null
        var bestScore = -1
        for ((key, tr) in s.tracks) {
            val st = ChartIO.splitTrack(key) ?: continue
            if (!ChartIO.isEditableInstrument(st.second) || tr.notes.isEmpty()) continue
            val sc = (if (st.first == "Expert") 10 else 0) + (if (st.second == "Single") 5 else 0) + tr.notes.size.coerceAtMost(4)
            if (sc > bestScore) {
                bestScore = sc
                chosen = ChartIO.difficulties.indexOf(st.first) to st.second
            }
        }
        if (chosen != null) {
            diffIdx = chosen.first
            inst = chosen.second
        } else {
            diffIdx = 3
            inst = "Single"
        }
        highway.song = s
        highway.curTick = 0.0
        applyTrack()
        dirty = false
        updateAudioLength()
        updateTitle()
        updateInfo()
    }

    // ===================== audio =====================
    private fun addStem(uri: Uri, name: String) {
        try {
            val mp = MediaPlayer()
            mp.setDataSource(this, uri)
            mp.prepare()
            mp.setVolume(setVolume / 100f, setVolume / 100f)
            if (stems.isEmpty()) mp.setOnCompletionListener { pausePlayback() }
            stems.add(Stem(uri, name, mp))
            if (stems.size == 1) {
                val ms = song.meta["MusicStream"]
                if (ms.isNullOrEmpty() || ms == "song.ogg") song.meta["MusicStream"] = name
            }
        } catch (e: Exception) {
            toast("Audio gagal dimuat ($name): ${e.message}")
        }
    }

    private fun afterAudioChange() {
        wave = null
        highway.waveform = null
        updateAudioLength()
        updateTitle()
        if (stems.isNotEmpty()) toast("Audio dimuat: ${stems.joinToString(", ") { it.name }}")
        if (setWave) loadWaveform()
    }

    private fun loadWaveform() {
        val main = stems.firstOrNull() ?: return
        val job = ++waveJob
        toast("Membuat waveform…")
        Thread {
            val w = WaveformLoader.load(this, main.uri, 100) { job != waveJob }
            runOnUiThread {
                if (job == waveJob) {
                    wave = w
                    highway.waveform = w
                    if (w == null) toast("Waveform gagal dibuat")
                }
            }
        }.start()
    }

    private fun releaseStems() {
        pausePlayback()
        waveJob++
        for (s in stems) try {
            s.player.release()
        } catch (e: Exception) {
        }
        stems.clear()
        wave = null
        highway.waveform = null
        highway.lengthTicks = 0
    }

    private fun reloadAudio() {
        val list = stems.map { it.uri to it.name }
        if (list.isEmpty()) {
            toast("Belum ada audio")
            return
        }
        releaseStems()
        for ((u, n) in list) addStem(u, n)
        afterAudioChange()
    }

    private fun audioList() {
        if (stems.isEmpty()) {
            toast("Belum ada audio")
            return
        }
        val names = stems.mapIndexed { i, s -> (if (i == 0) "[utama] " else "") + s.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Audio (ketuk untuk membuang)")
            .setItems(names) { _, i ->
                val s = stems.removeAt(i)
                try {
                    s.player.release()
                } catch (e: Exception) {
                }
                if (i == 0 && stems.isNotEmpty()) stems[0].player.setOnCompletionListener { pausePlayback() }
                afterAudioChange()
            }.setNegativeButton("Tutup", null).show()
    }

    private fun updateAudioLength() {
        val mp = stems.firstOrNull()?.player
        if (mp == null) {
            highway.lengthTicks = 0
            return
        }
        val sec = mp.duration / 1000.0 - song.offset
        highway.lengthTicks = TempoMap(song).secToTick(max(0.0, sec)).toInt()
        highway.invalidate()
    }

    private fun applySpeed() {
        for (s in stems) try {
            s.player.playbackParams = PlaybackParams().setSpeed(speed)
        } catch (e: Exception) {
        }
    }

    private fun cycleSpeed() {
        val list = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f)
        var i = list.indexOfFirst { abs(it - speed) < 0.01f }
        i = (i + 1) % list.size
        val wasPlaying = playing
        if (wasPlaying) pausePlayback()
        speed = list[i]
        speedBtn.text = "${speed}x"
        if (wasPlaying) startPlayback()
    }

    private fun togglePlay() {
        if (playing) pausePlayback() else startPlayback()
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!playing) return
            stepFrame()
            highway.postOnAnimation(this)
        }
    }

    private fun startPlayback() {
        if (playing) return
        startChartSec = highway.tempo.tickToSec(highway.curTick)
        wallStart = SystemClock.elapsedRealtime()
        audioStarted = false
        lastTick = highway.curTick
        playing = true
        highway.glow = true
        playBtn.kind = IconView.PAUSE
        playBtn.invalidate()
        highway.postOnAnimation(frame)
    }

    private fun pausePlayback() {
        if (!playing) return
        playing = false
        highway.glow = false
        updateInfo(true)
        playBtn.kind = IconView.PLAY
        playBtn.invalidate()
        for (s in stems) try {
            if (s.player.isPlaying) s.player.pause()
        } catch (e: Exception) {
        }
        audioStarted = false
    }

    private fun stepFrame() {
        val now = SystemClock.elapsedRealtime()
        val wallChart = startChartSec + (now - wallStart) / 1000.0 * speed
        var chartSec = wallChart
        val main = stems.firstOrNull()?.player
        if (main != null) {
            try {
                if (!audioStarted) {
                    val ap = wallChart + song.offset
                    if (ap >= 0.0) {
                        for (s in stems) {
                            s.player.seekTo((ap * 1000).toInt())
                            s.player.start()
                        }
                        applySpeed()
                        audioStarted = true
                        startChartSec = wallChart
                        wallStart = now
                    }
                } else {
                    val ac = main.currentPosition / 1000.0 - song.offset
                    if (abs(ac - wallChart) > 0.06) {
                        startChartSec = ac
                        wallStart = now
                        chartSec = ac
                    }
                }
            } catch (e: Exception) {
            }
        }
        val t = highway.tempo.secToTick(chartSec + setCalib / 1000.0)
        if (loopOn && highway.loopA >= 0 && highway.loopB > highway.loopA && t >= highway.loopB) {
            for (st in stems) try {
                if (st.player.isPlaying) st.player.pause()
            } catch (e: Exception) {
            }
            startChartSec = highway.tempo.tickToSec(highway.loopA.toDouble())
            wallStart = now
            audioStarted = false
            lastTick = highway.loopA.toDouble()
            highway.curTick = highway.loopA.toDouble()
            return
        }
        if (t > lastTick && t - lastTick < song.resolution * 2) {
            if (setMetronome) {
                val lines = BeatGrid.lines(song, Math.floor(lastTick).toInt() + 1, Math.floor(t).toInt())
                if (lines.isNotEmpty()) (if (lines.any { it.isMeasure }) metronomeHi else metronomeLo)?.play()
            }
            if (setClap && highway.notesBetween(lastTick, t) > 0) clap?.play()
        }
        lastTick = t
        highway.curTick = t
    }

    // ===================== info =====================
    private var lastInfoText = ""
    private var lastSelText = ""

    private fun updateInfo(force: Boolean = false) {
        if (!::infoText.isInitialized) return
        if (playing && !force) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastUi < 90) return
            lastUi = now
        }
        val tick = highway.curTick
        val sec = highway.tempo.tickToSec(tick)
        val tr = highway.track()
        val a = highway.bpmAtCursorAt(tick.toInt())
        val ts = highway.tsAtCursorAt(tick.toInt())
        val infoStr = "Tick  ${tick.toInt()}\n" +
            "BPM   ${ChartIO.bpmText(a)}\n" +
            "TS    ${ts.num}/${1 shl ts.denExp}\n" +
            "Not   ${tr.notes.size}\n" +
            "SP    ${tr.starpower.count { it.kind == 2 }}\n" +
            "Res   ${song.resolution}"
        if (infoStr != lastInfoText) {
            lastInfoText = infoStr
            infoText.text = infoStr
        }
        val ss0 = highway.selectionSummary()
        if (ss0 != lastSelText) {
            lastSelText = ss0
            selText.text = ss0
        }
        val m = max(0.0, sec)
        val mm = (m / 60).toInt()
        val ss = m - mm * 60
        val tstr = String.format(java.util.Locale.US, "%02d:%06.3f", mm, ss)
        if (timeText.text.toString() != tstr) timeText.text = tstr
    }

    // ===================== dialog =====================
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Ya") { _, _ -> ok() }
            .setNegativeButton("Batal", null).show()
    }

    private fun clearTrack() {
        beforeEdit()
        val t = highway.track()
        t.notes.clear()
        t.starpower.clear()
        t.events.clear()
        highway.selection.clear()
        afterSongEdit(null)
    }

    private fun copyFromDifficulty() {
        val names = ChartIO.difficulties.toTypedArray()
        AlertDialog.Builder(this).setTitle("Salin dari tingkat… (menimpa tingkat sekarang)")
            .setItems(names) { _, i ->
                if (i == diffIdx) return@setItems
                val src = song.tracks[ChartIO.trackKey(ChartIO.difficulties[i], inst)]
                if (src == null || src.isEmpty()) {
                    toast("Tingkat itu kosong")
                    return@setItems
                }
                confirm("Timpa ${ChartIO.difficulties[diffIdx]} dengan isi ${ChartIO.difficulties[i]}?") {
                    beforeEdit()
                    val copy = src.deepCopy()
                    song.tracks[ChartIO.trackKey(ChartIO.difficulties[diffIdx], inst)] = copy
                    highway.selection.clear()
                    highway.rebuild()
                    updateInfo()
                }
            }.show()
    }

    private fun setLoop(isA: Boolean) {
        val t = highway.snapTickPublic(highway.curTick)
        if (isA) highway.loopA = t else highway.loopB = t
        if (highway.loopA >= 0 && highway.loopB > highway.loopA) loopOn = true
        highway.invalidate()
        toast("Loop ${if (isA) "A" else "B"} = tick $t")
    }

    private fun selectByDialog() {
        val ln = highway.lanes
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        for (i in 0 until ln.count) {
            items.add("Semua not jalur ${i + 1}")
            acts.add { highway.selectBy(0, i) }
        }
        items.add(if (ln.mode == Mode.DRUMS) "Semua kick" else "Semua not open")
        acts.add { highway.selectBy(0, -1) }
        items.add("Semua chord")
        acts.add { highway.selectBy(3) }
        items.add("Semua not bersustain")
        acts.add { highway.selectBy(4) }
        if (ln.mode == Mode.DRUMS) {
            items.add("Semua cymbal")
            acts.add { highway.selectBy(5) }
        } else {
            items.add("Semua HOPO")
            acts.add { highway.selectBy(1) }
            items.add("Semua tap")
            acts.add { highway.selectBy(2) }
            items.add("Semua forced")
            acts.add { highway.selectBy(6) }
        }
        AlertDialog.Builder(this).setTitle("Pilih semua…")
            .setItems(items.toTypedArray()) { _, i -> acts[i]() }
            .setNegativeButton("Batal", null).show()
    }

    private val iniKeys = listOf(
        "delay", "diff_guitar", "diff_bass", "diff_rhythm", "diff_keys", "diff_drums", "song_length",
        "hopo_frequency", "sustain_cutoff_threshold", "multiplier_note", "pro_drums", "five_lane_drums",
        "icon", "loading_phrase", "playlist_track", "modchart"
    )

    private fun iniTags() {
        form(
            "Tag song.ini (ikut tertulis saat Ekspor ke folder Clone Hero)",
            iniKeys.map { it to (song.ini[it] ?: "") },
            iniKeys.map { InputType.TYPE_CLASS_TEXT },
            "Isi default", onNeutral = {
                for ((k, v) in listOf("diff_guitar" to "-1", "diff_bass" to "-1", "diff_rhythm" to "-1", "diff_keys" to "-1", "diff_drums" to "-1", "hopo_frequency" to "170", "sustain_cutoff_threshold" to "-1", "multiplier_note" to "116")) {
                    if (song.ini[k].isNullOrEmpty()) song.ini[k] = v
                }
                toast("Tag default diisi (buka lagi dialog untuk melihat)")
            }
        ) { v ->
            for ((i, k) in iniKeys.withIndex()) {
                val t = v[i].trim()
                if (t.isEmpty()) song.ini.remove(k) else song.ini[k] = t
            }
            dirty = true
            updateTitle()
        }
    }

    private fun openRecent() {
        val u = prefs().getString("recent", null)
        if (u == null) {
            toast("Belum ada file terakhir")
            return
        }
        try {
            loadChartFromUri(Uri.parse(u))
        } catch (e: Exception) {
            toast("Tidak bisa membuka file terakhir: ${e.message}")
        }
    }

    private fun rememberRecent(u: Uri?) {
        if (u != null) prefs().edit().putString("recent", u.toString()).apply()
    }

    private fun handleViewIntent(i: Intent?) {
        val u = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        try {
            loadChartFromUri(u)
        } catch (e: Exception) {
            toast("Gagal membuka: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun showStats() {
        val tv = TextView(this)
        tv.text = highway.stats()
        tv.textSize = 13f
        tv.typeface = Typeface.MONOSPACE
        tv.setPadding(dp(20), dp(10), dp(20), dp(10))
        AlertDialog.Builder(this).setTitle("Statistik ${ChartIO.difficulties[diffIdx]} ${ChartIO.instruments[inst] ?: inst}")
            .setView(tv).setPositiveButton("Tutup", null).show()
    }

    private fun goToDialog() {
        form(
            "Pergi ke…",
            listOf("Tick" to "", "atau Birama ke-" to "", "atau Waktu (detik)" to ""),
            listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            null, null
        ) { v ->
            pausePlayback()
            val tick = v[0].toIntOrNull()
            val measure = v[1].toIntOrNull()
            val sec = v[2].replace(',', '.').toDoubleOrNull()
            if (tick != null) highway.goToTick(tick)
            else if (measure != null) {
                val res = song.resolution
                val lines = BeatGrid.lines(song, 0, max(res * 4 * (measure + 4) * 2, res * 64)).filter { it.isMeasure && it.measureNo == measure }
                if (lines.isNotEmpty()) highway.goToTick(lines[0].tick) else toast("Birama tidak ditemukan")
            } else if (sec != null) highway.goToTick(highway.tempo.secToTick(sec).toInt())
        }
    }

    private fun sectionList() {
        val secs = song.events.filter { it.text.startsWith("section ") }.sortedBy { it.tick }
        if (secs.isEmpty()) {
            toast("Belum ada section")
            return
        }
        val names = secs.map { it.text.substring(8) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Loncat ke section")
            .setItems(names) { _, i ->
                pausePlayback()
                highway.goToTick(secs[i].tick)
            }.setNegativeButton("Tutup", null).show()
    }

    private fun validate() {
        val mp = stems.firstOrNull()?.player
        val len = if (mp != null) mp.duration / 1000.0 else 0.0
        val tv = TextView(this)
        tv.text = Validator.report(song, mp != null, len)
        tv.textSize = 12f
        tv.typeface = Typeface.MONOSPACE
        tv.setPadding(dp(20), dp(10), dp(20), dp(10))
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this).setTitle("Validasi lagu").setView(sv).setPositiveButton("Tutup", null).show()
    }

    private fun setPreview(key: String) {
        beforeEdit()
        val sec = highway.tempo.tickToSec(highway.curTick) + song.offset
        song.meta[key] = Song.trimFloat(max(0.0, sec).toFloat())
        toast("$key = ${song.meta[key]} detik")
    }

    private fun form(
        title: String,
        fields: List<Pair<String, String>>,
        types: List<Int>,
        neutral: String?,
        onNeutral: (() -> Unit)?,
        onOk: (List<String>) -> Unit
    ) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), dp(4))
        val edits = ArrayList<EditText>()
        for ((i, f) in fields.withIndex()) {
            val tv = TextView(this)
            tv.text = f.first
            tv.textSize = 12f
            col.addView(tv)
            val e = EditText(this)
            e.setText(f.second)
            e.inputType = types.getOrElse(i) { InputType.TYPE_CLASS_TEXT }
            e.setSingleLine(true)
            col.addView(e)
            edits.add(e)
        }
        val sv = ScrollView(this)
        sv.addView(col)
        val b = AlertDialog.Builder(this).setTitle(title).setView(sv)
            .setPositiveButton("OK") { _, _ -> onOk(edits.map { it.text.toString() }) }
            .setNegativeButton("Batal", null)
        if (neutral != null && onNeutral != null) b.setNeutralButton(neutral) { _, _ -> onNeutral() }
        b.show()
    }

    private fun songProperties() {
        val keys = listOf("Name", "Artist", "Album", "Year", "Genre", "Charter", "Difficulty", "PreviewStart", "PreviewEnd")
        val labels = listOf("Judul", "Artis", "Album", "Tahun", "Genre", "Charter", "Tingkat kesulitan (0-6)", "Preview mulai (detik)", "Preview selesai (detik)")
        val fields = ArrayList<Pair<String, String>>()
        val types = ArrayList<Int>()
        for ((i, k) in keys.withIndex()) {
            var v = song.meta[k] ?: ""
            if (k == "Year") v = v.removePrefix(",").trim()
            fields.add(labels[i] to v)
            types.add(
                if (k == "Difficulty") InputType.TYPE_CLASS_NUMBER
                else if (k.startsWith("Preview")) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else InputType.TYPE_CLASS_TEXT
            )
        }
        form("Properti lagu", fields, types, null, null) { v ->
            beforeEdit()
            for ((i, k) in keys.withIndex()) {
                val s = v[i].trim()
                if (s.isEmpty() && (k == "Album" || k == "Year")) song.meta.remove(k)
                else song.meta[k] = if (s.isEmpty() && (k == "Difficulty" || k.startsWith("Preview"))) "0" else s
            }
            updateTitle()
        }
    }

    private fun offsetDialog() {
        form(
            "Offset audio (detik)",
            listOf("Offset — nilai positif = audio dimulai lebih lambat dari tick 0" to Song.trimFloat(song.offset)),
            listOf(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED),
            null, null
        ) { v ->
            val o = v[0].replace(',', '.').toFloatOrNull()
            if (o == null) toast("Angka tidak valid")
            else {
                beforeEdit()
                song.offset = o
                updateAudioLength()
                updateInfo()
                highway.invalidate()
            }
        }
    }

    private fun showGuide() {
        val msg = """
LAYAR (mirip Moonscraper)
• Bar atas: menu File / Edit / View / Song / Tools / Options / Help, pilihan tingkat (Easy–Expert) dan instrumen, Undo / Redo / Simpan.
• Kiri: kotak alat. Tekan lama sebuah ikon untuk melihat namanya.
• Tengah: highway. Garis putih bawah = garis pukul (posisi pemutaran).
• Kanan: panel snap, tipe not, aksi untuk not terpilih, inspektur. Tombol ☰ di bar bawah menyembunyikan / menampilkan panel.
• Bar bawah: ⏮ awal, ⏪ ⏩ birama, ◀ ▶ satu snap, putar, waktu, 🔔 metronome, 👏 clap, kecepatan, zoom, panel.

ALAT (istilah Moonscraper dalam kurung)
✋ Geser (Pan): seret naik/turun. Di alat lain, geser dengan dua jari. Cubit = zoom.
↖ Pilih (Cursor): ketuk not (memilih seluruh chord bila "Chord select" aktif); seret kotak di area kosong untuk memilih banyak; seret not yang sudah terpilih untuk memindahkannya (Grab move).
⌫ Hapus (Eraser): ketuk / seret di atas not.
● Not (Note): ketuk jalur untuk menaruh not (ketuk lagi = hapus). Tekan dan seret ke atas = sustain. Tombol Open/Kick menaruh not bar.
★ Star Power: seret awal → akhir. Ketuk = frasa 1 birama / hapus. Untuk drum, tombol "SP tool → Fill" membuat drum fill (ungu).
≋ Drum Roll: seperti star power; "Roll ganda" untuk roll ganda (hijau).
♩ BPM / 4/4 Time Signature / § Section & lirik / ⚑ Event (solo): ketuk posisi, isi dialog. Ketuk penanda yang ada untuk mengubah atau menghapus.

INSTRUMEN
Guitar, Co-op, Bass, Rhythm, Keys (5 lane + open), GHL (6 lane + open), Drums (4/5 lane, kick, cymbal/tom, accent, ghost, kick 2×, roll, fill).
Tipe not: Nat = natural, Strum, HOPO, Tap (tombol "TIPE NOT BARU" untuk yang baru, "NOT TERPILIH" untuk yang sudah ada).

TOOLS
• Validasi lagu, Tap BPM, Ketuk lirik (stempel kata ke posisi sekarang sambil memutar musik), Daftar event global.
• Edit > Anchor BPM: mengunci waktu penanda BPM; mengubah BPM sebelumnya akan menyesuaikan BPM sebelum anchor agar tidak bergeser.
• File mendukung .chart dan .mid (impor / ekspor). File .chart / .mid juga bisa dibuka dari pengelola file lewat "Buka dengan ChartDroid"; File > Buka terakhir membuka file sebelumnya.
• Loop A–B (Tools): tandai A dan B di kursor, aktifkan Loop, lalu putar untuk mengulang bagian sulit.
• Edit > Potong sustain / Hapus sustain pendek merapikan sustain; Tools > Tag song.ini mengisi tag Clone Hero.
• Panel "TARUH DI KURSOR": ketuk warna jalur untuk menaruh not di posisi kursor, bahkan saat lagu diputar.

TIPS
• Atur BPM dan Offset (Song > Offset audio) dulu sampai garis ketukan pas dengan musik. Aktifkan waveform di Options untuk membantu.
• Simpan sering. Autosave dibuat saat aplikasi ditutup.
        """.trimIndent()
        val tv = TextView(this)
        tv.text = msg
        tv.textSize = 12.5f
        tv.setPadding(dp(20), dp(10), dp(20), dp(10))
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this).setTitle("Panduan ChartDroid").setView(sv).setPositiveButton("Tutup", null).show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("ChartDroid")
            .setMessage(
                "Editor chart gaya Moonscraper untuk Android (format .chart / .mid, Clone Hero).\n\n" +
                    "Terinspirasi dari Moonscraper Chart Editor oleh Alexander Ong (BSD-3-Clause). " +
                    "Aplikasi ini bukan produk resmi Moonscraper dan tidak berafiliasi dengannya."
            )
            .setPositiveButton("OK", null).show()
    }

    override fun onBackPressed() {
        if (dirty) {
            AlertDialog.Builder(this)
                .setMessage("Ada perubahan yang belum disimpan. Keluar?")
                .setPositiveButton("Simpan & keluar") { _, _ ->
                    saveChart()
                    finish()
                }
                .setNegativeButton("Keluar tanpa simpan") { _, _ -> finish() }
                .setNeutralButton("Batal", null)
                .show()
        } else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        pausePlayback()
        for (s in stems) try {
            s.player.release()
        } catch (e: Exception) {
        }
        metronomeHi?.release()
        metronomeLo?.release()
        clap?.release()
    }

    // ===================== Guitar Hero II Deluxe =====================
    private var gh2Meta = Gh2.Meta()
    private var gh2Lead = ""
    private var gh2Coop: String? = null

    private fun gh2Dialog() {
        val parts = Gh2.availableParts(song)
        if (parts.isEmpty()) {
            toast("Chart belum punya track 5-fret (Guitar/Bass/Rhythm) berisi not")
            return
        }
        if (gh2Meta.symbol.isEmpty()) {
            gh2Meta.symbol = Gh2.sanitizeSymbol(song.name)
            gh2Meta.title = song.name
            gh2Meta.artist = song.meta["Artist"] ?: ""
            gh2Meta.album = song.meta["Album"] ?: ""
            gh2Meta.year = song.meta["Year"] ?: ""
            gh2Meta.genre = song.meta["Genre"] ?: ""
            gh2Meta.author = song.meta["Charter"] ?: ""
            gh2Meta.guitarRank = 3
            gh2Meta.bassRank = 3
        }
        val sv = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(8))
        sv.addView(box)
        fun label(t: String) {
            val tv = TextView(this)
            tv.text = t
            tv.textSize = 12f
            box.addView(tv)
        }
        fun field(t: String, v: String): EditText {
            label(t)
            val e = EditText(this)
            e.setText(v)
            e.setSingleLine(true)
            box.addView(e)
            return e
        }
        val info = TextView(this)
        info.text = "Target: file ISO GH2 Deluxe CE langsung di HP (buat salinan ISO dulu!), atau folder GEN hasil ekstrak (HDR dicadangkan sebagai .bak). " +
            "Audio yang sedang dimuat (semua stem) dipakai sebagai band."
        info.textSize = 12f
        box.addView(info)
        val eSym = field("Simbol (huruf kecil/angka, jadi nama folder lagu)", gh2Meta.symbol)
        val eTitle = field("Judul", gh2Meta.title)
        val eArtist = field("Artis", gh2Meta.artist)
        val eAlbum = field("Album", gh2Meta.album)
        val eAuthor = field("Pembuat chart (author)", gh2Meta.author)
        val eYear = field("Tahun", gh2Meta.year)
        val eGenre = field("Genre", gh2Meta.genre)
        val eGr = field("Tingkat gitar (0-6, -1 = tidak ada)", gh2Meta.guitarRank.toString())
        val eBr = field("Tingkat bass/rhythm (0-6, -1 = tidak ada)", gh2Meta.bassRank.toString())
        val names = parts.map { ChartIO.instruments[it] ?: it }
        label("Part lead (PART GUITAR)")
        val spLead = Spinner(this)
        spLead.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spLead.setSelection(max(0, parts.indexOf(gh2Lead)))
        box.addView(spLead)
        label("Part co-op")
        val coopNames = listOf("(salin dari lead)") + names
        val spCoop = Spinner(this)
        spCoop.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, coopNames)
        spCoop.setSelection(if (gh2Coop == null) 0 else 1 + max(0, parts.indexOf(gh2Coop)))
        box.addView(spCoop)
        label("Jenis co-op")
        val spType = Spinner(this)
        spType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Rhythm", "Bass"))
        spType.setSelection(if (gh2Meta.coop == "bass") 1 else 0)
        box.addView(spType)
        val cbDx = CheckBox(this)
        cbDx.text = "Format GH2 Deluxe (metadata author/album/dll.)"
        cbDx.isChecked = gh2Meta.deluxe
        box.addView(cbDx)
        val cbCover = CheckBox(this)
        cbCover.text = "Tampilkan 'performed by' (cover)"
        cbCover.isChecked = gh2Meta.cover
        box.addView(cbCover)
        AlertDialog.Builder(this)
            .setTitle("Pasang ke GH2 Deluxe")
            .setView(sv)
            .setNegativeButton("Batal", null)
            .setNeutralButton("Folder GEN…") { _, _ -> gh2Collect(eSym, eTitle, eArtist, eAlbum, eAuthor, eYear, eGenre, eGr, eBr, spType, cbDx, cbCover, spLead, spCoop, parts); chooseGh2Target(false) }
            .setPositiveButton("File ISO…") { _, _ -> gh2Collect(eSym, eTitle, eArtist, eAlbum, eAuthor, eYear, eGenre, eGr, eBr, spType, cbDx, cbCover, spLead, spCoop, parts); chooseGh2Target(true) }
            .show()
    }

    private fun gh2Collect(
        eSym: EditText, eTitle: EditText, eArtist: EditText, eAlbum: EditText, eAuthor: EditText, eYear: EditText,
        eGenre: EditText, eGr: EditText, eBr: EditText, spType: Spinner, cbDx: CheckBox, cbCover: CheckBox,
        spLead: Spinner, spCoop: Spinner, parts: List<String>
    ) {
        gh2Meta.symbol = Gh2.sanitizeSymbol(eSym.text.toString())
        gh2Meta.title = eTitle.text.toString().ifBlank { "Lagu" }
        gh2Meta.artist = eArtist.text.toString()
        gh2Meta.album = eAlbum.text.toString()
        gh2Meta.author = eAuthor.text.toString()
        gh2Meta.year = eYear.text.toString()
        gh2Meta.genre = eGenre.text.toString()
        gh2Meta.guitarRank = eGr.text.toString().toIntOrNull()?.coerceIn(-1, 6) ?: 3
        gh2Meta.bassRank = eBr.text.toString().toIntOrNull()?.coerceIn(-1, 6) ?: 3
        gh2Meta.coop = if (spType.selectedItemPosition == 1) "bass" else "rhythm"
        gh2Meta.deluxe = cbDx.isChecked
        gh2Meta.cover = cbCover.isChecked
        gh2Lead = parts[spLead.selectedItemPosition]
        gh2Coop = if (spCoop.selectedItemPosition == 0) null else parts[spCoop.selectedItemPosition - 1]
        val m = gh2Meta
        val copy = Gh2.Meta(m.symbol, m.title, m.artist, m.album, m.author, m.year, m.genre, m.coop, m.guitarRank, m.bassRank, m.drumRank, m.cover, m.deluxe)
        gh2Jobs = listOf(Gh2Job(m.title, song, copy, gh2Lead, gh2Coop, stems.map { it.uri }))
    }

    private var gh2Jobs: List<Gh2Job> = emptyList()

    private fun chooseGh2Target(iso: Boolean) {
        if (iso) {
            pending = RQ_GH2_ISO
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            launcher.launch(i)
        } else launchTree(RQ_GH2)
    }

    private fun scanGh2Folder(tree: Uri) {
        val tv = TextView(this)
        tv.setPadding(dp(20), dp(16), dp(20), dp(16))
        tv.text = "Memindai folder…"
        val dlg = AlertDialog.Builder(this).setTitle("Memindai").setView(tv).setCancelable(false).show()
        Thread {
            try {
                val entries = Gh2Library.scan(this, tree)
                runOnUiThread {
                    try { dlg.dismiss() } catch (e: Exception) {}
                    if (entries.isEmpty()) {
                        toast("Tidak ada lagu (notes.chart / notes.mid) ditemukan")
                        return@runOnUiThread
                    }
                    showGh2BatchList(tree, entries)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    try { dlg.dismiss() } catch (x: Exception) {}
                    toast("Gagal memindai: ${e.message}")
                }
            }
        }.start()
    }

    private fun showGh2BatchList(tree: Uri, entries: List<Gh2Library.Entry>) {
        val checked = BooleanArray(entries.size) { true }
        val names = entries.map { (it.ini["artist"]?.let { a -> "$a - " } ?: "") + (it.ini["name"] ?: it.name) }.toTypedArray()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val eAuthor = EditText(this)
        eAuthor.hint = "Author bila song.ini tidak punya charter"
        eAuthor.setText(gh2Meta.author)
        eAuthor.setSingleLine(true)
        val cbDx = CheckBox(this)
        cbDx.text = "Format GH2 Deluxe"
        cbDx.isChecked = true
        box.setPadding(dp(16), dp(8), dp(16), dp(0))
        box.addView(eAuthor)
        box.addView(cbDx)
        val sv = ScrollView(this)
        sv.addView(box)
        for ((i, n) in names.withIndex()) {
            val cb = CheckBox(this)
            cb.text = n
            cb.isChecked = true
            cb.setOnCheckedChangeListener { _, on -> checked[i] = on }
            box.addView(cb)
        }
        fun go(iso: Boolean) {
            val sel = entries.indices.filter { checked[it] }
            if (sel.isEmpty()) { toast("Tidak ada lagu dipilih"); return }
            val author = eAuthor.text.toString()
            val dx = cbDx.isChecked
            val tv = TextView(this)
            tv.setPadding(dp(20), dp(16), dp(20), dp(16))
            tv.text = "Membaca chart…"
            val dlg = AlertDialog.Builder(this).setTitle("Menyiapkan").setView(tv).setCancelable(false).show()
            Thread {
                val used = HashSet<String>()
                val jobs = ArrayList<Gh2Job>()
                val skipped = ArrayList<String>()
                for (i in sel) {
                    val (job, why) = Gh2Library.toJob(this, tree, entries[i], used, author, dx)
                    if (job != null) jobs.add(job) else if (why != null) skipped.add(why)
                }
                runOnUiThread {
                    try { dlg.dismiss() } catch (e: Exception) {}
                    if (jobs.isEmpty()) {
                        AlertDialog.Builder(this).setTitle("Tidak ada yang bisa dipasang").setMessage(skipped.joinToString("\n")).setPositiveButton("OK", null).show()
                    } else {
                        gh2Jobs = jobs
                        gh2Skipped = skipped
                        chooseGh2Target(iso)
                    }
                }
            }.start()
        }
        AlertDialog.Builder(this)
            .setTitle("Pilih lagu (${entries.size} ditemukan)")
            .setView(sv)
            .setNegativeButton("Batal", null)
            .setNeutralButton("Folder GEN…") { _, _ -> go(false) }
            .setPositiveButton("File ISO…") { _, _ -> go(true) }
            .show()
    }

    private var gh2Skipped: List<String> = emptyList()

    private fun runGh2Jobs(target: Uri, isIso: Boolean) {
        val jobs = gh2Jobs
        val skipped = gh2Skipped
        gh2Skipped = emptyList()
        if (jobs.isEmpty()) { toast("Tidak ada lagu untuk dipasang"); return }
        val tv = TextView(this)
        tv.setPadding(dp(20), dp(16), dp(20), dp(16))
        tv.text = "Memulai…"
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Memasang ${jobs.size} lagu ke GH2")
            .setView(tv)
            .setCancelable(false)
            .setNegativeButton("Batal") { _, _ -> cancel.set(true) }
            .show()
        Thread {
            var result: String
            try {
                result = Gh2Installer.installMany(this, target, isIso, jobs, { m -> runOnUiThread { tv.text = m } }, { cancel.get() })
                if (skipped.isNotEmpty()) result += "Dilewati:\n" + skipped.joinToString("\n") { "✗ $it" }
            } catch (e: Throwable) {
                result = "GAGAL: ${e.message ?: e.javaClass.simpleName}\nBila pesan ini muncul sebelum 'Menulis HDR', HDR/ISO asli belum diubah (kecuali ARK yang diperpanjang di akhir)."
            }
            runOnUiThread {
                try { dlg.dismiss() } catch (e: Exception) {}
                AlertDialog.Builder(this).setTitle("Hasil").setMessage(result).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private var gh2CheckMode = 0

    private fun gh2CheckDialog() {
        val items = arrayOf("Periksa ISO (hanya baca)", "Periksa + perbaiki ukuran volume", "Pulihkan ISO ke kondisi sebelum dipasangi (jurnal terakhir)", "UJI A: hanya tulis ulang wadah ISO, tanpa lagu (mengubah ISO!)", "UJI B: wadah + 4 DTB apa adanya, tanpa lagu (mengubah ISO!)", "UJI C: wadah + 1 berkas dummy (tanpa DTB) (mengubah ISO!)", "UJI D: wadah + 1 entri lagu dummy di DTB (tanpa berkas) (mengubah ISO!)", "UJI F: klon entri lagu pertama dengan nama baru, tanpa berkas (mengubah ISO!)")
        AlertDialog.Builder(this)
            .setTitle("ISO GH2")
            .setItems(items) { _, which ->
                gh2CheckMode = which
                pending = RQ_GH2_CHECK
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "*/*"
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                launcher.launch(i)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun runGh2Check(iso: Uri) {
        val mode = gh2CheckMode
        Thread {
            val msg = try {
                when (mode) {
                    0 -> Gh2Installer.diagnoseIso(this, iso, false)
                    1 -> Gh2Installer.diagnoseIso(this, iso, true)
                    3 -> Gh2Installer.containerTest(this, iso, false)
                    4 -> Gh2Installer.containerTest(this, iso, true)
                    5 -> Gh2Installer.containerTest(this, iso, false, 1)
                    6 -> Gh2Installer.containerTest(this, iso, false, 2)
                    7 -> Gh2Installer.containerTest(this, iso, false, 3)
                    else -> {
                        val j = Gh2Installer.journals(this)
                        if (j.isEmpty()) "Tidak ada jurnal pemulihan di perangkat ini."
                        else {
                            Gh2Installer.restoreIso(this, iso, j.first())
                            "ISO dipulihkan memakai ${j.first().name}.\n" + Gh2Installer.diagnoseIso(this, iso, false)
                        }
                    }
                }
            } catch (e: Throwable) {
                "GAGAL: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { AlertDialog.Builder(this).setTitle("Hasil").setMessage(msg).setPositiveButton("OK", null).show() }
        }.start()
    }
}
