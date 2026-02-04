# Project Concept

## 1. Introduction
**Project Name**: CCTV (CCTVPrimary & CCTVViewer)

This project allows users to repurpose older or spare Android smartphones as effective CCTV usage security cameras. It consists of two distinct applications:
1.  **CCTVPrimary (The Camera)**: Installed on the device acting as the surveillance camera. It effectively turns the phone into a server that captures video/audio and streams it over the local network (or internet given proper routing).
2.  **CCTVViewer (The Monitor)**: Installed on the device used to view the live feed. It acts as a client, connecting to the Primary device to display video, play audio, and send control commands.

## 2. Core Value Proposition
*   **Reuse of Hardware**: Gives new life to old devices that are otherwise functional but unmatched for daily driver tasks.
*   **Zero Cost Security**: Eliminates the need for expensive proprietary CCTV hardware and subscriptions.
*   **Privacy First**: Direct peer-to-peer (P2P) streaming over TCP/IP ensuring data remains local or directly controlled by the user, without intermediate cloud storage servers.
*   **High Performance**: Optimized for low latency and smooth framerates using hardware-accelerated encoding/decoding and raw TCP sockets.

## 3. Key Features
*   **High-Efficiency Streaming**: Uses H.264 video compression and AAC/PCM audio for efficient bandwidth usage.
*   **Two-Way Audio (Talkback)**:
    *   **Listen**: Hear what is happening around the camera (Primary -> Viewer).
    *   **Speak**: Talk through the viewer to the camera (Viewer -> Primary), useful for intercom functionality or deterring intruders.
*   **Video Recording**: Capabilities to record video locally on the Primary device for evidence or later review.
*   **Remote Control**:
    *   **Flashlight**: Toggle the camera flash remotely for night visibility.
    *   **Camera Switch**: Switch between front and back cameras.
    *   **Quality Control**: Adjust resolution and bitrate dynamically based on network conditions.
*   **Robust Connectivity**: Auto-reconnection logic and session resumption to handle network fluctuations properly.

## 4. Design Philosophy
*   **"Mobile-First" Architecture**: Recognizes the constraints of mobile devices (battery, thermal limits, variable network). Features like the "Zero-Copy Pipeline" are implemented specifically to reduce CPU load and heat.
*   **Resiliency**: The system is designed as a state machine. It expects network failures and recovers gracefully without crashing or leaving the app in an inconsistent state.
*   **Device Agnostic**: While optimized, it includes fallback mechanisms (e.g., Buffer Mode vs. Surface Mode) to support a wide range of Android versions and manufacturer quirks (Samsung, Realme, etc.).

## 5. Intended User Experience
The user should be able to launch `CCTVPrimary` on an old phone, place it on a shelf, and forget about it. On their daily phone (`CCTVViewer`), they can open the app, instantly see the live feed, hear the room, and talk back if necessary, with no lag and high visual clarity.
