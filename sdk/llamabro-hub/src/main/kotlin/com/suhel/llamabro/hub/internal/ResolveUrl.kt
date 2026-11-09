package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HubFile

/** Immutable-commit resolve URL for both complete downloads and header probes. */
internal fun resolveUrl(endpoint: String, file: HubFile): String =
    endpoint.trimEnd('/') + "/${file.sourceRepo}/resolve/${file.sourceCommit}/" +
        file.path.split('/').joinToString("/") { segment ->
            segment.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
                val value = byte.toInt() and 0xff
                if (value in 65..90 || value in 97..122 || value in 48..57 ||
                    value == 45 || value == 46 || value == 95 || value == 126) value.toChar().toString()
                else "%%%02X".format(value)
            }
        }
