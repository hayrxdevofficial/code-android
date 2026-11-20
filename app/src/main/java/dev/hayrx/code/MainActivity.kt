package dev.hayrx.code

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val bg = Color.rgb(15, 18, 25)
    private val panel = Color.rgb(24, 29, 39)
    private val accent = Color.rgb(92, 150, 255)
    private val textColor = Color.rgb(232, 237, 245)
    private var currentName = "main.py"
    private var currentUri: Uri? = null
    private lateinit var editor: EditText
    private lateinit var terminal: EditText
    private lateinit var fileLabel: TextView
    private lateinit var root: LinearLayout

    private val openFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            currentUri = uri
            currentName = uri.lastPathSegment?.substringAfterLast('/') ?: "untitled"
            contentResolver.openInputStream(uri)?.use { editor.setText(it.bufferedReader().readText()) }
            fileLabel.text = currentName
            log("Opened: $currentName")
        }
    }

    private val createDocument = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            currentUri = uri
            contentResolver.openOutputStream(uri)?.use { it.write(editor.text.toString().toByteArray()) }
            log("Saved file")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildInterface()
    }

    private fun buildInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(12, 8, 12, 8)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = label("CODE", 23f, true).apply { setTextColor(accent) }
        header.addView(brand, LinearLayout.LayoutParams(0, 52, 1f))
        header.addView(action("Import") { openFile.launch(arrayOf("*/*")) })
        header.addView(action("Save") { saveFile() })
        root.addView(header)

        val workspaceBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        workspaceBar.addView(label("WORKSPACE", 12f, true), LinearLayout.LayoutParams(0, 42, 1f))
        workspaceBar.addView(action("+ File") { askName("Create file") { name -> currentName = name; fileLabel.text = name; editor.setText(""); log("Created file: $name") } })
        workspaceBar.addView(action("+ Folder") { askName("Create folder") { name -> log("Folder placeholder created: $name") } })
        root.addView(workspaceBar)

        fileLabel = label(currentName, 13f, true).apply { setPadding(8, 8, 8, 8); setBackgroundColor(panel) }
        root.addView(fileLabel)
        root.addView(label("EDITOR", 11f, true).apply { setPadding(4, 10, 4, 6) })

        editor = EditText(this).apply {
            setTextColor(textColor); setHintTextColor(Color.GRAY)
            hint = "Write Python, C++ or HTML here..."
            textSize = 14f; typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setPadding(12, 12, 12, 12); setBackgroundColor(panel)
            minLines = 10
            setText("print(\"Hello from CODE!\")")
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = HorizontalScrollView(this)
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(action("Run Code") { runCode() })
        actionRow.addView(action("Android Project") { showAndroidProjectInfo() })
        actionRow.addView(action("HTML Preview") { showHtmlPreview() })
        actionRow.addView(action("Save As") { createDocument.launch(currentName) })
        actions.addView(actionRow)
        root.addView(actions)

        root.addView(label("TERMINAL", 11f, true).apply { setPadding(4, 8, 4, 5) })
        terminal = EditText(this).apply {
            setTextColor(Color.rgb(135, 220, 160)); setHintTextColor(Color.GRAY)
            hint = "Terminal output..."; textSize = 12f
            typeface = Typeface.MONOSPACE; setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.rgb(8, 11, 16))
            minLines = 3; maxLines = 5; isSingleLine = false
            setText("CODE Terminal 0.1\nType help in the command field below.")
        }
        root.addView(terminal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 112))

        val commandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val command = EditText(this).apply {
            hint = "Enter command..."; setTextColor(textColor); setHintTextColor(Color.GRAY)
            singleLine = true; textSize = 13f
        }
        commandRow.addView(command, LinearLayout.LayoutParams(0, 48, 1f))
        commandRow.addView(action("Send") {
            val value = command.text.toString().trim()
            if (value.isNotEmpty()) { handleCommand(value); command.setText("") }
        })
        root.addView(commandRow)
        setContentView(root)
    }

    private fun label(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(textColor)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun action(title: String, callback: () -> Unit) = Button(this).apply {
        text = title; textSize = 11f; isAllCaps = false
        setTextColor(textColor); setBackgroundColor(panel)
        setOnClickListener { callback() }
    }

    private fun log(message: String) { terminal.append("\n> $message") }

    private fun askName(title: String, done: (String) -> Unit) {
        val field = EditText(this).apply { hint = "Name" }
        AlertDialog.Builder(this).setTitle(title).setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) done(name)
            }.show()
    }

    private fun saveFile() {
        val uri = currentUri
        if (uri == null) { createDocument.launch(currentName); return }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(editor.text.toString().toByteArray()) }
            log("Saved $currentName")
        } catch (e: Exception) { log("Save failed: ${e.message}") }
    }

    private fun handleCommand(command: String) {
        log("$ $command")
        when (command.substringBefore(' ')) {
            "help" -> log("Commands: help, clear, pwd, whoami, version")
            "clear" -> terminal.setText("")
            "pwd" -> log("/storage/emulated/0/CODE (workspace placeholder)")
            "whoami" -> log("code-user")
            "version" -> log("CODE 0.1.0")
            else -> log("Command unavailable in this build; runtime integration is planned.")
        }
    }

    private fun runCode() {
        when {
            currentName.endsWith(".html", true) || currentName.endsWith(".htm", true) -> showHtmlPreview()
            currentName.endsWith(".py", true) -> log("Python runtime is not bundled yet.")
            currentName.endsWith(".cpp", true) || currentName.endsWith(".cc", true) || currentName.endsWith(".c", true) -> log("C/C++ compiler is not bundled yet.")
            else -> log("No runner for this file type yet.")
        }
    }

    private fun showHtmlPreview() {
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
            loadDataWithBaseURL(null, editor.text.toString(), "text/html", "UTF-8", null)
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val close = Button(this).apply {
            text = "✕  Close"; isAllCaps = false; gravity = Gravity.END
            setOnClickListener { setContentView(root) }
        }
        container.addView(close, LinearLayout.LayoutParams(-1, 52))
        container.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(container)
    }

    private fun showAndroidProjectInfo() {
        AlertDialog.Builder(this).setTitle("Start Android Project")
            .setMessage("Project templates and on-device APK building are planned for the next milestone. This starter version does not download Android SDK or build APKs yet.")
            .setPositiveButton("OK", null).show()
    }
}
