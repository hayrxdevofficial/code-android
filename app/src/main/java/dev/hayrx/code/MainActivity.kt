package dev.hayrx.code

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : ComponentActivity() {
    private val bg = Color.rgb(30, 30, 30)
    private val panel = Color.rgb(37, 37, 38)
    private val rail = Color.rgb(51, 51, 51)
    private val fg = Color.rgb(220, 220, 220)
    private val muted = Color.rgb(145, 145, 145)
    private val blue = Color.rgb(0, 122, 204)
    private val terminalBg = Color.rgb(24, 24, 24)

    private data class Tab(val uri: Uri?, var name: String, var content: String, var dirty: Boolean = false)
    private val tabs = mutableListOf<Tab>()
    private var activeTab = 0
    private var workspaceUri: Uri? = null
    private var workspace: DocumentFile? = null
    private var currentFile: DocumentFile? = null
    private var loadingDocument = false

    private lateinit var editor: EditText
    private lateinit var terminal: EditText
    private lateinit var terminalOutputParams: LinearLayout.LayoutParams
    private lateinit var fileTree: LinearLayout
    private lateinit var tabStrip: LinearLayout
    private lateinit var lineNumbers: TextView
    private lateinit var fileLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var explorer: LinearLayout
    private lateinit var terminalPanel: LinearLayout
    private lateinit var root: LinearLayout
    private var terminalVisible = true
    private var terminalDragStartY = 0f
    private var terminalDragStartHeight = 0
    private var pythonRunInProgress = false

    private val chooseWorkspace = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                workspaceUri = uri
                getPreferences(MODE_PRIVATE).edit().putString("workspace_uri", uri.toString()).apply()
                workspace = DocumentFile.fromTreeUri(this, uri)
                currentFile = null
                tabs.clear()
                activeTab = 0
                editor.setText("")
                refreshWorkspace()
                log("Workspace opened: ${workspace?.name ?: "folder"}")
            } catch (e: Exception) {
                log("Cannot open workspace: ${e.message}")
            }
        }
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            val sourceName = displayName(uri)
            val targetFolder = workspace
            if (targetFolder != null) {
                try {
                    val target = targetFolder.findFile(sourceName)
                        ?: targetFolder.createFile(mimeFor(sourceName), sourceName)
                    if (target == null) throw IllegalStateException("Could not create destination file")
                    contentResolver.openInputStream(uri)?.use { input ->
                        contentResolver.openOutputStream(target.uri, "wt")?.use { output -> input.copyTo(output) }
                    }
                    openWorkspaceFile(target)
                    refreshWorkspace()
                    log("Imported $sourceName")
                } catch (e: Exception) {
                    log("Import failed: ${e.message}")
                }
            } else {
                openExternalFile(uri, sourceName)
            }
        }
    }

    private val saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(editor.text.toString().toByteArray(Charsets.UTF_8))
                }
                val tab = active()
                tab?.let {
                    it.name = displayName(uri)
                    it.dirty = false
                    tabs[activeTab] = it.copy(uri = uri)
                }
                updateHeaders()
                renderTabs()
                log("Saved")
            } catch (e: Exception) {
                log("Save failed: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildInterface()
        val saved = getPreferences(MODE_PRIVATE).getString("workspace_uri", null)
        if (saved != null) {
            try {
                workspaceUri = Uri.parse(saved)
                workspace = DocumentFile.fromTreeUri(this, workspaceUri!!)
                refreshWorkspace()
                log("Restored workspace: ${workspace?.name ?: "folder"}")
            } catch (_: Exception) {
                workspace = null
                workspaceUri = null
            }
        }
        if (tabs.isEmpty()) newUntitledTab("main.py", "print(\"Hello from CODE!\")")
    }

    private fun buildInterface() {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val explorerWidth = if (widthDp >= 600) 260 else 176
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 0, 10, 0)
            setBackgroundColor(Color.rgb(45, 45, 45))
        }
        titleBar.addView(text("CODE", 17f, true).apply { setTextColor(Color.rgb(80, 160, 220)) },
            LinearLayout.LayoutParams(0, 52, 1f))
        titleBar.addView(action("Open") { importFile.launch(arrayOf("*/*")) })
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
            setBackgroundColor(rail)
        }
        activityRail.addView(railIcon("▤", "Explorer") { toggleExplorer() })
        activityRail.addView(railIcon("⌕", "Search") { showSearch() })
        activityRail.addView(railIcon("⑂", "Source Control") { toast("Git integration is planned next.") })
        body.addView(activityRail, LinearLayout.LayoutParams(50, -1))

        explorer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(7, 6, 5, 6)
            setBackgroundColor(panel)
        }
        val explorerHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        explorerHeader.addView(text("EXPLORER", 10f, true), LinearLayout.LayoutParams(0, 34, 1f))
        explorerHeader.addView(action("⋯") { showWorkspaceMenu() })
        explorer.addView(explorerHeader)
        explorer.addView(action("▣  Open Folder") { chooseWorkspace.launch(null) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(action("+ File") { createWorkspaceFile() }, LinearLayout.LayoutParams(0, 40, 1f))
        buttons.addView(action("+ Dir") { createWorkspaceFolder() }, LinearLayout.LayoutParams(0, 40, 1f))
        explorer.addView(buttons)
        explorer.addView(action("↥ Import") { importFile.launch(arrayOf("*/*")) })
        fileLabel = text("WORKSPACE", 10f, true).apply { setPadding(3, 10, 2, 8) }
        explorer.addView(fileLabel)
        val treeScroll = ScrollView(this).apply { isFillViewport = true }
        fileTree = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        treeScroll.addView(fileTree)
        explorer.addView(treeScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(explorer, LinearLayout.LayoutParams(explorerWidth, -1))

        val editorColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(panel) }
        tabStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(panel) }
        tabScroll.addView(tabStrip)
        editorColumn.addView(tabScroll)
        val editArea = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(bg) }
        lineNumbers = TextView(this).apply {
            setTextColor(Color.rgb(100, 100, 100))
            textSize = 15f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.END
            setPadding(10, 14, 12, 14)
            setBackgroundColor(bg)
            text = "1"
        }
        editArea.addView(lineNumbers, LinearLayout.LayoutParams(38, -1))
        editor = EditText(this).apply {
            setTextColor(fg)
            setHintTextColor(muted)
            hint = "// Start writing code..."
            textSize = if (widthDp >= 600) 17f else 15.5f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setPadding(6, 14, 14, 14)
            setBackgroundColor(bg)
            setSingleLine(false)
            setHorizontallyScrolling(true)
            isVerticalScrollBarEnabled = true
            includeFontPadding = false
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!loadingDocument) {
                        active()?.let { it.content = s?.toString() ?: ""; it.dirty = true }
                        updateLineNumbers()
                        renderTabs()
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        editArea.addView(editor, LinearLayout.LayoutParams(0, -1, 1f))
        editorColumn.addView(editArea, LinearLayout.LayoutParams(-1, 0, 1f))
        val toolScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(panel) }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(action("▶ Run") { runCode() })
        tools.addView(action("HTML Preview") { showHtmlPreview() })
        tools.addView(action("Android Project") { showAndroidProjectInfo() })
        tools.addView(action("Terminal") { toggleTerminal() })
        tools.addView(action("Installer / APK") { showToolsHub("Installer") })
        toolScroll.addView(tools)
        editorColumn.addView(toolScroll)
        body.addView(editorColumn)
        root.addView(body)

        terminalPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(terminalBg) }
        val terminalHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(panel) }
        terminalHeader.addView(text("  PROBLEMS     OUTPUT     DEBUG CONSOLE     TERMINAL", 10f, true),
            LinearLayout.LayoutParams(0, 34, 1f))
        terminalHeader.addView(action("×") { toggleTerminal() })
        terminalPanel.addView(terminalHeader)
        terminal = EditText(this).apply {
            setTextColor(Color.rgb(205, 205, 205))
            setHintTextColor(muted)
            hint = "Terminal output"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(14, 10, 14, 10)
            setBackgroundColor(terminalBg)
            minLines = 4
            maxLines = 12
            isSingleLine = false
            setText("CODE terminal  •  0.4\n> Ready")
            isFocusable = false
            isFocusableInTouchMode = false
        }
        terminalOutputParams = LinearLayout.LayoutParams(-1, dp(180))
        terminalPanel.addView(terminal, terminalOutputParams)
        val commandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 0, 8, 4); setBackgroundColor(terminalBg) }
        val command = EditText(this).apply {
            hint = "IDE command (help)"
            setTextColor(fg)
            setHintTextColor(muted)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            setPadding(8, 4, 8, 4)
        }
        commandRow.addView(command, LinearLayout.LayoutParams(0, 42, 1f))
        commandRow.addView(action("Send") {
            val value = command.text.toString().trim()
            if (value.isNotEmpty()) { handleCommand(value); command.setText("") }
        })
        terminalPanel.addView(commandRow)
        val resizeHandle = View(this).apply {
            setBackgroundColor(Color.rgb(65, 65, 65))
            contentDescription = "Drag to resize terminal"
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        terminalDragStartY = event.rawY
                        terminalDragStartHeight = terminalOutputParams.height
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val maxHeight = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * 0.48f).toInt()
                        val next = (terminalDragStartHeight - (event.rawY - terminalDragStartY).toInt())
                            .coerceIn(dp(120), maxHeight.coerceAtLeast(dp(160)))
                        terminalOutputParams.height = next
                        terminal.layoutParams = terminalOutputParams
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        root.addView(resizeHandle, LinearLayout.LayoutParams(-1, dp(8)))
        root.addView(terminalPanel)
        statusText = text("  Python   |   UTF-8   |   CODE", 10f, false).apply {
            setTextColor(Color.WHITE); setBackgroundColor(blue); setPadding(4, 0, 4, 0)
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, 28))
        setContentView(root)
        renderTabs()
        refreshWorkspace()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(fg)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun action(title: String, callback: () -> Unit) = text(title, 11f, false).apply {
        gravity = Gravity.CENTER; setPadding(10, 10, 10, 10)
        isClickable = true; isFocusable = true
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { callback() }
    }

    private fun railIcon(glyph: String, description: String, callback: () -> Unit) =
        text(glyph, 23f, false).apply {
            gravity = Gravity.CENTER; contentDescription = description
            layoutParams = LinearLayout.LayoutParams(-1, 52)
            setOnClickListener { callback() }
        }

    private fun active(): Tab? = if (tabs.isEmpty() || activeTab !in tabs.indices) null else tabs[activeTab]

    private fun newUntitledTab(name: String, content: String = "") {
        tabs.add(Tab(null, name, content))
        activeTab = tabs.lastIndex
        showActiveTab()
    }

    private fun showActiveTab() {
        val tab = active() ?: return
        loadingDocument = true
        editor.setText(tab.content)
        editor.setSelection(editor.text.length)
        loadingDocument = false
        currentFile = tab.uri?.let { uri -> workspace?.findFile(tab.name)?.takeIf { it.uri == uri } }
        updateLineNumbers()
        updateHeaders()
        renderTabs()
    }

    private fun openWorkspaceFile(file: DocumentFile) {
        try {
            val content = contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val existing = tabs.indexOfFirst { it.uri == file.uri }
            if (existing >= 0) activeTab = existing
            else {
                tabs.add(Tab(file.uri, file.name ?: "untitled", content))
                activeTab = tabs.lastIndex
            }
            currentFile = file
            showActiveTab()
            log("Opened ${file.name}")
        } catch (e: Exception) { log("Open failed: ${e.message}") }
    }

    private fun openExternalFile(uri: Uri, name: String) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            tabs.add(Tab(uri, name, content))
            activeTab = tabs.lastIndex
            currentFile = null
            showActiveTab()
            log("Opened external file $name")
        } catch (e: Exception) { log("Open failed: ${e.message}") }
    }

    private fun saveFile() {
        val tab = active() ?: return
        tab.content = editor.text.toString()
        try {
            val uri = tab.uri
            if (uri == null) {
                saveAs.launch(tab.name)
                return
            }
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(tab.content.toByteArray(Charsets.UTF_8)) }
            tab.dirty = false
            updateHeaders()
            renderTabs()
            log("Saved ${tab.name}")
        } catch (e: Exception) { log("Save failed: ${e.message}") }
    }

    private fun createWorkspaceFile() {
        val folder = workspace
        if (folder == null) { chooseWorkspace.launch(null); toast("Choose a workspace folder first."); return }
        askText("New File", "example.py") { name ->
            try {
                val file = folder.createFile(mimeFor(name), name)
                    ?: throw IllegalStateException("Could not create file")
                openWorkspaceFile(file)
                refreshWorkspace()
            } catch (e: Exception) { log("Create file failed: ${e.message}") }
        }
    }

    private fun createWorkspaceFolder() {
        val folder = workspace
        if (folder == null) { chooseWorkspace.launch(null); toast("Choose a workspace folder first."); return }
        askText("New Folder", "src") { name ->
            try {
                if (folder.findFile(name) != null) throw IllegalArgumentException("That name already exists")
                folder.createDirectory(name) ?: throw IllegalStateException("Could not create folder")
                refreshWorkspace()
                log("Created folder $name")
            } catch (e: Exception) { log("Create folder failed: ${e.message}") }
        }
    }

    private fun refreshWorkspace() {
        if (!::fileTree.isInitialized) return
        fileTree.removeAllViews()
        val folder = workspace
        fileLabel.text = if (folder == null) "NO FOLDER OPEN" else "▾  ${folder.name ?: "WORKSPACE"}"
        if (folder == null) {
            fileTree.addView(text("Open a folder to\nsee your files.", 11f, false).apply { setPadding(6, 12, 4, 4); setTextColor(muted) })
            return
        }
        renderFolder(folder, "", 0)
    }

    private fun renderFolder(folder: DocumentFile, prefix: String, depth: Int) {
        if (depth > 5) return
        val children = try { folder.listFiles().sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase() ?: "" })) } catch (_: Exception) { emptyList() }
        for (child in children) {
            val name = child.name ?: "untitled"
            val row = text(prefix + (if (child.isDirectory) "▸  " else iconFor(name) + "  ") + name, 11f, child.isDirectory).apply {
                setPadding(4 + depth * 3, 9, 2, 9)
                isClickable = true
                setOnClickListener {
                    if (child.isDirectory) {
                        val shown = child.uri.toString() in expandedFolders
                        if (shown) expandedFolders.remove(child.uri.toString()) else expandedFolders.add(child.uri.toString())
                        refreshWorkspace()
                    } else openWorkspaceFile(child)
                }
            }
            fileTree.addView(row)
            if (child.isDirectory && child.uri.toString() in expandedFolders) renderFolder(child, prefix + "   ", depth + 1)
        }
    }

    private val expandedFolders = mutableSetOf<String>()

    private fun renderTabs() {
        if (!::tabStrip.isInitialized) return
        tabStrip.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val label = (if (index == activeTab) "● " else "  ") + tab.name + if (tab.dirty) " •" else ""
            tabStrip.addView(action(label) {
                if (activeTab != index) {
                    active()?.let { it.content = editor.text.toString() }
                    activeTab = index
                    showActiveTab()
                }
            }.apply { setPadding(10, 0, 10, 0); setBackgroundColor(if (index == activeTab) bg else panel) },
                LinearLayout.LayoutParams(-2, 42))
        }
    }

    private fun updateHeaders() {
        val tab = active()
        fileLabel.text = if (workspace == null) "NO FOLDER OPEN" else "▾  ${workspace?.name ?: "WORKSPACE"}"
        statusText.text = "  ${languageName(tab?.name ?: "")}   |   UTF-8   |   ${if (tab?.dirty == true) "Modified" else "CODE"}"
    }

    private fun updateLineNumbers() {
        val count = maxOf(1, editor.text.toString().count { it == '\n' } + 1)
        lineNumbers.text = (1..count).joinToString("\n")
    }

    private fun languageName(name: String) = when {
        name.endsWith(".py", true) -> "Python"
        name.endsWith(".cpp", true) || name.endsWith(".cc", true) -> "C++"
        name.endsWith(".c", true) -> "C"
        name.endsWith(".html", true) || name.endsWith(".htm", true) -> "HTML"
        name.endsWith(".js", true) -> "JavaScript"
        else -> "Plain Text"
    }

    private fun iconFor(name: String) = when {
        name.endsWith(".py", true) -> "🐍"
        name.endsWith(".html", true) -> "◇"
        name.endsWith(".cpp", true) || name.endsWith(".c", true) -> "C"
        else -> "·"
    }

    private fun mimeFor(name: String) = when {
        name.endsWith(".html", true) || name.endsWith(".htm", true) -> "text/html"
        name.endsWith(".py", true) || name.endsWith(".txt", true) || name.endsWith(".md", true) -> "text/plain"
        name.endsWith(".json", true) -> "application/json"
        else -> "text/plain"
    }

    private fun displayName(uri: Uri): String {
        val doc = DocumentFile.fromSingleUri(this, uri)
        return doc?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "untitled"
    }

    private fun askText(title: String, hintText: String, done: (String) -> Unit) {
        val field = EditText(this).apply { hint = hintText }
        AlertDialog.Builder(this).setTitle(title).setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty() && !name.contains("/") && !name.contains("\\")) done(name)
            }.show()
    }

    private fun showWorkspaceMenu() {
        AlertDialog.Builder(this).setTitle("Workspace")
            .setItems(arrayOf("Open Folder", "Refresh Explorer", "Close Workspace")) { _, which ->
                when (which) {
                    0 -> chooseWorkspace.launch(null)
                    1 -> refreshWorkspace()
                    2 -> {
                        workspace = null; workspaceUri = null
                        getPreferences(MODE_PRIVATE).edit().remove("workspace_uri").apply()
                        refreshWorkspace()
                    }
                }
            }.show()
    }

    private fun showSearch() {
        val field = EditText(this).apply { hint = "Find text in current file" }
        AlertDialog.Builder(this).setTitle("Find")
            .setView(field)
            .setNegativeButton("Close", null)
            .setPositiveButton("Find") { _, _ ->
                val query = field.text.toString()
                val index = editor.text.toString().indexOf(query, ignoreCase = true)
                if (query.isNotEmpty() && index >= 0) {
                    editor.requestFocus()
                    editor.setSelection(index, index + query.length)
                } else toast("Text not found")
            }.show()
    }

    private fun log(message: String) {
        if (::terminal.isInitialized) terminal.append("\n> $message")
    }

    private fun handleCommand(command: String) {
        log("\$ $command")
        when (command.substringBefore(' ')) {
            "help" -> log("IDE commands: help, clear, pwd, ls, whoami, version")
            "clear" -> terminal.setText("")
            "pwd" -> log(workspace?.uri?.toString() ?: "No workspace open")
            "ls" -> log(workspace?.listFiles()?.joinToString("  ") { it.name ?: "?" } ?: "No workspace open")
            "whoami" -> log("code-user")
            "version" -> log("CODE 0.4.0")
            else -> log("This is the IDE command panel, not a system shell. Command not found.")
        }
    }

    private fun runCode() {
        val name = active()?.name ?: ""
        when {
            name.endsWith(".html", true) || name.endsWith(".htm", true) -> showHtmlPreview()
            name.endsWith(".py", true) -> runPythonCode()
            name.endsWith(".cpp", true) || name.endsWith(".cc", true) || name.endsWith(".c", true) ->
                log("C/C++ compiler is not connected yet.")
            else -> log("No runner available for this file type.")
        }
    }

    private fun runPythonCode() {
        if (pythonRunInProgress) {
            toast("Python is already running.")
            return
        }

        val source = editor.text.toString()
        val fileName = active()?.name ?: "main.py"
        pythonRunInProgress = true
        log("Running $fileName with embedded Python 3.13...")

        Thread {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }
                val py = Python.getInstance()
                py.getModule("code_runner").callAttr("add_package_path", File(filesDir, "python-packages").absolutePath)
                val result = py.getModule("code_runner")
                    .callAttr("run_code", source, fileName)
                    .toString()

                runOnUiThread {
                    pythonRunInProgress = false
                    log(result.ifBlank { "Python finished successfully (no output)." })
                }
            } catch (error: Exception) {
                runOnUiThread {
                    pythonRunInProgress = false
                    log("Python error: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun showHtmlPreview() {
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
            loadDataWithBaseURL(null, editor.text.toString(), "text/html", "UTF-8", null)
        }
        val preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        preview.addView(text("✕   Close Preview", 14f, true).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(12, 0, 18, 0); setBackgroundColor(Color.rgb(45, 45, 45))
            setOnClickListener { setContentView(root) }
        }, LinearLayout.LayoutParams(-1, 48))
        preview.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(preview)
    }


    private fun showToolsHub(initialTab: String = "Installer") {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 8, 0); setBackgroundColor(Color.rgb(45, 45, 45))
        }
        header.addView(text("CODE / TOOLS", 17f, true), LinearLayout.LayoutParams(0, 54, 1f))
        header.addView(action("← Editor") { setContentView(root) })
        page.addView(header)
        val tabsBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(panel) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 14, 14, 24) }
        scroll.addView(content)
        page.addView(tabsBar)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun para(value: String, size: Float = 13f, color: Int = fg) =
            text(value, size, false).apply { setTextColor(color); setPadding(2, 7, 2, 7) }
        fun heading(value: String) = text(value, 19f, true).apply { setPadding(2, 10, 2, 6) }
        fun card(title: String, description: String, buttonText: String, click: () -> Unit) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 8, 14, 10); setBackgroundColor(panel) }
            box.addView(text(title, 15f, true))
            box.addView(para(description, 12f, muted))
            box.addView(action(buttonText, click).apply { setBackgroundColor(Color.rgb(45, 90, 130)); setTextColor(Color.WHITE) },
                LinearLayout.LayoutParams(-1, 44))
            content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        fun openUrl(url: String) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Exception) { toast("Cannot open link") }
        }
        fun showTab(tab: String) {
            content.removeAllViews()
            tabsBar.removeAllViews()
            listOf("Installer", "Pip Installer", "APK", "Android SDK Compiler").forEach { name ->
                tabsBar.addView(action(name) { showTab(name) }.apply {
                    textSize = 10f; setPadding(7, 12, 7, 12)
                    setBackgroundColor(if (name == tab) blue else panel)
                }, LinearLayout.LayoutParams(0, 48, 1f))
            }
            when (tab) {
                "Installer" -> {
                    content.addView(heading("Installer"))
                    content.addView(para("Runtime and developer tool manager. CODE separates built-in tools from components that still need a supported installer.", 13f, muted))
                    card("Python 3.13 • Built in", "CODE already includes embedded Python 3.13 through Chaquopy. No separate Python download is needed to run Python files.", "Check Python runtime") {
                        log("Embedded Python 3.13 runtime is ready"); toast("Python 3.13 is ready")
                    }
                    card("Android SDK / Build Tools", "A full Android SDK is a desktop developer toolchain. This opens official downloads; installing a complete SDK on this phone is not supported yet.", "Official Android SDK") {
                        openUrl("https://developer.android.com/studio")
                    }
                    card("C++ compiler / Android NDK", "The official Android NDK provides the Clang C/C++ toolchain. CODE does not yet install or invoke it on-device.", "Official Android NDK") {
                        openUrl("https://developer.android.com/ndk")
                    }
                    card("Python official downloads", "Official Python releases. CODE's embedded Python remains its current runtime.", "Open python.org") {
                        openUrl("https://www.python.org/downloads/")
                    }
                }
                "Pip Installer" -> {
                    content.addView(heading("Pip Installer"))
                    content.addView(para("Search the official PyPI index and install universal pure-Python wheels into CODE's private package directory. Native extensions and packages without a none-any wheel are not supported yet.", 13f, muted))
                    val input = EditText(this).apply {
                        hint = "Package name, e.g. requests"; setTextColor(fg); setHintTextColor(muted)
                        textSize = 14f; setSingleLine(true); setPadding(12, 8, 12, 8); setBackgroundColor(panel)
                    }
                    content.addView(input, LinearLayout.LayoutParams(-1, 52))
                    val result = para("Ready to search PyPI.", 12f, muted)
                    content.addView(result)
                    content.addView(action("Search PyPI") {
                        val name = input.text.toString().trim()
                        if (!name.matches(Regex("[A-Za-z0-9._-]{1,100}"))) toast("Enter a valid package name")
                        else if (name.equals("tkinter", ignoreCase = true) || name.equals("turtle", ignoreCase = true)) {
                            result.text = if (name.equals("tkinter", ignoreCase = true)) {
                                "tkinter is part of Python's standard library, not a PyPI package. It is also unsupported by CODE's Android Python runtime (Chaquopy), because it requires the Tcl/Tk GUI toolkit. It cannot be installed using Pip Installer."
                            } else {
                                "turtle is part of Python's standard library, not a PyPI package. CODE's Android Python runtime (Chaquopy) does not support it because it depends on Tk."
                            }
                        }
                        else {
                            result.text = "Searching official PyPI..."
                            searchPypi(name) { info, wheel ->
                                result.text = info
                                if (wheel != null) content.addView(action("Download and Install") {
                                    installPurePythonWheel(name, wheel) { message -> result.text = message }
                                }.apply { setBackgroundColor(blue); setTextColor(Color.WHITE) },
                                    LinearLayout.LayoutParams(-1, 48))
                            }
                        }
                    }.apply { setBackgroundColor(blue); setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(-1, 48))
                    content.addView(para("Packages are downloaded from pypi.org. Only universal pure-Python wheels are accepted in this first version.", 11f, muted))
                }
                "APK" -> {
                    content.addView(heading("APK Builder"))
                    content.addView(para("The CODE app currently builds its debug APK in GitHub Actions. On-device Gradle builds and project-to-APK packaging are not implemented yet.", 13f, muted))
                    card("Download CODE APK", "Open Actions, select the latest successful Build CODE debug APK run and download the CODE-debug artifact.", "Open APK builds") {
                        openUrl("https://github.com/hayrxdevofficial/code-android/actions")
                    }
                    card("Project → APK", "The planned packager will bundle Python source, HTML, CSS, JavaScript and assets into one Android APK.", "Packaging status") {
                        toast("Project-to-APK packaging is planned, not available yet.")
                    }
                }
                else -> {
                    content.addView(heading("Android SDK Compiler"))
                    content.addView(para("This module will manage SDK Platforms, Build Tools, Platform Tools and NDK, then connect them to a project compiler. The full SDK is not installed on this device by this page.", 13f, muted))
                    card("Android SDK Manager", "Official Android Studio and SDK Manager downloads for supported desktop environments.", "Official SDK page") {
                        openUrl("https://developer.android.com/studio")
                    }
                    card("SDK command-line tools", "Official sdkmanager documentation and SDK package management reference.", "SDK Manager docs") {
                        openUrl("https://developer.android.com/tools/sdkmanager")
                    }
                    card("C++ NDK / Clang", "Official Android NDK documentation for native C and C++ builds.", "NDK guide") {
                        openUrl("https://developer.android.com/ndk/guides")
                    }
                }
            }
        }
        showTab(initialTab)
        setContentView(page)
    }

    private fun searchPypi(packageName: String, callback: (String, String?) -> Unit) {
        Thread {
            try {
                val safeName = URLEncoder.encode(packageName, "UTF-8")
                val connection = URL("https://pypi.org/pypi/" + safeName + "/json").openConnection() as HttpURLConnection
                connection.connectTimeout = 12000; connection.readTimeout = 12000
                connection.setRequestProperty("User-Agent", "CODE-Android-IDE/0.5")
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val rootJson = JSONObject(json)
                val info = rootJson.getJSONObject("info")
                val urls = rootJson.getJSONArray("urls")
                var wheel: String? = null
                for (i in 0 until urls.length()) {
                    val item = urls.getJSONObject(i)
                    val filename = item.optString("filename")
                    if (filename.endsWith(".whl") && filename.contains("none-any")) {
                        wheel = item.optString("url"); break
                    }
                }
                val message = info.optString("name") + "  " + info.optString("version") + "\n" +
                    info.optString("summary") + "\n" +
                    if (wheel != null) "Universal pure-Python wheel found." else "No universal wheel found; native/source packages are not supported yet."
                runOnUiThread { callback(message, wheel) }
            } catch (e: Exception) {
                runOnUiThread { callback("PyPI search failed: " + (e.message ?: "network error"), null) }
            }
        }.start()
    }

    private fun installPurePythonWheel(packageName: String, wheelUrl: String, callback: (String) -> Unit) {
        Thread {
            try {
                val root = File(filesDir, "python-packages").apply { mkdirs() }
                val target = File(root, packageName.lowercase().replace(Regex("[^a-z0-9_.-]"), "_"))
                target.mkdirs()
                val connection = URL(wheelUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000; connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "CODE-Android-IDE/0.5")
                val temp = File(cacheDir, "package-" + System.currentTimeMillis() + ".whl")
                connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                connection.disconnect()
                val safeRoot = target.canonicalPath + File.separator
                ZipInputStream(temp.inputStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(target, entry.name)
                        if (!out.canonicalPath.startsWith(safeRoot)) throw SecurityException("Unsafe archive path")
                        if (entry.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { output -> zip.copyTo(output) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                temp.delete()
                runOnUiThread {
                    callback("Installed " + packageName + ". Run Python again to load the package.")
                    log("Pip Installer installed " + packageName)
                }
            } catch (e: Exception) {
                runOnUiThread { callback("Install failed: " + (e.message ?: "unknown error")) }
            }
        }.start()
    }

    private fun showAndroidProjectInfo() {
        AlertDialog.Builder(this).setTitle("Android Project")
            .setMessage("The real project generator and on-device Gradle/SDK build system are the next major module. They are not enabled yet; this button does not pretend to build an APK.")
            .setPositiveButton("OK", null).show()
    }

    private fun toggleExplorer() {
        explorer.visibility = if (explorer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun toggleTerminal() {
        terminalVisible = !terminalVisible
        terminalPanel.visibility = if (terminalVisible) View.VISIBLE else View.GONE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
