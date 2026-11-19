package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HubFile
import java.util.Locale

/** Immutable-commit resolve URL for both complete downloads and header probes. */
internal fun resolveUrl(endpoint: String, file: HubFile): String =
    endpoint.trimEnd('/') + "/${file.sourceRepo}/resolve/${file.sourceCommit}/" +
        file.path.split('/').joinToString("/") { segment ->
            segment.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
                val value = byte.toInt() and 0xff
                if (isUnreserved(value.toChar())) value.toChar().toString() else "%%%02X".format(Locale.ROOT, value)
            }
        }

/** RFC 3986's unreserved characters: letters, digits and `-`, `.`, `_`, `~`. */
private fun isUnreserved(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~"
