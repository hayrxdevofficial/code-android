# CODE — Android Development Studio

CODE is an Android-first coding IDE designed for phones, tablets and Samsung DeX, with a VS Code-inspired dark interface.

## Current milestone: 0.4.0

Implemented:
- Native Android application written in Kotlin.
- VS Code-inspired workbench with activity rail, Explorer, editor, tabs, terminal panel and status bar.
- Responsive Explorer sizing for phones and larger screens.
- Open a workspace folder using Android's Storage Access Framework.
- Persist workspace folder permission and restore the selected workspace after restarting the app.
- Browse real folders and files; expand/collapse folders in the Explorer.
- Create real files and folders inside the selected workspace.
- Import files into the workspace, or open them directly when no workspace is selected.
- Open multiple files in editor tabs and switch between them.
- Track unsaved edits and save files back to their original URI; Save As is available for new files.
- Dynamic line-number gutter based on the current document.
- Basic in-file text search.
- HTML preview in an in-app WebView.
- Basic IDE commands: help, clear, pwd, ls, whoami and version.
- Embedded Python 3.13 runtime using Chaquopy; run the active `.py` file inside the IDE and display captured output/errors in the terminal panel.
- GitHub Actions workflow for a debug APK build.

## Not yet implemented

These are still planned modules, not working features yet:
- Project-specific Python package manager and editable requirements installation.
- C/C++ compiler toolchain and runtime.
- Real operating-system shell / terminal execution.
- Full syntax highlighting, code completion, diagnostics and advanced editor features.
- Android project templates, SDK/Gradle package manager and on-device APK builds.
- HTML-to-APK project packaging.
- Git integration.

The terminal is currently an IDE output/command panel, not a general-purpose shell. C/C++ Run is not connected yet. Python runs through the embedded Chaquopy runtime; third-party package installation is not yet exposed in the IDE. The Android Project button is informational and does not claim to build an APK.

## Build

Open the project in Android Studio, install Android SDK 35, JDK 17 and Python 3.13, then build the app module. GitHub Actions builds a debug APK and uploads it as an artifact when the workflow succeeds.
