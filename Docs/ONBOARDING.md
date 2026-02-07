# Project Onboarding Guide

## 1. Introduction

Welcome to the **CCTV Project**. This is a sophisticated Android-based surveillance system that turns old phones into IP Cameras.

**This file is your starting point.** If you are a new developer (or an AI reset to zero), reading the files linked below _in order_ will restore your complete understanding of the system in minutes.

---

## 2. Mandatory Reading Order

Do not skip files. The system is complex, and "guessing" how the streaming protocol works will lead to bugs.

### Phase 1: The Big Picture

1.  **[CONCEPT.md](CONCEPT.md)**: What are we building? (Primary vs Viewer, CCTV logic).
2.  **[ARCHITECTURE.md](ARCHITECTURE.md)**: The high-level block diagram. How data flows from Camera -> Encoder -> Network -> Decoder -> Screen.
3.  **[WORKFLOW.md](WORKFLOW.md)**: The user journey. How to connect, authenticate, and what the buttons do.
4.  **[PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md)**: Where to find things in the folder tree.

### Phase 2: The Core Tech (The "Hard Stuff")

5.  **[CONNECTIONS.md](CONNECTIONS.md)**: How the TCP Handshake, Authentication, and Session Management work.
6.  **[PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md)**: The "Cheat Sheet" of every network command. The [Protocol & Message Fields](PROTOCOL_REFERENCE.md#protocol--message-fields-consolidated) section lists every command and its fields in one place—keep it open while coding.
    *   See also **[FRAMED_PROTOCOL.md](FRAMED_PROTOCOL.md)**: Deep dive into the Version 3 Binary Framing format and implementation details.
    *   See also **[STATE_MACHINE.md](STATE_MACHINE.md)**: The "Why" behind the protocol design—read this to understand the architectural philosophy and strict state machine rules.
7.  **[STREAMING.md](STREAMING.md)**: The Video Pipeline. Explains "Fan-Out", "Epochs" (critical for resolution changes), and Adaptive Bitrate.
8.  **[VIDEO_ENCODING.md](VIDEO_ENCODING.md)**: Deep dive into the Primary's Encoder (Surface Mode vs Buffer Mode).
9.  **[VIEWER_ARCHITECTURE.md](VIEWER_ARCHITECTURE.md)**: Deep dive into the Viewer's Jitter Buffer and Rendering Loop.

### Phase 3: Audio & Recording

10. **[AUDIO_COMMUNICATION.md](AUDIO_COMMUNICATION.md)**: How Two-Way Audio (Talkback) works and how we handle Echo/Noise.
11. **[VIDEO_CAPTURE.md](VIDEO_CAPTURE.md)**: CameraX configuration details.
12. **[VIDEO_RECORDING.md](VIDEO_RECORDING.md)**: Local storage logic (File rotation, Scoped Storage).

### Phase 4: Maintenance

13. **[TROUBLESHOOTING.md](TROUBLESHOOTING.md)**: **CRITICAL**. Read this before fixing "Black Screens" or "Green Flashes". It documents device-specific hacks for Samsung and OnePlus.

### Phase 5: Reference Materials

For standard Android patterns (e.g. **CameraX**, **Activity Lifecycle**, **Views & UI**), consult the official Android documentation. Cursor AI should use these when implementing standard Android patterns.

---

## 3. Project Knowledge Base

**[PROJECT_KNOWLEDGE_BASE.md](PROJECT_KNOWLEDGE_BASE.md)**

This document has been restored and contains critical details on:
- Device-specific hacks (Samsung M30s, OnePlus Nord).
- Technical constraints (16KB alignment).
- Architectural decision records.

**Read this if you run into "Black Screen" or "Crash on Start" issues on specific phones.**
- **Key Constraints**:
  - **16KB Page Size**: Native libraries must be 16KB-aligned (Android 15+ requirement).
  - **Scoped Storage**: We cannot write arbitrary files. We use MediaStore.
  - **Background Restrictions**: The Primary App MUST run as a Foreground Service to survive doze mode.

---

## 4. Quick Start for Developers

1.  **Open Project**: Use Android Studio 2025.2.2+.
2.  **Sync**: Ensure Gradle sync completes.
3.  **Build**: Both `CCTVPrimary` and `CCTVViewer` are independent.
4.  **Run Primary**:
    - Grant Permissions.
    - Note the IP Address on screen.
    - Press "Start Capture".
5.  **Run Viewer**:
    - Enter the Primary's IP.
    - Enter Password (`123456`).
    - Connect.

**End of Onboarding**
