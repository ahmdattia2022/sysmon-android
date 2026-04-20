package io.ahmed.sysmon.util

private val HEX_ESCAPE = Regex("""\\x([0-9a-fA-F]{2})""")

/** Decode Huawei's `\xNN` hex-escape sequences back to their literal characters. */
fun hexDecode(text: String): String =
    HEX_ESCAPE.replace(text) { m -> m.groupValues[1].toInt(16).toChar().toString() }
