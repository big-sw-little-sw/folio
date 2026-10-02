package io.github.big_sw_little_sw.folio.consumption

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.reader.UnicodeReader
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.util.Properties

/** Validation is metadata about raw content, never a condition for serving it (design 15.1, 15.2). */
enum class ValidationStatus {
    /** Not a known format, or too large to validate. */
    UNKNOWN,
    VALID,
    INVALID,
}

/**
 * The formats Folio validates (design 15.3), recognized by file extension, and the media type it serves them with.
 * Validation only parses: it never constructs objects from content, resolves tags or executes anything, and it is
 * bounded in input size, nesting and aliases (design 23.3, ADR 0037).
 */
enum class ConfigFormat(
    val mediaType: String,
) {
    YAML("application/yaml") {
        /**
         * Composes the node graph of every document without constructing objects. SnakeYAML's defaults reject
         * custom global tags such as `!!java.net.URL`, more than 50 aliases to collections and collections nested
         * more than 50 deep; aliases are never expanded.
         */
        override fun isWellFormed(bytes: ByteArray): Boolean =
            try {
                Yaml(SafeConstructor(LoaderOptions()))
                    .composeAll(UnicodeReader(ByteArrayInputStream(bytes)))
                    .count()
                true
            } catch (_: YAMLException) {
                false
            }
    },

    /** One JSON value and nothing after it; Jackson's default stream constraints bound nesting and token sizes. */
    JSON("application/json") {
        override fun isWellFormed(bytes: ByteArray): Boolean =
            try {
                !jsonMapper.readTree(bytes).isMissingNode
            } catch (_: JacksonException) {
                false
            }
    },

    /** `java.util.Properties` accepts almost any text; it rejects malformed `\uXXXX` escapes. */
    PROPERTIES("text/plain") {
        override fun isWellFormed(bytes: ByteArray): Boolean =
            try {
                Properties().load(ByteArrayInputStream(bytes))
                true
            } catch (_: IllegalArgumentException) {
                false
            }
    },
    ;

    protected abstract fun isWellFormed(bytes: ByteArray): Boolean

    fun validate(bytes: ByteArray): ValidationStatus =
        when {
            bytes.size > MAX_VALIDATED_BYTES -> ValidationStatus.UNKNOWN
            isWellFormed(bytes) -> ValidationStatus.VALID
            else -> ValidationStatus.INVALID
        }

    companion object {
        /** Larger files are served without validation; SnakeYAML's default input limit is the same. */
        const val MAX_VALIDATED_BYTES = 3 * 1024 * 1024

        private val jsonMapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

        /** The format of the file at [path] by its extension, ignoring case; null if Folio does not know it. */
        fun of(path: String): ConfigFormat? =
            when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
                "yaml", "yml" -> YAML
                "json" -> JSON
                "properties" -> PROPERTIES
                else -> null
            }
    }
}
