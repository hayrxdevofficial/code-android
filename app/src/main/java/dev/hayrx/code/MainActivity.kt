package dev.hayrx.code

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : ComponentActivity() {
    private val bg = Color.rgb(30, 30, 30)
    private val panel = Color.rgb(37, 37, 38)
    private val railColor = Color.rgb(51, 51, 51)
    private val fg = Color.rgb(220, 220, 220)
    private val muted = Color.rgb(145, 145, 145)
    private val blue = Color.rgb(0, 122, 204)
    private val terminalBg = Color.rgb(24, 24, 24)

    private var currentName = "main.py"
    private var currentUri: Uri? = null
    private lateinit var editor: EditText
    private lateinit var terminal: EditText
    private lateinit var fileLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var explorer: LinearLayout
    private lateinit var terminalPanel: LinearLayout
    private lateinit var root: LinearLayout
    private var terminalVisible = true

    private val openFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            currentUri = uri
            currentName = uri.lastPathSegment?.substringAfterLast('/') ?: "untitled"
            contentResolver.openInputStream(uri)?.use { editor.setText(it.bufferedReader().readText()) }
            updateFileHeader()
            log("Opened $currentName")
        }
    }

    private val saveAs = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            currentUri = uri
            contentResolver.openOutputStream(uri)?.use { it.write(editor.text.toString().toByteArray()) }
            log("Saved $currentName")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildInterface()
    }

    private fun buildInterface() {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val explorerWidth = if (widthDp >= 600) 210 else 116

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 8, 0)
            setBackgroundColor(Color.rgb(45, 45, 45))
        }
        titleBar.addView(text("CODE", 17f, true).apply { setTextColor(Color.rgb(80, 160, 220)) },
            LinearLayout.LayoutParams(0, 42, 1f))
        titleBar.addView(action("Open") { openFile.launch(arrayOf("*/*")) })
        titleBar.addView(action("Save") { saveFile() })
        titleBar.addView(action("Run ▶") { runCode() })
        root.addView(titleBar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        val activityRail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(railColor)
        }
        activityRail.addView(railIcon("▤", "Explorer") { toggleExplorer() })
        activityRail.addView(railIcon("⌕", "Search") { toast("Search is planned for a later milestone.") })
        activityRail.addView(railIcon("⑂", "Source Control") { toast("Git integration is planned for a later milestone.") })
        body.addView(activityRail, LinearLayout.LayoutParams(42, -1))

        explorer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 6, 6, 6)
            setBackgroundColor(panel)
        }
        val explorerHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        explorerHeading.addView(text("EXPLORER", 10f, true), LinearLayout.LayoutParams(0, 36, 1f))
        explorerHeading.addView(action("⋯") { showWorkspaceInfo() })
        explorer.addView(explorerHeading)
        explorer.addView(text("▾  CODE", 12f, true).apply { setPadding(2, 8, 2, 8) })
        fileLabel = text("  ◉  $currentName", 12f, false).apply {
            setPadding(4, 10, 2, 10)
            setBackgroundColor(Color.rgb(55, 55, 56))
        }
        explorer.addView(fileLabel)
        explorer.addView(action("+  New File") {
            askName("New File") { name ->
                currentName = name
                currentUri = null
                editor.setText("")
                updateFileHeader()
                log("Created unsaved file $name")
            }
        })
        explorer.addView(action("↥  Import File") { openFile.launch(arrayOf("*/*")) })
        explorer.addView(action("+  New Folder") {
            toast("Persistent folders are coming with the workspace manager.")
        })
        body.addView(explorer, LinearLayout.LayoutParams(explorerWidth, -1))

        val editorColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        val tabs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(panel)
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(panel)
        }
        tabRow.addView(text("  ●  $currentName   ×  ", 12f, false).apply {
            setPadding(8, 0, 8, 0)
            setBackgroundColor(bg)
        }, LinearLayout.LayoutParams(-2, 42))
        tabs.addView(tabRow)
        editorColumn.addView(tabs)

        val editArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
        }
        val lineNumbers = TextView(this).apply {
            text = (1..100).joinToString("\n") { it.toString() }
            setTextColor(Color.rgb(100, 100, 100))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.END
            setPadding(8, 12, 10, 12)
            setBackgroundColor(bg)
        }
        editArea.addView(lineNumbers, LinearLayout.LayoutParams(38, -1))
        editor = EditText(this).apply {
            setTextColor(fg)
            setHintTextColor(muted)
            hint = "// Start writing code..."
            textSize = 13.5f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setPadding(4, 12, 12, 12)
            setBackgroundColor(bg)
            setSingleLine(false)
            setHorizontallyScrolling(true)
            isVerticalScrollBarEnabled = true
            includeFontPadding = false
            setText("print(\"Hello from CODE!\")")
        }
        editArea.addView(editor, LinearLayout.LayoutParams(0, -1, 1f))
        editorColumn.addView(editArea, LinearLayout.LayoutParams(-1, 0, 1f))

        val editorTools = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(panel)
        }
        val toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolRow.addView(action("▶ Run") { runCode() })
        toolRow.addView(action("HTML Preview") { showHtmlPreview() })
        toolRow.addView(action("Android Project") { showAndroidProjectInfo() })
        toolRow.addView(action("Terminal") { toggleTerminal() })
        editorTools.addView(toolRow)
        editorColumn.addView(editorTools)
        body.addView(editorColumn)
        root.addView(body)

        terminalPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(terminalBg)
        }
        val terminalHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(panel)
        }
        terminalHeading.addView(text("  PROBLEMS     OUTPUT     DEBUG CONSOLE     TERMINAL", 10f, true),
            LinearLayout.LayoutParams(0, 34, 1f))
        terminalHeading.addView(action("×") { toggleTerminal() })
        terminalPanel.addView(terminalHeading)

        terminal = EditText(this).apply {
            setTextColor(Color.rgb(205, 205, 205))
            setHintTextColor(muted)
            hint = "Terminal output"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(12, 6, 12, 6)
            setBackgroundColor(terminalBg)
            minLines = 2
            maxLines = 4
            isSingleLine = false
            setText("CODE terminal  •  Build 0.2\n> Ready")
        }
        terminalPanel.addView(terminal, LinearLayout.LayoutParams(-1, 92))

        val commandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 0, 8, 4)
            setBackgroundColor(terminalBg)
        }
        val command = EditText(this).apply {
            hint = "Enter command (try: help)"
            setTextColor(fg)
            setHintTextColor(muted)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            singleLine = true
            setPadding(8, 4, 8, 4)
        }
        commandRow.addView(command, LinearLayout.LayoutParams(0, 42, 1f))
        commandRow.addView(action("Send") {
            val value = command.text.toString().trim()
            if (value.isNotEmpty()) {
                handleCommand(value)
                command.setText("")
            }
        })
        terminalPanel.addView(commandRow)
        root.addView(terminalPanel)

        statusText = text("  Python   |   UTF-8   |   CODE", 10f, false).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setPadding(4, 0, 4, 0)
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, 22))
        setContentView(root)
    }

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(fg)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun action(title: String, callback: () -> Unit) = text(title, 11f, false).apply {
        gravity = Gravity.CENTER
        setPadding(8, 8, 8, 8)
        isClickable = true
        isFocusable = true
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { callback() }
    }

    private fun railIcon(glyph: String, description: String, callback: () -> Unit) =
        text(glyph, 23f, false).apply {
            gravity = Gravity.CENTER
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(-1, 52)
            setOnClickListener { callback() }
        }

    private fun updateFileHeader() {
        fileLabel.text = "  ◉  $currentName"
        statusText.text = "  ${languageName(currentName)}   |   UTF-8   |   CODE"
    }

    private fun languageName(name: String) = when {
        name.endsWith(".py", true) -> "Python"
        name.endsWith(".cpp", true) || name.endsWith(".cc", true) -> "C++"
        name.endsWith(".c", true) -> "C"
        name.endsWith(".html", true) || name.endsWith(".htm", true) -> "HTML"
        name.endsWith(".js", true) -> "JavaScript"
        else -> "Plain Text"
    }

    private fun log(message: String) { terminal.append("\n> $message") }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun askName(title: String, done: (String) -> Unit) {
        val field = EditText(this).apply { hint = "example.py" }
        AlertDialog.Builder(this).setTitle(title).setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) done(name)
            }.show()
    }

    private fun saveFile() {
        val uri = currentUri
        if (uri == null) {
            saveAs.launch(currentName)
            return
        }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(editor.text.toString().toByteArray())
            }
            log("Saved $currentName")
        } catch (e: Exception) {
            log("Save failed: ${e.message}")
        }
    }

    private fun handleCommand(command: String) {
        log("$ $command")
        when (command.substringBefore(' ')) {
            "help" -> log("Built-in: help, clear, pwd, whoami, version")
            "clear" -> terminal.setText("")
            "pwd" -> log("/CODE (workspace integration is in progress)")
            "whoami" -> log("code-user")
            "version" -> log("CODE 0.2.0")
            else -> log("Shell execution is not integrated yet; this is the IDE terminal panel.")
        }
    }

    private fun runCode() {
        when {
            currentName.endsWith(".html", true) || currentName.endsWith(".htm", true) -> showHtmlPreview()
            currentName.endsWith(".py", true) -> log("Python runtime is not integrated yet.")
            currentName.endsWith(".cpp", true) || currentName.endsWith(".cc", true) || currentName.endsWith(".c", true) ->
                log("C/C++ compiler is not integrated yet.")
            else -> log("No runner available for this file type.")
        }
    }

    private fun showHtmlPreview() {
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
            loadDataWithBaseURL(null, editor.text.toString(), "text/html", "UTF-8", null)
        }
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val close = TextView(this).apply {
            text = "✕   Close Preview"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(12, 0, 18, 0)
            setBackgroundColor(Color.rgb(45, 45, 45))
            setOnClickListener { setContentView(root) }
        }
        preview.addView(close, LinearLayout.LayoutParams(-1, 48))
        preview.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(preview)
    }

    private fun showAndroidProjectInfo() {
        AlertDialog.Builder(this).setTitle("Start Android Project")
            .setMessage("Android project templates, SDK download and on-device APK builds are not implemented yet.")
            .setPositiveButton("OK", null).show()
    }

    private fun showWorkspaceInfo() {
        AlertDialog.Builder(this).setTitle("Workspace")
            .setMessage("CODE 0.2 interface. Persistent folder management and a real project tree are planned for the next workspace milestone.")
            .setPositiveButton("OK", null).show()
    }

    private fun toggleExplorer() {
        explorer.visibility = if (explorer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun toggleTerminal() {
        terminalVisible = !terminalVisible
        terminalPanel.visibility = if (terminalVisible) View.VISIBLE else View.GONE
    }
}
