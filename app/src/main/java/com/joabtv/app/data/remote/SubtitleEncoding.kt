package com.joabtv.app.data.remote

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

private const val MAX_SUBTITLE_BYTES = 5 * 1024 * 1024

internal fun InputStream.readSubtitleBytes(limit: Int = MAX_SUBTITLE_BYTES): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Arquivo de legenda muito grande" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun decodeSubtitle(bytes: ByteArray, declared: Charset? = null): String {
    require(bytes.size <= MAX_SUBTITLE_BYTES) { "Arquivo de legenda muito grande" }
    val raw = when {
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() ->
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readSubtitleBytes() }
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                var skipped = 0
                var remaining = MAX_SUBTITLE_BYTES
                while (entry != null && skipped++ < 100) {
                    if (!entry.isDirectory && entry.name.substringAfterLast('.').lowercase() in listOf("srt", "vtt", "ass", "ssa")) break
                    remaining -= zip.readSubtitleBytes(remaining).size
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                require(entry != null && skipped <= 100) { "Arquivo sem legenda compatível" }
                zip.readSubtitleBytes(remaining)
            }
        else -> bytes
    }
    val charset = when {
        raw.size >= 2 && raw[0] == 0xff.toByte() && raw[1] == 0xfe.toByte() -> Charsets.UTF_16LE
        raw.size >= 2 && raw[0] == 0xfe.toByte() && raw[1] == 0xff.toByte() -> Charsets.UTF_16BE
        else -> declared
    }
    if (charset != null) return String(raw, charset).removePrefix("\uFEFF")
    return runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString() }
        .getOrElse { String(raw, Charset.forName("windows-1252")) }.removePrefix("\uFEFF")
}
