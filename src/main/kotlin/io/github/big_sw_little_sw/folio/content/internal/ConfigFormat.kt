package io.github.big_sw_little_sw.folio.content.internal

import io.github.big_sw_little_sw.folio.content.ItemPath
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.toml.TomlMapper
import java.io.ByteArrayInputStream
import java.util.Properties

/**
 * The validation status is metadata about the raw content. Folio serves the content unchanged in all cases, also when
 * the content does not parse.
 */
enum class ValidationStatus {
    VALID,
    INVALID,

    /** The format is not known, or the file is larger than [ConfigFormat.MAX_VALIDATED_BYTES]. */
    UNKNOWN,
}

/**
 * The formats that Folio validates. The file extension selects the format, and case is not important. To
 * add a format, add one entry with its extensions, its media type and one parser call. There is no validator
 * interface.
 *
 * The parsers check the syntax only. They never make application objects from the content, and they never run code.
 * They have limits on input size, nesting and aliases.
 */
enum class ConfigFormat(
    /**
     * The media type of a file response. Folio sends no charset, because Folio serves the raw bytes and does not know
     * their encoding.
     */
    val mediaType: String,
    private vararg val extensions: String,
) {
    /** [YamlChecker] gives the rules. */
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

    /** [Ini] gives the rules. */
    INI("text/plain", "ini") {
        override fun parses(bytes: ByteArray): Boolean = Ini.parses(bytes)
    },
    ;

    protected abstract fun parses(bytes: ByteArray): Boolean

    companion object {
        /** Folio validates only files of this size or less. */
        const val MAX_VALIDATED_BYTES = 3 * 1024 * 1024

        const val OCTET_STREAM = "application/octet-stream"

        private val jsonMapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
        private val tomlMapper = TomlMapper.builder().build()

        fun of(path: ItemPath): ConfigFormat? {
            val extension = path.name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }

        fun mediaTypeOf(path: ItemPath): String = of(path)?.mediaType ?: OCTET_STREAM

        /**
         * The caller must give only files of [MAX_VALIDATED_BYTES] or less, because this function does not check the
         * size.
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
