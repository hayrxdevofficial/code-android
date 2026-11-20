package dev.hayrx.code

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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile

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
    private lateinit var fileTree: LinearLayout
    private lateinit var tabStrip: LinearLayout
    private lateinit var lineNumbers: TextView
    private lateinit var fileLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var explorer: LinearLayout
    private lateinit var terminalPanel: LinearLayout
    private lateinit var root: LinearLayout
    private var terminalVisible = true

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
        val explorerWidth = if (widthDp >= 600) 220 else 132
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 0, 6, 0)
            setBackgroundColor(Color.rgb(45, 45, 45))
        }
        titleBar.addView(text("CODE", 17f, true).apply { setTextColor(Color.rgb(80, 160, 220)) },
            LinearLayout.LayoutParams(0, 44, 1f))
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
        body.addView(activityRail, LinearLayout.LayoutParams(42, -1))

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
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.END
            setPadding(8, 12, 10, 12)
            setBackgroundColor(bg)
            text = "1"
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
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(12, 6, 12, 6)
            setBackgroundColor(terminalBg)
            minLines = 2
            maxLines = 4
            isSingleLine = false
            setText("CODE terminal  •  0.3\n> Ready")
            isFocusable = false
            isFocusableInTouchMode = false
        }
        terminalPanel.addView(terminal, LinearLayout.LayoutParams(-1, 92))
        val commandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 0, 8, 4); setBackgroundColor(terminalBg) }
        val command = EditText(this).apply {
            hint = "IDE command (help)"
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
            if (value.isNotEmpty()) { handleCommand(value); command.setText("") }
        })
        terminalPanel.addView(commandRow)
        root.addView(terminalPanel)
        statusText = text("  Python   |   UTF-8   |   CODE", 10f, false).apply {
            setTextColor(Color.WHITE); setBackgroundColor(blue); setPadding(4, 0, 4, 0)
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, 22))
        setContentView(root)
        renderTabs()
        refreshWorkspace()
    }

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(fg)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun action(title: String, callback: () -> Unit) = text(title, 11f, false).apply {
        gravity = Gravity.CENTER; setPadding(7, 7, 7, 7)
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
            "version" -> log("CODE 0.3.0")
            else -> log("This is the IDE command panel, not a system shell. Command not found.")
        }
    }

    private fun runCode() {
        val name = active()?.name ?: ""
        when {
            name.endsWith(".html", true) || name.endsWith(".htm", true) -> showHtmlPreview()
            name.endsWith(".py", true) -> log("Python runtime is not connected yet. The editor and workspace are ready for runtime integration.")
            name.endsWith(".cpp", true) || name.endsWith(".cc", true) || name.endsWith(".c", true) ->
                log("C/C++ compiler is not connected yet.")
            else -> log("No runner available for this file type.")
        }
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
