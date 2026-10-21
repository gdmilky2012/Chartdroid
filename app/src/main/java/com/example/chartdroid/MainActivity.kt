package com.example.chartdroid

import android.app.Activity
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
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupMenu
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
    private var audioUri: Uri? = null
    private var audioName: String = ""
    private var dirty = false

    private var diffIdx = 3
    private var inst = "Single"

    private val undoStack = ArrayList<String>()
    private val redoStack = ArrayList<String>()
    private var clip: List<NoteObj> = emptyList()

    // ----- audio / pemutaran -----
    private var player: MediaPlayer? = null
    private var playing = false
    private var audioStarted = false
    private var speed = 1.0f
    private var startChartSec = 0.0
    private var wallStart = 0L

    // ----- tampilan -----
    private val toolButtons = ArrayList<TextView>()
    private val diffButtons = ArrayList<TextView>()
    private lateinit var instButton: TextView
    private lateinit var snapText: TextView
    private lateinit var openBtn: TextView
    private lateinit var infoText: TextView
    private lateinit var timeText: TextView
    private lateinit var titleText: TextView
    private lateinit var playBtn: TextView
    private lateinit var speedBtn: TextView

    private val snaps = intArrayOf(1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 192)
    private var snapIdx = 5

    private val cBar = 0xFF262936.toInt()
    private val cPanel = 0xFF1E202B.toInt()
    private val cBtn = 0xFF343848.toInt()
    private val cSel = 0xFF3B82F6.toInt()

    private var pending = 0
    private val RQ_OPEN_CHART = 1
    private val RQ_OPEN_FOLDER = 2
    private val RQ_OPEN_AUDIO = 3
    private val RQ_SAVE_AS = 4
    private val RQ_EXPORT = 5

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
                RQ_OPEN_AUDIO -> loadAudio(uri, displayName(uri))
                RQ_SAVE_AS -> {
                    chartUri = uri
                    saveChart()
                }
                RQ_EXPORT -> exportFolder(uri)
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

        highway = HighwayView(this)
        highway.listener = this
        highway.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && playing) pausePlayback()
            false
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF161821.toInt())
        root.addView(buildMenuBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.HORIZONTAL
        mid.addView(buildToolColumn(), LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.MATCH_PARENT))
        mid.addView(highway, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        mid.addView(buildRightPanel(), LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(buildPlayBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        setContentView(root)

        selectTool(HighwayView.T_NOTE)
        applySong(Song(), keepAudio = false)
        updateSnapText()
        updateInfo()
        goImmersive()
        toast("Tips: menu Bantuan > Panduan menjelaskan tiap alat")
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
        t.setPadding(dp(8), dp(4), dp(8), dp(4))
        t.isClickable = true
        t.setOnClickListener { onClick(it) }
        return t
    }

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 11f
        t.setTextColor(0xFF9CA3AF.toInt())
        t.setPadding(0, dp(8), 0, dp(2))
        return t
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, m: Int = 2): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(w, h, weight)
        l.setMargins(dp(m), dp(m), dp(m), dp(m))
        return l
    }

    private fun buildMenuBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(cBar)
        bar.gravity = Gravity.CENTER_VERTICAL
        val names = listOf("File", "Edit", "View", "Song", "Bantuan")
        for (n in names) {
            val t = TextView(this)
            t.text = n
            t.textSize = 13f
            t.setTextColor(Color.WHITE)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(14), 0, dp(14), 0)
            t.setOnClickListener { showMenu(it, n) }
            bar.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        titleText = TextView(this)
        titleText.textSize = 12f
        titleText.setTextColor(0xFFD1D5DB.toInt())
        titleText.gravity = Gravity.CENTER
        titleText.maxLines = 1
        bar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(btn("↶ Undo", 12f) { undo() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 0f, 4))
        bar.addView(btn("↷ Redo", 12f) { redo() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 0f, 4))
        bar.addView(btn("💾 Simpan", 12f) { saveChart() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 0f, 4))
        return bar
    }

    private fun buildToolColumn(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(cPanel)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(4), dp(4), dp(4), dp(4))
        val defs = listOf(
            HighwayView.T_PAN to "Geser\nPan",
            HighwayView.T_SELECT to "Pilih\nCursor",
            HighwayView.T_ERASE to "Hapus\nEraser",
            HighwayView.T_NOTE to "Not\nNote",
            HighwayView.T_STAR to "Star\nPower",
            HighwayView.T_BPM to "BPM",
            HighwayView.T_TS to "Birama\nTime Sig",
            HighwayView.T_SECTION to "Bagian\nSection",
            HighwayView.T_EVENT to "Event\nSolo"
        )
        for ((tool, text) in defs) {
            val b = btn(text, 11f) { selectTool(tool) }
            toolButtons.add(b)
            col.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0f, 2))
        }
        sv.addView(col)
        return sv
    }

    private fun buildRightPanel(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(cPanel)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(8), dp(2), dp(8), dp(8))

        col.addView(label("TINGKAT (Difficulty)"))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val short = listOf("E", "M", "H", "X")
        for (i in 0..3) {
            val b = btn(short[i], 13f) { setDiff(i) }
            diffButtons.add(b)
            row.addView(b, lp(0, dp(36), 1f, 1))
        }
        col.addView(row)
        col.addView(label("INSTRUMEN"))
        instButton = btn("Lead Guitar ▾", 12f) { showInstMenu(it) }
        col.addView(instButton, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), 0f, 1))

        col.addView(label("SNAP"))
        val sr = LinearLayout(this)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.gravity = Gravity.CENTER_VERTICAL
        sr.addView(btn("−", 16f) { changeSnap(-1) }, lp(dp(40), dp(38), 0f, 1))
        snapText = TextView(this)
        snapText.setTextColor(Color.WHITE)
        snapText.textSize = 14f
        snapText.gravity = Gravity.CENTER
        sr.addView(snapText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sr.addView(btn("+", 16f) { changeSnap(1) }, lp(dp(40), dp(38), 0f, 1))
        col.addView(sr)

        col.addView(label("JENIS NOT"))
        openBtn = btn("Open (nada kosong)", 12f) {
            highway.openMode = !highway.openMode
            updateOpenBtn()
        }
        col.addView(openBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), 0f, 1))
        col.addView(label("NOT TERPILIH"))
        col.addView(btn("Forced (HOPO ⇄ strum)", 12f) { highway.toggleForced() }, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), 0f, 1))
        col.addView(btn("Tap", 12f) { highway.toggleTap() }, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), 0f, 1))
        val sus = LinearLayout(this)
        sus.orientation = LinearLayout.HORIZONTAL
        sus.addView(btn("Sustain −", 11f) { highway.changeSustain(-highway.snapTicks()) }, lp(0, dp(36), 1f, 1))
        sus.addView(btn("Sustain +", 11f) { highway.changeSustain(highway.snapTicks()) }, lp(0, dp(36), 1f, 1))
        col.addView(sus)
        col.addView(btn("Hapus sustain", 12f) { highway.clearSustain() }, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), 0f, 1))
        col.addView(btn("Hapus terpilih", 12f) { highway.deleteSelection() }, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), 0f, 1))

        col.addView(label("INFO"))
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
        bar.setPadding(dp(6), 0, dp(6), 0)
        val h = ViewGroup.LayoutParams.MATCH_PARENT
        bar.addView(btn("⏮", 16f) { pausePlayback(); highway.curTick = 0.0 }, lp(dp(48), h, 0f, 4))
        bar.addView(btn("◀", 16f) { pausePlayback(); highway.stepSnap(-1) }, lp(dp(48), h, 0f, 4))
        playBtn = btn("▶", 18f) { togglePlay() }
        bar.addView(playBtn, lp(dp(64), h, 0f, 4))
        bar.addView(btn("▶|", 14f) { pausePlayback(); highway.stepSnap(1) }, lp(dp(48), h, 0f, 4))
        timeText = TextView(this)
        timeText.setTextColor(Color.WHITE)
        timeText.textSize = 13f
        timeText.typeface = Typeface.MONOSPACE
        timeText.gravity = Gravity.CENTER
        bar.addView(timeText, LinearLayout.LayoutParams(0, h, 1f))
        speedBtn = btn("1.0x", 12f) { cycleSpeed() }
        bar.addView(speedBtn, lp(dp(56), h, 0f, 4))
        bar.addView(btn("Zoom −", 11f) { highway.range = highway.range * 1.35 }, lp(dp(64), h, 0f, 4))
        bar.addView(btn("Zoom +", 11f) { highway.range = highway.range / 1.35 }, lp(dp(64), h, 0f, 4))
        return bar
    }

    // ===================== menu =====================
    private fun showMenu(anchor: View, name: String) {
        val m = PopupMenu(this, anchor)
        val menu = m.menu
        when (name) {
            "File" -> {
                menu.add(0, 1, 0, "Baru…")
                menu.add(0, 2, 1, "Buka file .chart…")
                menu.add(0, 3, 2, "Buka folder lagu (chart + audio)…")
                menu.add(0, 4, 3, "Buka audio…")
                menu.add(0, 5, 4, "Simpan")
                menu.add(0, 6, 5, "Simpan sebagai…")
                menu.add(0, 7, 6, "Ekspor ke folder Clone Hero…")
                menu.add(0, 8, 7, "Pulihkan autosave")
            }
            "Edit" -> {
                menu.add(0, 11, 0, "Undo")
                menu.add(0, 12, 1, "Redo")
                menu.add(0, 13, 2, "Pilih semua")
                menu.add(0, 14, 3, "Salin")
                menu.add(0, 15, 4, "Potong")
                menu.add(0, 16, 5, "Tempel di posisi sekarang")
                menu.add(0, 17, 6, "Hapus terpilih")
                menu.add(0, 18, 7, "Geser terpilih ke atas (1 snap)")
                menu.add(0, 19, 8, "Geser terpilih ke bawah (1 snap)")
                menu.add(0, 20, 9, "Kosongkan track ini…")
            }
            "View" -> {
                menu.add(0, 31, 0, "Zoom +")
                menu.add(0, 32, 1, "Zoom −")
                menu.add(0, 33, 2, "Reset zoom")
                menu.add(0, 34, 3, "Ke awal lagu")
                menu.add(0, 35, 4, "Ke akhir not")
            }
            "Song" -> {
                menu.add(0, 41, 0, "Properti lagu…")
                menu.add(0, 42, 1, "Offset audio…")
            }
            else -> {
                menu.add(0, 51, 0, "Panduan")
                menu.add(0, 52, 1, "Tentang")
            }
        }
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newChart()
                2 -> launchOpen(RQ_OPEN_CHART)
                3 -> launchTree(RQ_OPEN_FOLDER)
                4 -> launchOpen(RQ_OPEN_AUDIO)
                5 -> saveChart()
                6 -> saveAs()
                7 -> launchTree(RQ_EXPORT)
                8 -> restoreAutosave()
                11 -> undo()
                12 -> redo()
                13 -> highway.selectAll()
                14 -> {
                    clip = highway.copySelection()
                    toast("${clip.size} not disalin")
                }
                15 -> {
                    clip = highway.copySelection()
                    highway.deleteSelection()
                }
                16 -> highway.paste(clip, highway.snapTickPublic(highway.curTick))
                17 -> highway.deleteSelection()
                18 -> highway.nudge(highway.snapTicks())
                19 -> highway.nudge(-highway.snapTicks())
                20 -> confirm("Kosongkan semua not di track ini?") { clearTrack() }
                31 -> highway.range = highway.range / 1.35
                32 -> highway.range = highway.range * 1.35
                33 -> highway.range = 768.0
                34 -> highway.curTick = 0.0
                35 -> {
                    val n = highway.track().notes
                    highway.curTick = if (n.isEmpty()) 0.0 else n[n.size - 1].tick.toDouble()
                }
                41 -> songProperties()
                42 -> offsetDialog()
                51 -> showGuide()
                52 -> showAbout()
            }
            true
        }
        m.show()
    }

    private fun showInstMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        var i = 0
        val keys = ArrayList<String>()
        for ((k, v) in ChartIO.instruments) {
            if (!ChartIO.isEditableInstrument(k)) continue
            m.menu.add(0, i, i, v)
            keys.add(k)
            i++
        }
        m.setOnMenuItemClickListener { item ->
            inst = keys[item.itemId]
            applyTrack()
            true
        }
        m.show()
    }

    // ===================== alat / track =====================
    private fun selectTool(t: Int) {
        highway.tool = t
        for (i in toolButtons.indices) toolButtons[i].background = bg(if (i == t) cSel else cBtn)
    }

    private fun setDiff(i: Int) {
        diffIdx = i
        applyTrack()
    }

    private fun applyTrack() {
        highway.trackKey = ChartIO.trackKey(ChartIO.difficulties[diffIdx], inst)
        for (i in diffButtons.indices) diffButtons[i].background = bg(if (i == diffIdx) cSel else cBtn)
        instButton.text = (ChartIO.instruments[inst] ?: inst) + " ▾"
        updateInfo()
    }

    private fun updateOpenBtn() {
        openBtn.background = bg(if (highway.openMode) HighwayView.OPEN_COLOR else cBtn)
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
        val a = if (audioName.isEmpty()) "tanpa audio" else audioName
        titleText.text = "$n  •  $a" + if (dirty) "  *" else ""
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
                        afterSongEdit()
                    }
                ) { v ->
                    val bpm = v[0].replace(',', '.').toDoubleOrNull()
                    if (bpm == null || bpm < 1.0 || bpm > 2000.0) {
                        toast("BPM tidak valid")
                    } else {
                        beforeEdit()
                        val milli = Math.round(bpm * 1000).toInt()
                        if (ev != null) ev.milli = milli else song.bpms.add(BpmEv(tick, milli))
                        afterSongEdit()
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
                        afterSongEdit()
                    }
                ) { v ->
                    val num = v[0].toIntOrNull()
                    val den = v[1].toIntOrNull()
                    val exp = when (den) {
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
                        afterSongEdit()
                    }
                }
            }
            HighwayView.T_SECTION -> {
                val ev = if (hit >= 0) song.events[hit] else null
                val cur = ev?.text?.removePrefix("section ") ?: ""
                form(
                    if (ev != null) "Ubah bagian" else "Bagian baru di tick $tick",
                    listOf("Nama bagian (mis. Intro, Verse 1, Chorus)" to cur),
                    listOf(InputType.TYPE_CLASS_TEXT),
                    if (ev != null) "Hapus" else null,
                    onNeutral = {
                        beforeEdit()
                        if (ev != null) song.events.remove(ev)
                        afterSongEdit()
                    }
                ) { v ->
                    val name = v[0].trim()
                    if (name.isEmpty()) {
                        toast("Nama kosong")
                    } else {
                        beforeEdit()
                        if (ev != null) ev.text = "section $name" else song.events.add(TextEv(tick, "section $name"))
                        afterSongEdit()
                    }
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
                            afterSongEdit()
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

    private fun addTrackEvent(tick: Int, text: String) {
        beforeEdit()
        highway.track().events.add(TextEv(tick, text))
        afterSongEdit()
    }

    private fun afterSongEdit() {
        song.normalize()
        highway.rebuild()
        updateAudioLength()
        updateInfo()
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
        if (rq == RQ_OPEN_AUDIO) {
            i.type = "audio/*"
        }
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

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment ?: ""
    }

    private fun readText(uri: Uri): String {
        val ins = contentResolver.openInputStream(uri) ?: throw IllegalStateException("tidak bisa membuka file")
        return ins.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun writeText(uri: Uri, text: String) {
        val os = contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("tidak bisa menulis file")
        os.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun loadChartFromUri(uri: Uri) {
        val name = displayName(uri)
        if (name.endsWith(".mid", true) || name.endsWith(".midi", true)) {
            toast("Format .mid belum didukung. Gunakan file .chart")
            return
        }
        val text = readText(uri)
        val s = ChartIO.parse(text)
        chartUri = uri
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
        if (chart == null) {
            toast("Tidak ada file .chart di folder ini")
            return
        }
        val audioExt = listOf(".ogg", ".opus", ".mp3", ".wav", ".m4a")
        val audio = kids.firstOrNull { k -> k.name.startsWith("song.", true) && audioExt.any { k.name.endsWith(it, true) } }
            ?: kids.firstOrNull { k -> k.name.startsWith("guitar.", true) && audioExt.any { k.name.endsWith(it, true) } }
            ?: kids.firstOrNull { k -> audioExt.any { k.name.endsWith(it, true) } }
        val cu = DocumentsContract.buildDocumentUriUsingTree(tree, chart.id)
        chartUri = cu
        applySong(ChartIO.parse(readText(cu)), keepAudio = false)
        if (audio != null) {
            loadAudio(DocumentsContract.buildDocumentUriUsingTree(tree, audio.id), audio.name)
        } else toast("Chart dibuka, audio tidak ditemukan — pilih lewat File > Buka audio")
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
            toast("Gagal menyimpan: ${e.message}")
        }
    }

    private fun exportFolder(tree: Uri) {
        val text = ChartIO.write(song)
        val au = audioUri
        val an = audioName
        val kids = listChildren(tree)
        Thread {
            try {
                val treeDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                fun target(name: String, mime: String): Uri {
                    val ex = kids.firstOrNull { it.name == name }
                    return if (ex != null) DocumentsContract.buildDocumentUriUsingTree(tree, ex.id)
                    else DocumentsContract.createDocument(contentResolver, treeDoc, mime, name)
                        ?: throw IllegalStateException("gagal membuat $name")
                }
                var audioFile = ""
                if (au != null) {
                    val ext = if (an.contains('.')) an.substringAfterLast('.').lowercase() else "ogg"
                    audioFile = "song.$ext"
                    val t = target(audioFile, "application/octet-stream")
                    contentResolver.openInputStream(au)?.use { ins ->
                        contentResolver.openOutputStream(t, "wt")?.use { os -> ins.copyTo(os) }
                    }
                }
                val tc = target("notes.chart", "application/octet-stream")
                contentResolver.openOutputStream(tc, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                val ini = StringBuilder()
                ini.append("[song]\r\n")
                ini.append("name = ").append(song.meta["Name"] ?: "").append("\r\n")
                ini.append("artist = ").append(song.meta["Artist"] ?: "").append("\r\n")
                ini.append("charter = ").append(song.meta["Charter"] ?: "").append("\r\n")
                ini.append("genre = ").append(song.meta["Genre"] ?: "rock").append("\r\n")
                val ti = target("song.ini", "text/plain")
                contentResolver.openOutputStream(ti, "wt")?.use { it.write(ini.toString().toByteArray(Charsets.UTF_8)) }
                runOnUiThread { toast("Diekspor ke folder (notes.chart, song.ini" + (if (audioFile.isNotEmpty()) ", $audioFile" else "") + ")") }
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
            toast("Atur BPM dengan alat BPM, lalu muat audio lewat File > Buka audio")
        }
    }

    private fun applySong(s: Song, keepAudio: Boolean) {
        pausePlayback()
        song = s
        undoStack.clear()
        redoStack.clear()
        if (!keepAudio) releasePlayer()
        // pilih track pertama yang ada isinya (prioritas Expert Single)
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
    private fun loadAudio(uri: Uri, name: String) {
        releasePlayer()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(this, uri)
            mp.prepare()
            mp.setOnCompletionListener { pausePlayback() }
            player = mp
            audioUri = uri
            audioName = name
            if (song.meta["MusicStream"].isNullOrEmpty() || song.meta["MusicStream"] == "song.ogg") {
                if (name.isNotEmpty()) song.meta["MusicStream"] = name
            }
            updateAudioLength()
            updateTitle()
            toast("Audio dimuat: $name")
        } catch (e: Exception) {
            toast("Audio gagal dimuat: ${e.message}")
            audioUri = null
            audioName = ""
        }
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        audioUri = null
        audioName = ""
        audioStarted = false
        highway.lengthTicks = 0
    }

    private fun updateAudioLength() {
        val mp = player
        if (mp == null) {
            highway.lengthTicks = 0
            return
        }
        val sec = mp.duration / 1000.0 - song.offset
        highway.lengthTicks = TempoMap(song).secToTick(max(0.0, sec)).toInt()
        highway.invalidate()
    }

    private fun applySpeed() {
        val mp = player ?: return
        try {
            mp.playbackParams = PlaybackParams().setSpeed(speed)
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
        val tm = highway.tempo
        startChartSec = tm.tickToSec(highway.curTick)
        wallStart = SystemClock.elapsedRealtime()
        audioStarted = false
        playing = true
        playBtn.text = "⏸"
        highway.postOnAnimation(frame)
    }

    private fun pausePlayback() {
        if (!playing) return
        playing = false
        playBtn.text = "▶"
        try {
            val mp = player
            if (mp != null && audioStarted && mp.isPlaying) mp.pause()
        } catch (e: Exception) {
        }
        audioStarted = false
    }

    private fun stepFrame() {
        val now = SystemClock.elapsedRealtime()
        val wallChart = startChartSec + (now - wallStart) / 1000.0 * speed
        var chartSec = wallChart
        val mp = player
        if (mp != null) {
            try {
                if (!audioStarted) {
                    val ap = wallChart + song.offset
                    if (ap >= 0.0) {
                        mp.seekTo((ap * 1000).toInt())
                        mp.start()
                        applySpeed()
                        audioStarted = true
                        startChartSec = wallChart
                        wallStart = now
                    }
                } else {
                    val ac = mp.currentPosition / 1000.0 - song.offset
                    if (abs(ac - wallChart) > 0.06) {
                        startChartSec = ac
                        wallStart = now
                        chartSec = ac
                    }
                }
            } catch (e: Exception) {
            }
        }
        highway.curTick = highway.tempo.secToTick(chartSec)
    }

    // ===================== info =====================
    private fun updateInfo() {
        if (!::infoText.isInitialized) return
        val tick = highway.curTick
        val sec = highway.tempo.tickToSec(tick)
        val tr = highway.track()
        val a = highway.bpmAtCursorAt(tick.toInt())
        val ts = highway.tsAtCursorAt(tick.toInt())
        infoText.text = "Tick  ${tick.toInt()}\n" +
            "BPM   ${ChartIO.bpmText(a)}\n" +
            "TS    ${ts.num}/${1 shl ts.denExp}\n" +
            "Not   ${tr.notes.size}\n" +
            "SP    ${tr.starpower.size}\n" +
            "Pilih ${highway.selection.size}\n" +
            "Res   ${song.resolution}"
        val m = max(0.0, sec)
        val mm = (m / 60).toInt()
        val ss = m - mm * 60
        timeText.text = String.format(java.util.Locale.US, "%02d:%06.3f", mm, ss)
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
        afterSongEdit()
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
            }
        }
    }

    private fun showGuide() {
        val msg = """
ALAT (kolom kiri) — istilah Moonscraper dalam tanda kurung

• Geser (Pan): seret layar naik/turun untuk menggulir lagu. Di alat lain, geser dengan dua jari.
• Pilih (Cursor): ketuk not untuk memilih / batal pilih, atau seret kotak untuk memilih banyak. Lalu pakai tombol Forced, Tap, Sustain, Hapus di panel kanan.
• Hapus (Eraser): ketuk atau seret di atas not untuk menghapus.
• Not (Note tool): ketuk jalur untuk menaruh not (ketuk lagi untuk menghapus). Tekan lalu seret ke atas untuk membuat sustain. Aktifkan tombol "Open" untuk not open (ungu).
• Star Power: seret dari awal ke akhir frasa. Ketuk saja untuk membuat 1 birama / menghapus frasa.
• BPM: ketuk tempat BPM berubah. Ketuk penanda oranye untuk mengubah / menghapus.
• Birama (Time Signature): sama seperti BPM, penanda biru.
• Bagian (Section): nama bagian lagu (Intro, Chorus, …) — tampil hijau di kiri.
• Event: solo / soloend (ungu, kanan highway) pada track yang sedang dipilih.

PANEL KANAN
• E / M / H / X = Easy / Medium / Hard / Expert. Instrumen dipilih lewat tombol di bawahnya.
• Snap = kerapatan penempatan (1/4 = per ketukan, 1/8, 1/16 dst). Di Moonscraper ini sama dengan tombol snap.
• Forced mengubah not HOPO ↔ strum. Not HOPO ditandai lingkaran putih. Tap = not tap (ungu).

BAR BAWAH
• ⏮ awal, ◀ ▶| loncat satu snap, ▶ putar. Tombol kecepatan memperlambat audio (berguna saat charting).
• Garis horizontal putih di bawah adalah "garis pukul" — posisi pemutaran.
• Strip di tepi kanan layar = posisi lagu; sentuh / seret untuk loncat cepat.
• Cubit dengan dua jari = zoom jarak not.

TIPS
• Sebelum menaruh not, buat dulu BPM yang benar dan sesuaikan Offset (Song > Offset audio) sampai garis ketukan pas dengan musik.
• Simpan sering lewat tombol Simpan. Autosave dibuat otomatis saat aplikasi ditutup.
• "Ekspor ke folder Clone Hero" membuat notes.chart + song.ini + song.<audio> siap dipakai.
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
                "Editor chart gaya Moonscraper untuk Android (format .chart / Clone Hero).\n\n" +
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
        try {
            player?.release()
        } catch (e: Exception) {
        }
    }
}
