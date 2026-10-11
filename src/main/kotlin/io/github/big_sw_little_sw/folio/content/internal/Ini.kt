package io.github.big_sw_little_sw.folio.content.internal

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The INI checker (design 13). INI has no standard, so Folio uses the rules of `ConfigParser.read` in Python with the
 * default options. Most Python users use these rules.
 *
 * - The file is UTF-8. Lines end with LF, CRLF or CR.
 * - Blank lines and lines that start with `#` or `;` after leading whitespace are comments, also inside a value.
 * - `[name]` starts a section. The name is case-sensitive, and a second section with the same name is an error.
 *   `DEFAULT` is the exception: it can occur more than once.
 * - `key = value` and `key: value` are entries. The key is the text before the first `=` or `:`, and it must not be
 *   empty. Keys are not case-sensitive, and a second entry with the same key in a section is an error.
 * - A line that is indented more than its entry continues the value of that entry.
 * - An entry before the first section is an error, and so is a line that is none of these.
 */
internal object Ini {
    private const val DEFAULT_SECTION = "DEFAULT"
    private val SECTION = Regex("""\[(.+)]""")

    fun parses(bytes: ByteArray): Boolean {
        val text = decode(bytes) ?: return false
        return text.lines().all(Parse()::accept)
    }

    /** A Parse holds the state of one parse. [accept] takes the lines in order and returns false at the first error. */
    private class Parse {
        private val sections = mutableSetOf<String>()
        private val keys = mutableSetOf<Pair<String, String>>()
        private var section: String? = null

        /**
         * The indentation of the current entry, or null after a section header. A line with more indentation continues
         * the value of the entry, also after a comment or a blank line.
         */
        private var entryIndent: Int? = null

        fun accept(line: String): Boolean {
            val value = line.trim()
            val indent = line.indexOfFirst { !it.isWhitespace() }
            return isComment(value) || isContinuation(indent) || (header(value) ?: entry(value, indent))
        }

        private fun isComment(value: String) = value.isEmpty() || value.startsWith('#') || value.startsWith(';')

        private fun isContinuation(indent: Int) = entryIndent?.let { indent > it } ?: false

        /** Returns null if [value] is not a section header, and false if the section is a duplicate. */
        private fun header(value: String): Boolean? {
            val name = SECTION.matchAt(value, 0)?.groupValues?.get(1) ?: return null
            section = name
            entryIndent = null
            return sections.add(name) || name == DEFAULT_SECTION
        }

        private fun entry(value: String, indent: Int): Boolean {
            val current = section ?: return false
            // This code scans for the separator and does not use a regular expression. A regular expression with a
            // lazy key and optional whitespace takes quadratic time on a long line of whitespace without a separator.
            val separator = value.indexOfFirst { it == '=' || it == ':' }
            if (separator < 0) return false
            val key = value.substring(0, separator).trimEnd().lowercase()
            entryIndent = indent
            return key.isNotEmpty() && keys.add(current to key)
        }
    }

    private fun decode(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
