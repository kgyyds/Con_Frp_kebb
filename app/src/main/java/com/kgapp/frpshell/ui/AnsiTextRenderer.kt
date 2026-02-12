package com.kgapp.frpshell.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

private const val ESC = '\u001B'

class AnsiAnnotatedBuffer {
    private var builder = AnnotatedString.Builder()
    private var currentColor: Color? = null
    private var lastRaw: String = ""

    fun update(newText: String): AnnotatedString {
        val appendPart = if (newText.startsWith(lastRaw)) {
            newText.substring(lastRaw.length)
        } else {
            builder = AnnotatedString.Builder()
            currentColor = null
            newText
        }

        parseAndAppend(appendPart)
        lastRaw = newText
        return builder.toAnnotatedString()
    }

    private fun parseAndAppend(text: String) {
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch == ESC && index + 1 < text.length && text[index + 1] == '[') {
                val mIndex = text.indexOf('m', startIndex = index + 2)
                if (mIndex == -1) return
                val codes = text.substring(index + 2, mIndex)
                applySgrCodes(codes)
                index = mIndex + 1
                continue
            }

            val nextEsc = text.indexOf(ESC, startIndex = index).let { if (it == -1) text.length else it }
            appendText(text.substring(index, nextEsc))
            index = nextEsc
        }
    }

    private fun applySgrCodes(codes: String) {
        val values = if (codes.isBlank()) listOf(0) else codes.split(';').mapNotNull { it.toIntOrNull() }
        values.forEach { code ->
            when (code) {
                0 -> currentColor = null
                30 -> currentColor = Color(0xFF000000)
                31 -> currentColor = Color(0xFFCD3131)
                32 -> currentColor = Color(0xFF0DBC79)
                33 -> currentColor = Color(0xFFE5E510)
                34 -> currentColor = Color(0xFF2472C8)
                35 -> currentColor = Color(0xFFBC3FBC)
                36 -> currentColor = Color(0xFF11A8CD)
                37 -> currentColor = Color(0xFFE5E5E5)
                90 -> currentColor = Color(0xFF666666)
                91 -> currentColor = Color(0xFFF14C4C)
                92 -> currentColor = Color(0xFF23D18B)
                93 -> currentColor = Color(0xFFF5F543)
                94 -> currentColor = Color(0xFF3B8EEA)
                95 -> currentColor = Color(0xFFD670D6)
                96 -> currentColor = Color(0xFF29B8DB)
                97 -> currentColor = Color(0xFFFFFFFF)
            }
        }
    }

    private fun appendText(part: String) {
        if (part.isEmpty()) return
        val start = builder.length
        builder.append(part)
        currentColor?.let { color ->
            builder.addStyle(SpanStyle(color = color), start, builder.length)
        }
    }
}
