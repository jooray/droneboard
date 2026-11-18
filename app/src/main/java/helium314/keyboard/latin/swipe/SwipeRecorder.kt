// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Records swipes for tuning the decoder offline (tools/swipe-replay). Only active while the swipe
 * recorder screen is open, and only for its own text field (marked with [IME_OPTION]): nothing
 * typed in other apps is ever recorded. Files stay on the device in the app's external files
 * directory (readable with adb pull, removed with the app); nothing is uploaded.
 *
 * The decoder hands every final decode to [onFinalDecode], which builds the record (decoder input and
 * output, the raw touch points of the stroke at full rate) and passes it to the screen's [listener] on
 * the main thread. The screen adds what only it knows (prompt, target word, committed word, session
 * style) and saves it with [write].
 *
 * [TypingSwipeRecorder] (optional, off by default) uses the same touch capture ([onTouchEvent]), the same
 * record ([buildRecord]) and the same writer thread ([append]) for swipes made while typing.
 */
object SwipeRecorder {
    private const val TAG = "SwipeRecorder"
    const val IME_OPTION = "${BuildConfig.APPLICATION_ID}.swipeRecorder"
    const val DIR = "swipe-recordings"
    /** version of the record format, see tools/swipe-replay/README.md */
    const val RECORD_VERSION = 2
    /** a stroke that ended longer ago than this does not belong to the decode being recorded */
    private const val STROKE_MAX_AGE_MS = 3000L
    private const val MAX_STROKE_POINTS = 20_000

    @Volatile
    private var file: File? = null

    /** receives finished records on the main thread; null when the recorder screen is not open */
    @Volatile
    var listener: ((JSONObject) -> Unit)? = null

    /** number of swipes written to the current file, and the result of the last one (for the screen) */
    val recorded = MutableStateFlow(0)
    val lastResult = MutableStateFlow<List<String>>(emptyList())

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "swipe-recorder") }
    private val main = Handler(Looper.getMainLooper())

    fun directory(context: Context): File? = context.getExternalFilesDir(DIR)

    /** Starts a new recording file. */
    fun start(context: Context): File? {
        val dir = directory(context) ?: return null
        val name = "swipes-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
        recorded.value = 0
        lastResult.value = emptyList()
        synchronized(strokeLock) { current = null; finished = null }
        return File(dir, name).also { file = it }
    }

    fun stop() {
        listener = null
        file = null
        clearStroke()
    }

    /** forgets the touch points kept in memory */
    fun clearStroke() {
        synchronized(strokeLock) { current = null; finished = null }
    }

    /** true only while the recorder screen is open (cheap check for the touch path) */
    @JvmStatic
    fun isActive(): Boolean = file != null && listener != null

    @JvmStatic
    fun isRecording(editorInfo: EditorInfo?): Boolean =
        isActive() && editorInfo?.privateImeOptions?.contains(IME_OPTION) == true

    // ---- raw touch points ----

    private class Stroke(val downUptime: Long, val offsetX: Int, val offsetY: Int) {
        val x = ArrayList<Float>()
        val y = ArrayList<Float>()
        val t = ArrayList<Long>()
        val id = ArrayList<Int>()
        val action = ArrayList<Int>()
        val pressure = ArrayList<Float>()
        var upUptime = -1L

        fun add(px: Float, py: Float, time: Long, pointerId: Int, a: Int, p: Float) {
            if (x.size >= MAX_STROKE_POINTS) return
            x.add(px); y.add(py); t.add(time - downUptime); id.add(pointerId); action.add(a); pressure.add(p)
        }
    }

    private val strokeLock = Any()
    private var current: Stroke? = null
    private var finished: Stroke? = null

    /**
     * Every touch event on the keyboard while recording, with all historical samples (the gesture
     * detector keeps only some of them). Coordinates are keyboard view pixels; adding offset_x/offset_y
     * gives the key coordinates of the "keyboard.keys" list.
     */
    @JvmStatic
    fun onTouchEvent(me: MotionEvent, offsetX: Int, offsetY: Int) {
        val masked = me.actionMasked
        synchronized(strokeLock) {
            if (masked == MotionEvent.ACTION_DOWN) current = Stroke(me.downTime, offsetX, offsetY)
            val s = current ?: return
            val actionIndex = me.actionIndex
            for (h in 0 until me.historySize) {
                for (p in 0 until me.pointerCount) {
                    s.add(me.getHistoricalX(p, h), me.getHistoricalY(p, h), me.getHistoricalEventTime(h),
                        me.getPointerId(p), MotionEvent.ACTION_MOVE, me.getHistoricalPressure(p, h))
                }
            }
            for (p in 0 until me.pointerCount) {
                // only the pointer that went down / up carries that action, the others moved
                val a = if (masked == MotionEvent.ACTION_MOVE || p == actionIndex) masked else MotionEvent.ACTION_MOVE
                s.add(me.getX(p), me.getY(p), me.eventTime, me.getPointerId(p), a, me.getPressure(p))
            }
            if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
                s.upUptime = me.eventTime
                finished = s
                current = null
            }
        }
    }

    private fun takeStroke(): Stroke? = synchronized(strokeLock) {
        val s = finished
        finished = null
        s?.takeIf { it.upUptime > SystemClock.uptimeMillis() - STROKE_MAX_AGE_MS }
    }

    // ---- records ----

    /** Called by the decoder after the final decode of a swipe. */
    fun onFinalDecode(
        keyboard: Keyboard, pointers: InputPointers, input: SwipeGestureDecoder.LastInput,
        layoutKeys: List<Triple<String, Float, Float>>, words: Array<String>, languages: List<String>,
        wordLanguages: List<String>, millis: Float
    ) {
        if (file == null || !isRecording(runCatching { keyboard.mId.editorInfo }.getOrNull())) return
        val json = buildRecord(keyboard, pointers, input, layoutKeys, words, languages, wordLanguages, millis) ?: return
        lastResult.value = words.take(3)
        main.post { listener?.invoke(json) }
    }

    /** The record of a final decode: decoder input and output, the touch points of the stroke, the keyboard. */
    fun buildRecord(
        keyboard: Keyboard, pointers: InputPointers, input: SwipeGestureDecoder.LastInput,
        layoutKeys: List<Triple<String, Float, Float>>, words: Array<String>, languages: List<String>,
        wordLanguages: List<String>, millis: Float
    ): JSONObject? {
        val stroke = takeStroke()
        return try {
            JSONObject().apply {
                put("context", JSONArray(input.context))
                put("layout", JSONArray().apply {
                    layoutKeys.forEach { (c, x, y) -> put(JSONObject().put("c", c).put("x", x.toDouble()).put("y", y.toDouble())) }
                })
                put("x", floats(input.x))
                put("y", floats(input.y))
                put("t", floats(input.t))
                put("pred", JSONArray(words.take(8)))
                put("pred_lang", JSONArray(wordLanguages.take(8)))
                put("languages", JSONArray(languages))
                put("decode_ms", millis.toDouble())
                // the points the gesture detector passed to the decoder (subsampled)
                put("raw", JSONObject().apply {
                    val n = pointers.pointerSize
                    put("x", JSONArray(pointers.xCoordinates.take(n)))
                    put("y", JSONArray(pointers.yCoordinates.take(n)))
                    put("t", JSONArray(pointers.times.take(n)))
                    put("id", JSONArray(pointers.pointerIds.take(n)))
                })
                if (stroke != null) put("touch", strokeJson(stroke))
                put("keyboard", JSONObject().apply {
                    put("width", keyboard.mOccupiedWidth)
                    put("height", keyboard.mOccupiedHeight)
                    put("locale", keyboard.mId.locale.toLanguageTag())
                    put("keys", JSONArray().apply {
                        keyboard.sortedKeys.filter { it.code > 0 && Character.isLetter(it.code) }.forEach {
                            put(JSONObject().put("c", String(Character.toChars(it.code)))
                                .put("x", it.x).put("y", it.y).put("w", it.width).put("h", it.height))
                        }
                    })
                })
                put("app", BuildConfig.VERSION_NAME)
                put("time", System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not serialize swipe", e)
            null
        }
    }

    private fun strokeJson(s: Stroke) = JSONObject().apply {
        // uptime -> wall clock
        val wallOffset = System.currentTimeMillis() - SystemClock.uptimeMillis()
        put("down_time", s.downUptime + wallOffset)
        if (s.upUptime > 0) put("duration_ms", s.upUptime - s.downUptime)
        put("offset_x", s.offsetX)
        put("offset_y", s.offsetY)
        put("x", JSONArray().apply { s.x.forEach { put(round1(it)) } })
        put("y", JSONArray().apply { s.y.forEach { put(round1(it)) } })
        put("t", JSONArray(s.t))
        put("id", JSONArray(s.id))
        put("action", JSONArray(s.action))
        put("pressure", JSONArray().apply { s.pressure.forEach { put(Math.round(it * 1000) / 1000.0) } })
    }

    /** Appends [json] to the current file. */
    fun write(json: JSONObject) {
        val out = file ?: return
        val line = json.toString()
        io.execute { if (append(out, line)) recorded.value = recorded.value + 1 }
    }

    /** Runs [task] on the writer thread, after the writes queued before it. */
    fun onWriterThread(task: Runnable) = io.execute(task)

    /** Appends one record line to [out]; call on the writer thread. */
    fun append(out: File, line: String): Boolean = try {
        out.appendText(line + "\n")
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not write ${out.path}", e)
        false
    }

    /** Number of records in all recording files (blocking; call off the main thread). */
    fun countAll(context: Context): Int = directory(context)?.listFiles { f -> f.name.endsWith(".jsonl") }
        ?.sumOf { f -> runCatching { f.useLines { lines -> lines.count { it.isNotBlank() } } }.getOrDefault(0) } ?: 0

    private fun round1(f: Float) = Math.round(f * 10) / 10.0
    private fun floats(a: FloatArray) = JSONArray().apply { a.forEach { put(it.toDouble()) } }
}
