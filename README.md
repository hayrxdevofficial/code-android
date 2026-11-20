# CODE — Android Development Studio

CODE is an Android-first coding IDE designed for phones, tablets and Samsung DeX, with a VS Code-inspired dark interface.

## Current milestone: 0.2.0

Implemented in the current starter:
- Native Android app shell written in Kotlin.
- VS Code-inspired dark workbench: activity rail, Explorer, editor tab, line-number gutter and status bar.
- Responsive Explorer width for compact phones and larger tablet / DeX layouts.
- Import and Save As through Android Storage Access Framework.
- Basic editor area and terminal panel with built-in demo commands.
- HTML preview in an in-app WebView with a close control.
- GitHub Actions workflow for building a debug APK.

Still in development:
- Persistent multi-file workspace and real folder creation.
- Syntax highlighting, proper line-number synchronization, code completion and multiple tabs.
- Embedded Python runtime and package management.
- C/C++ compiler toolchain.
- Real shell / terminal execution.
- Android project templates, SDK / Gradle setup and on-device APK builds.
- HTML-to-APK packaging.

The terminal currently displays IDE messages; it is not yet a general-purpose shell. Python and C/C++ execution are not enabled in this milestone.

## Build

Open the project in Android Studio, install Android SDK 35 and JDK 17, then build the app module. The GitHub Actions workflow builds a debug APK and uploads it as an artifact.
