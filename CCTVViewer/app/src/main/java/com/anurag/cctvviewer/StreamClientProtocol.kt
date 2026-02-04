package com.anurag.cctvviewer

/**
 * Parses "CMD|k=v|k2=v2" style protocol lines into a map of params.
 * The first token ("CMD") is ignored.
 */
internal fun parseParams(line: String): Map<String, String> =
    line.split("|").drop(1).mapNotNull {
        val trimmed = it.trim()
        val p = trimmed.split("=", limit = 2)
        if (p.size == 2) p[0].trim() to p[1].trim() else null
    }.toMap()

