package io.github.big_sw_little_sw.folio.consumption

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ConfigFormatTest {
    @Test
    fun `the format follows the extension, ignoring case`() {
        assertEquals(ConfigFormat.YAML, ConfigFormat.of("app.yaml"))
        assertEquals(ConfigFormat.YAML, ConfigFormat.of("sub/app.YML"))
        assertEquals(ConfigFormat.JSON, ConfigFormat.of("a.b/extra.json"))
        assertEquals(ConfigFormat.PROPERTIES, ConfigFormat.of("kafka/client.properties"))
        listOf("README.md", "yaml", "sub.yaml/file", "app.yaml.bak").forEach { assertNull(ConfigFormat.of(it), it) }
    }

    @Test
    fun `YAML is valid when every document parses`() {
        assertEquals(ValidationStatus.VALID, yaml("a: 1\nb: [x, y]\n---\nc: {d: e}\n"))
        assertEquals(ValidationStatus.VALID, yaml(""))
        assertEquals(ValidationStatus.VALID, yaml("base: &base {a: 1}\nderived:\n  <<: *base\n  b: 2\n"))
    }

    @Test
    fun `malformed YAML is invalid`() {
        assertEquals(ValidationStatus.INVALID, yaml("a: [1, 2\n"))
        assertEquals(ValidationStatus.INVALID, yaml("a: 1\n  b: 2\n"))
        assertEquals(ValidationStatus.INVALID, yaml("a: *undefined\n"))
        assertEquals(ValidationStatus.INVALID, ConfigFormat.YAML.validate(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun `a YAML alias bomb is rejected quickly as invalid`() {
        val levels = ('a'..'i').toList()
        val bomb =
            buildString {
                append("a: &a [lol, lol, lol, lol, lol, lol, lol, lol, lol]\n")
                levels.zipWithNext().forEach { (previous, level) ->
                    append("$level: &$level [${List(9) { "*$previous" }.joinToString()}]\n")
                }
            }

        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertEquals(ValidationStatus.INVALID, yaml(bomb))
        }
    }

    @Test
    fun `YAML with custom global tags is invalid and nothing is instantiated`() {
        Instantiated.created = false
        val gadget =
            "!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1:1/\"]]]]"

        assertEquals(ValidationStatus.INVALID, yaml("marker: !!${Instantiated::class.java.name} {}\n"))
        assertEquals(ValidationStatus.INVALID, yaml("gadget: $gadget\n"))
        assertFalse(Instantiated.created)
        // Standard tags only label scalars and collections, and local tags such as CloudFormation's stay labels.
        assertEquals(ValidationStatus.VALID, yaml("a: !!str 1\nb: !!map {c: !!int 2}\n"))
        assertEquals(ValidationStatus.VALID, yaml("bucket: !Ref MyBucket\nname: !Sub '\${AWS::StackName}-x'\n"))
    }

    @Test
    fun `duplicate YAML keys are accepted, since parsing does not construct mappings`() {
        assertEquals(ValidationStatus.VALID, yaml("a: 1\na: 2\n"))
    }

    @Test
    fun `JSON is valid when it is exactly one value`() {
        assertEquals(ValidationStatus.VALID, json("""{"a": [1, 2.5, "x", null, true]}"""))
        assertEquals(ValidationStatus.VALID, json("\"text\""))
        listOf("", "{", """{"a": 1,}""", "{} {}", "{} x", "{'a': 1}").forEach {
            assertEquals(ValidationStatus.INVALID, json(it), it)
        }
    }

    @Test
    fun `deeply nested JSON is invalid rather than exhausting the stack`() {
        assertEquals(ValidationStatus.INVALID, json("[".repeat(100_000) + "]".repeat(100_000)))
    }

    @Test
    fun `properties are valid unless an escape is malformed`() {
        assertEquals(ValidationStatus.VALID, properties("a=1\nb: two\n# comment\nc = \\u0041\n"))
        assertEquals(ValidationStatus.INVALID, properties("a=\\u00zz\n"))
    }

    @Test
    fun `files above the size limit are not validated`() {
        val large = ByteArray(ConfigFormat.MAX_VALIDATED_BYTES + 1) { '['.code.toByte() }

        assertEquals(ValidationStatus.UNKNOWN, ConfigFormat.JSON.validate(large))
        assertEquals(ValidationStatus.UNKNOWN, ConfigFormat.YAML.validate(large))
    }

    private fun yaml(text: String) = ConfigFormat.YAML.validate(text.toByteArray())

    private fun json(text: String) = ConfigFormat.JSON.validate(text.toByteArray())

    private fun properties(text: String) = ConfigFormat.PROPERTIES.validate(text.toByteArray())
}

/** Records whether anything constructed it, as an unsafe YAML loader would for its tag, with its [value]. */
class Instantiated(
    val value: String = "",
) {
    init {
        created = true
    }

    companion object {
        @Volatile
        var created = false
    }
}
