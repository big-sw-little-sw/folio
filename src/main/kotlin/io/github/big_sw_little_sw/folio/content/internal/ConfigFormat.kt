package io.github.big_sw_little_sw.folio.content.internal

import io.github.big_sw_little_sw.folio.content.ItemPath
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.toml.TomlMapper
import java.io.ByteArrayInputStream
import java.util.Properties

/**
 * The validation status is metadata about the raw content. Folio serves the content in all cases, also when the
 * content does not parse (design 13).
 */
enum class ValidationStatus {
    VALID,
    INVALID,

    /** The format is not known, or the file is larger than [ConfigFormat.MAX_VALIDATED_BYTES]. */
    UNKNOWN,
}

/**
 * The formats that Folio validates (design 13). The file extension selects the format, and case is not important. To
 * add a format, add one entry with its extensions, its media type and one parser call. There is no validator
 * interface.
 *
 * The parsers check the syntax only. They never make application objects from the content, and they never run code.
 * They have limits on input size, nesting and aliases.
 */
enum class ConfigFormat(
    /**
     * The media type of a file response. Folio sends no charset, because Folio serves the raw bytes and does not know
     * their encoding (design 12.1).
     */
    val mediaType: String,
    private vararg val extensions: String,
) {
    /** SnakeYAML reads only parser events, and Folio checks the limits. [YamlChecker] gives the details. */
    YAML("application/yaml", "yaml", "yml") {
        override fun parses(bytes: ByteArray): Boolean = YamlChecker.parses(bytes)
    },

    /**
     * A JSON file must contain exactly one JSON value. The default stream constraints of Jackson limit the nesting
     * depth and the token sizes.
     */
    JSON("application/json", "json") {
        override fun parses(bytes: ByteArray): Boolean = try {
            !jsonMapper.readTree(bytes).isMissingNode
        } catch (_: JacksonException) {
            false
        }
    },

    /** `Properties.load` accepts almost all text. It rejects a malformed `\uXXXX` escape. */
    PROPERTIES("text/plain", "properties") {
        override fun parses(bytes: ByteArray): Boolean = try {
            Properties().load(ByteArrayInputStream(bytes))
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    },

    /** The TOML module of Jackson reads TOML 1.0. */
    TOML("application/toml", "toml") {
        override fun parses(bytes: ByteArray): Boolean = try {
            tomlMapper.readTree(bytes)
            true
        } catch (_: JacksonException) {
            false
        }
    },

    /**
     * An INI file must follow the rules of the Python `configparser` module in its default strict mode. [Ini] gives the
     * details.
     */
    INI("text/plain", "ini") {
        override fun parses(bytes: ByteArray): Boolean = Ini.parses(bytes)
    },
    ;

    protected abstract fun parses(bytes: ByteArray): Boolean

    companion object {
        /** Folio validates only files of 3 MB or less, and keeps only these files in memory (design 9). */
        const val MAX_VALIDATED_BYTES = 3 * 1024 * 1024

        const val OCTET_STREAM = "application/octet-stream"

        private val jsonMapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
        private val tomlMapper = TomlMapper.builder().build()

        /** Returns the format that the extension of [path] selects, or null if Folio does not know the format. */
        fun of(path: ItemPath): ConfigFormat? {
            val extension = path.name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }

        fun mediaTypeOf(path: ItemPath): String = of(path)?.mediaType ?: OCTET_STREAM

        /**
         * This function does not check the size of [bytes]. The caller gives only files of [MAX_VALIDATED_BYTES] or
         * less, as [ItemRead] does. A larger file is UNKNOWN (design 13).
         */
        fun validate(path: ItemPath, bytes: ByteArray): ValidationStatus {
            val format = of(path)
            return when {
                format == null -> ValidationStatus.UNKNOWN
                format.parses(bytes) -> ValidationStatus.VALID
                else -> ValidationStatus.INVALID
            }
        }
    }
}
