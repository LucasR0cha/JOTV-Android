package com.joabtv.app.data.backup

import java.io.InputStream

internal fun readBackupText(input: InputStream): String {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val length = input.read(chunk)
        if (length < 0) break
        require(out.size() + length <= AppBackupRepository.MAX_JSON_BYTES) { "O backup ultrapassa o limite de 8 MB" }
        out.write(chunk, 0, length)
    }
    return out.toString("UTF-8").removePrefix("\uFEFF")
}
