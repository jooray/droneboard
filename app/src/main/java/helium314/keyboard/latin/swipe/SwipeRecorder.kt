// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import android.content.Context
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
 */
object SwipeRecorder {
    private const val TAG = "SwipeRecorder"
    const val IME_OPTION = "${BuildConfig.APPLICATION_ID}.swipeRecorder"
    const val DIR = "swipe-recordings"

    /** word the user is asked to swipe; null when the recorder screen is not open */
    @Volatile
    private var target: String? = null
    @Volatile
    private var file: File? = null

    /** number of swipes recorded in the current file, and the result of the last one (for the screen) */
    val recorded = MutableStateFlow(0)
    val lastResult = MutableStateFlow<List<String>>(emptyList())

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "swipe-recorder") }

    fun directory(context: Context): File? = context.getExternalFilesDir(DIR)

    /** Starts a new recording file. */
    fun start(context: Context): File? {
        val dir = directory(context) ?: return null
        val name = "swipes-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
        file = File(dir, name)
        recorded.value = 0
        lastResult.value = emptyList()
        return file
    }

    fun setTarget(word: String?) {
        target = word
    }

    fun stop() {
        target = null
        file = null
    }

    fun isRecording(editorInfo: EditorInfo?): Boolean =
        target != null && file != null && editorInfo?.privateImeOptions?.contains(IME_OPTION) == true

    /** Called by the decoder after the final decode of a swipe. */
    fun onFinalDecode(
        keyboard: Keyboard, pointers: InputPointers, input: SwipeGestureDecoder.LastInput,
        layoutKeys: List<Triple<String, Float, Float>>, words: Array<String>, languages: List<String>,
        wordLanguages: List<String>, millis: Float
    ) {
        val word = target ?: return
        val out = file ?: return
        if (!isRecording(runCatching { keyboard.mId.editorInfo }.getOrNull())) return
        val json = try {
            JSONObject().apply {
                put("target", word)
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
                put("raw", JSONObject().apply {
                    val n = pointers.pointerSize
                    put("x", JSONArray(pointers.xCoordinates.take(n)))
                    put("y", JSONArray(pointers.yCoordinates.take(n)))
                    put("t", JSONArray(pointers.times.take(n)))
                    put("id", JSONArray(pointers.pointerIds.take(n)))
                })
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
            }.toString()
        } catch (e: Exception) {
            Log.w(TAG, "could not serialize swipe", e)
            return
        }
        lastResult.value = words.take(3)
        io.execute {
            try {
                out.appendText(json + "\n")
                recorded.value = recorded.value + 1
            } catch (e: Exception) {
                Log.w(TAG, "could not write ${out.path}", e)
            }
        }
    }

    private fun floats(a: FloatArray) = JSONArray().apply { a.forEach { put(it.toDouble()) } }
}
