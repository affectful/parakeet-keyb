package com.parakeet.keys

internal object DictationFormatter {
    private val sentenceEndings = setOf('.', '!', '?', '\u2026')
    private val closingCharacters = setOf('"', '\'', ')', ']', '}', '\u2019', '\u201D')

    fun format(transcription: String, beforeCursor: String): String {
        val text = transcription.trim()
        if (text.isEmpty() || beforeCursor.isEmpty()) return text

        val trimmedBefore = beforeCursor.trimEnd()
        val semanticEnd = trimmedBefore.dropLastWhile { it in closingCharacters }.lastOrNull()
        val hasTrailingWhitespace = beforeCursor.last().isWhitespace()

        return when {
            semanticEnd in sentenceEndings -> {
                val separator = if (hasTrailingWhitespace) "" else " "
                separator + text
            }
            hasTrailingWhitespace -> lowercaseInitialWord(text)
            trimmedBefore.lastOrNull()?.isLetterOrDigit() == true -> ". $text"
            else -> " " + lowercaseInitialWord(text)
        }
    }

    private fun lowercaseInitialWord(text: String): String {
        val index = text.indexOfFirst(Char::isLetter)
        if (index < 0 || !text[index].isUpperCase()) return text

        val next = text.getOrNull(index + 1)
        if (text[index] == 'I' && (next == null || next.isWhitespace() || next == '\'' || next == '\u2019')) {
            return text
        }
        if (next?.isUpperCase() == true) return text

        return text.replaceRange(index, index + 1, text[index].lowercase())
    }
}
