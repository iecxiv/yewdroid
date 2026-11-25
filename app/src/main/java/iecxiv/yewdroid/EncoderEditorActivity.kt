package iecxiv.yewdroid

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chaquo.python.Python
import org.json.JSONObject
import java.util.concurrent.Executors

class EncoderEditorActivity : ComponentActivity() {
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var save: Button
    private lateinit var cancel: Button
    private val worker = Executors.newSingleThreadExecutor()
    private var baseline = ""
    private var hash = ""
    private var busy = true

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 12, 12))
        }
        val padding = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + maxOf(bars.bottom, ime.bottom))
            insets
        }
        root.addView(TextView(this).apply {
            text = "Encoding presets"
            textSize = 20f
            setTextColor(Color.rgb(152, 195, 121))
        })
        status = TextView(this).apply {
            text = "Loading..."
            setTextColor(Color.LTGRAY)
        }
        root.addView(status)
        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(24, 24, 24))
            filters = arrayOf(InputFilter.LengthFilter(120000))
            isEnabled = false
        }
        root.addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this)
        save = Button(this).apply { text = "Save"; isEnabled = false; setOnClickListener { saveDocument() } }
        cancel = Button(this).apply { text = "Cancel"; setOnClickListener { closeEditor() } }
        buttons.addView(save)
        buttons.addView(cancel)
        root.addView(buttons)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closeEditor() }
        })
        val draft = state?.getString("draft")
        val priorHash = state?.getString("hash")
        val priorBaseline = state?.getString("baseline")
        worker.execute {
            try {
                val result = JSONObject(Python.getInstance().getModule("android_encoder_editor").callAttr("read_document").toString())
                runOnUiThread {
                    if (!isDestroyed) {
                        baseline = priorBaseline ?: result.getString("text")
                        hash = priorHash ?: result.getString("sha256")
                        editor.setText(draft ?: baseline)
                        status.text = result.getString("path")
                        busy = false
                        editor.isEnabled = true
                        save.isEnabled = true
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed) { busy = false; status.text = error.message ?: "Unable to load presets" } }
            }
        }
    }

    private fun saveDocument() {
        if (busy) return
        val draft = editor.text.toString()
        busy = true
        save.isEnabled = false
        cancel.isEnabled = false
        editor.isEnabled = false
        status.text = "Saving..."
        worker.execute {
            try {
                val result = JSONObject(Python.getInstance().getModule("android_encoder_editor").callAttr("save_document", draft, hash).toString())
                runOnUiThread {
                    if (!isDestroyed) {
                        baseline = draft
                        hash = result.getString("sha256")
                        status.text = result.getString("message")
                        setEditable()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed) { status.text = error.message ?: "Unable to save presets"; setEditable() } }
            }
        }
    }

    private fun setEditable() {
        busy = false
        save.isEnabled = true
        cancel.isEnabled = true
        editor.isEnabled = true
    }

    private fun closeEditor() {
        if (busy) return
        if (editor.text.toString() == baseline) finish() else {
            AlertDialog.Builder(this).setTitle("Discard changes?")
                .setMessage("Unsaved edits will be lost.")
                .setPositiveButton("Discard") { _, _ -> finish() }
                .setNegativeButton("Keep editing", null).show()
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        if (hash.isNotEmpty()) {
            state.putString("draft", editor.text.toString())
            state.putString("baseline", baseline)
            state.putString("hash", hash)
        }
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
