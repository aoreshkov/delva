package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.store.StoreHit

/**
 * The hits of a search as one JSON array the IDE's JSON editor can fold and search: per hit its document, path and
 * value, or the value's size where it was too long to show.
 */
internal fun searchResultsText(hits: List<StoreHit>): String = buildString {
    append('[')
    hits.forEachIndexed { i, hit ->
        if (i > 0) append(',')
        append("\n  {\n    \"docId\": ").append(hit.docId)
        hit.documentKey?.let { append(",\n    \"key\": ").appendJsonString(it) }
        append(",\n    \"path\": ").appendJsonString(hit.path)
        if (hit.json != null) {
            append(",\n    \"value\": ").append(hit.json.replace("\n", "\n    "))
        } else {
            append(",\n    \"valueOmittedBytes\": ").append(hit.encodedSize)
        }
        append("\n  }")
    }
    append(if (hits.isEmpty()) "]\n" else "\n]\n")
}

private fun StringBuilder.appendJsonString(s: String): StringBuilder {
    append('"')
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    return append('"')
}
