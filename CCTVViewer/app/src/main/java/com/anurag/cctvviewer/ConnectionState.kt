package com.anurag.cctvviewer

/**
 * High-level connection state shared between UI and StreamClient.
 *
 * Extracted from MainActivity.kt so StreamClient/UI can depend on it without MainActivity owning the type.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    STREAMING,
    RECOVERING
}

