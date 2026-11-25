package iecxiv.yewdroid

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var actions: LinearLayout
    private lateinit var save: Button
    private lateinit var restore: Button
    private lateinit var cancel: Button
    private val worker = Executors.newSingleThreadExecutor()
    private var baseline = ""
    private var hash = ""
    private var busy = true

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setBackgroundColor(Color.rgb(12, 12, 12))
        }
        val padding = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + maxOf(bars.bottom, ime.bottom))
            insets
        }
        root.addView(TextView(this).apply { text = "Encoding presets"; textSize = 20f; setTextColor(Color.rgb(152, 195, 121)) })
        status = TextView(this).apply { text = "Loading..."; setTextColor(Color.LTGRAY) }
        root.addView(status)
        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setHorizontallyScrolling(true)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(24, 24, 24))
            filters = arrayOf(InputFilter.LengthFilter(120000))
            isEnabled = false
        }
        root.addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        save = Button(this).apply { text = "Save & Close"; isEnabled = false; setOnClickListener { saveDocument() } }
        restore = Button(this).apply { text = "Restore defaults"; isEnabled = false; setOnClickListener { confirmRestore() } }
        cancel = Button(this).apply { text = "Cancel"; setOnClickListener { closeEditor() } }
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val buttons = LinearLayout(this)
        buttons.addView(save, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(buttons)
        actions.addView(restore, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(actions)
        updateActions(resources.configuration.orientation)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { closeEditor() } })
        worker.execute {
            try {
                val result = callPython("android_encoder_editor", "read_document")
                runOnUiThread { if (!isDestroyed) {
                    baseline = state?.getString("baseline") ?: result.getString("text")
                    hash = state?.getString("hash") ?: result.getString("sha256")
                    editor.setText(state?.getString("draft") ?: baseline)
                    status.text = result.getString("path")
                    setBusy(false)
                    if (state != null) restorePosition(state)
                } }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed) { busy = false; status.text = error.message ?: "Unable to load presets" } }
            }
        }
    }

    private fun updateActions(orientation: Int) {
        actions.visibility = if (orientation == Configuration.ORIENTATION_LANDSCAPE) View.GONE else View.VISIBLE
    }

    private fun callPython(module: String, method: String, vararg args: Any): JSONObject =
        JSONObject(Python.getInstance().getModule(module).callAttr(method, *args).toString())

    private fun setBusy(value: Boolean) {
        busy = value
        save.isEnabled = !value
        restore.isEnabled = !value
        cancel.isEnabled = !value
        editor.isEnabled = !value
    }

    private fun saveDocument() {
        if (busy) return
        val draft = editor.text.toString()
        setBusy(true)
        status.text = "Saving..."
        worker.execute {
            try {
                callPython("android_encoder_editor", "save_document", draft, hash)
                runOnUiThread { if (!isDestroyed) { Toast.makeText(this, "Saved. Profiles reloaded.", Toast.LENGTH_SHORT).show(); finish() } }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed) { status.text = error.message ?: "Unable to save presets"; setBusy(false) } }
            }
        }
    }

    private fun confirmRestore() {
        if (busy) return
        AlertDialog.Builder(this).setTitle("Restore default presets?")
            .setMessage("This will replace your current edits with the original encoding presets. The file will not change until you select Save & Close.")
            .setPositiveButton("Restore") { _, _ -> loadDefaults() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun loadDefaults() {
        if (busy) return
        val position = capturePosition()
        setBusy(true)
        status.text = "Loading defaults..."
        worker.execute {
            try {
                val result = callPython("android_encoder_defaults", "default_document")
                runOnUiThread { if (!isDestroyed) {
                    editor.setText(result.getString("text"))
                    status.text = "Defaults loaded. Select Save & Close to apply them."
                    setBusy(false)
                    position.putInt("start", 0); position.putInt("end", 0)
                    position.putInt("scrollX", 0); position.putInt("scrollY", 0)
                    restorePosition(position)
                } }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed) { status.text = error.message ?: "Unable to restore defaults"; setBusy(false); restorePosition(position) } }
            }
        }
    }

    private fun capturePosition() = Bundle().apply {
        putInt("start", editor.selectionStart); putInt("end", editor.selectionEnd)
        putInt("scrollX", editor.scrollX); putInt("scrollY", editor.scrollY)
        putBoolean("focus", editor.hasFocus())
    }

    private fun restorePosition(state: Bundle) {
        editor.post { if (!isDestroyed) {
            val length = editor.text.length
            val start = state.getInt("start", 0).coerceIn(0, length)
            val end = state.getInt("end", start).coerceIn(0, length)
            if (state.getBoolean("focus")) editor.requestFocus()
            editor.setSelection(minOf(start, end), maxOf(start, end))
            editor.post { if (!isDestroyed) editor.scrollTo(state.getInt("scrollX"), state.getInt("scrollY")) }
        } }
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        val position = capturePosition()
        super.onConfigurationChanged(configuration)
        updateActions(configuration.orientation)
        ViewCompat.requestApplyInsets(editor.rootView)
        restorePosition(position)
    }

    private fun closeEditor() {
        if (busy) return
        if (editor.text.toString() == baseline) finish() else {
            AlertDialog.Builder(this).setTitle("Discard changes?").setMessage("Unsaved edits will be lost.")
                .setPositiveButton("Discard") { _, _ -> finish() }.setNegativeButton("Keep editing", null).show()
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        if (hash.isNotEmpty()) {
            state.putString("draft", editor.text.toString()); state.putString("baseline", baseline); state.putString("hash", hash)
            state.putAll(capturePosition())
        }
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
