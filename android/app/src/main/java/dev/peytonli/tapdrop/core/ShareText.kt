package dev.peytonli.tapdrop.core

data class SharedText(val kind: String, val text: String?, val url: String?)

object ShareText {
    private val httpUrl = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    fun parse(value: String): SharedText {
        val trimmed = value.trim()
        if (httpUrl.matches(trimmed)) return SharedText("url", null, trimmed)

        val lines = trimmed.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size == 2 && httpUrl.matches(lines.last())) return SharedText("url", null, lines.last())
        return SharedText("text", trimmed, null)
    }
}
