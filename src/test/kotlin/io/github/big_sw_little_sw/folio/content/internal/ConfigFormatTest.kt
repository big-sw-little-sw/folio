package io.github.big_sw_little_sw.folio.content.internal

import io.github.big_sw_little_sw.folio.content.ItemPath
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.*

class ConfigFormatTest {
    @Test
    fun `the extension selects the format, and case is not important`() {
        assertEquals(ConfigFormat.YAML, format("app.yaml"))
        assertEquals(ConfigFormat.YAML, format("sub/app.YML"))
        assertEquals(ConfigFormat.JSON, format("a.b/extra.json"))
        assertEquals(ConfigFormat.PROPERTIES, format("kafka/client.properties"))
        assertEquals(ConfigFormat.TOML, format("pyproject.Toml"))
        assertEquals(ConfigFormat.INI, format("setup.ini"))
        listOf("README.md", "yaml", "sub.yaml/file", "app.yaml.bak", "setup.cfg").forEach { assertNull(format(it), it) }
    }

    @Test
    fun `each format has its media type, and other files are octet streams`() {
        val types =
            listOf("a.yaml", "a.json", "a.toml", "a.properties", "a.ini", "a.cfg", "a")
                .map { ConfigFormat.mediaTypeOf(ItemPath(it)) }

        assertEquals(
            listOf(
                "application/yaml",
                "application/json",
                "application/toml",
                "text/plain",
                "text/plain",
                "application/octet-stream",
                "application/octet-stream",
            ),
            types,
        )
    }

    @Test
    fun `YAML is valid when each document parses`() {
        assertEquals(ValidationStatus.VALID, validate("a.yaml", "a: 1\nb: [x, y]\n---\nc: {d: e}\n"))
        assertEquals(ValidationStatus.VALID, validate("a.yaml", ""))
        assertEquals(ValidationStatus.VALID, validate("a.yaml", "base: &base {a: 1}\nderived:\n  <<: *base\n  b: 2\n"))
    }

    @Test
    fun `YAML that does not parse is invalid`() {
        assertEquals(ValidationStatus.INVALID, validate("a.yaml", "a: [1, 2\n"))
        assertEquals(ValidationStatus.INVALID, validate("a.yaml", "a: 1\n  b: 2\n"))
        assertEquals(ValidationStatus.INVALID, validate("a.yaml", "a: *undefined\n"))
        assertEquals(
            ValidationStatus.INVALID,
            ConfigFormat.validate(ItemPath("a.yaml"), byteArrayOf(0xC3.toByte(), 0x28)),
        )
    }

    @Test
    fun `a YAML alias bomb is invalid, and the check is fast`() {
        val bomb =
            buildString {
                append("a: &a [lol, lol, lol, lol, lol, lol, lol, lol, lol]\n")
                ('a'..'i').zipWithNext().forEach { (previous, level) ->
                    append("$level: &$level [${List(9) { "*$previous" }.joinToString()}]\n")
                }
            }

        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertEquals(ValidationStatus.INVALID, validate("bomb.yaml", bomb))
        }
    }

    @Test
    fun `deep YAML nesting is invalid`() {
        assertEquals(ValidationStatus.VALID, validate("a.yaml", "[".repeat(50) + "]".repeat(50)))
        assertEquals(ValidationStatus.INVALID, validate("a.yaml", "[".repeat(51) + "]".repeat(51)))
        assertEquals(ValidationStatus.INVALID, validate("a.yaml", "[".repeat(1000) + "]".repeat(1000)))
    }

    @Test
    fun `an alias names an anchor before it in the same document`() {
        assertEquals(ValidationStatus.VALID, validate("a.yaml", "a: &x 1\nb: *x\nc: &y [*y]\n"))
        assertEquals(ValidationStatus.INVALID, validate("a.yaml", "a: *x\nb: &x 1\n"))
        assertEquals(ValidationStatus.INVALID, validate("a.yaml", "a: &x 1\n---\nb: *x\n"))
    }

    @Test
    fun `a YAML file of 3 MB validates fast, because the checker keeps no node graph`() {
        val sequence = "- a\n".repeat(ConfigFormat.MAX_VALIDATED_BYTES / 4)

        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertEquals(ValidationStatus.VALID, validate("a.yaml", sequence))
        }
    }

    @Test
    fun `YAML with a non-standard !! tag is invalid, and validation makes no object`() {
        Instantiated.created = false
        val gadget =
            "!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1:1/\"]]]]"

        assertEquals(ValidationStatus.INVALID, validate("a.yaml", "marker: !!${Instantiated::class.java.name} {}\n"))
        assertEquals(ValidationStatus.INVALID, validate("a.yaml", "gadget: $gadget\n"))
        assertFalse(Instantiated.created)
        // Standard tags name only scalars and collections, so they stay valid. Local tags, such as the tags of
        // CloudFormation, are labels and stay valid.
        assertEquals(ValidationStatus.VALID, validate("a.yaml", "a: !!str 1\nb: !!map {c: !!int 2}\n"))
        assertEquals(
            ValidationStatus.VALID,
            validate("a.yaml", "bucket: !Ref MyBucket\nname: !Sub '\${AWS::Region}'\n"),
        )
        // Global tags outside the yaml.org namespace are labels too, so they stay valid.
        assertEquals(ValidationStatus.VALID, validate("a.yaml", "a: !<tag:example.com,2000:x> 1\n"))
        assertEquals(ValidationStatus.VALID, validate("a.yaml", "%TAG !e! tag:example.com,2000:\n---\na: !e!x 1\n"))
    }

    @Test
    fun `JSON is valid when it is exactly one value`() {
        assertEquals(ValidationStatus.VALID, validate("a.json", """{"a": [1, 2.5, "x", null, true]}"""))
        assertEquals(ValidationStatus.VALID, validate("a.json", "\"text\""))
        listOf("", "{", """{"a": 1,}""", "{} {}", "{} x", "{'a': 1}").forEach {
            assertEquals(ValidationStatus.INVALID, validate("a.json", it), it)
        }
    }

    @Test
    fun `deep JSON nesting is invalid, and the stack does not overflow`() {
        assertEquals(ValidationStatus.INVALID, validate("a.json", "[".repeat(100_000) + "]".repeat(100_000)))
    }

    @Test
    fun `properties are valid unless an escape is malformed`() {
        assertEquals(ValidationStatus.VALID, validate("a.properties", "a=1\nb: two\n# comment\nc = \\u0041\n"))
        assertEquals(ValidationStatus.INVALID, validate("a.properties", "a=\\u00zz\n"))
    }

    @Test
    fun `TOML 1 0 is valid, and TOML errors are invalid`() {
        val toml =
            """
            title = "folio"
            [server]
            port = 8080
            hosts = ["a", "b"]
            [[clients]]
            name = "x"
            """.trimIndent()

        assertEquals(ValidationStatus.VALID, validate("a.toml", toml))
        assertEquals(ValidationStatus.VALID, validate("a.toml", ""))
        listOf("a = ", "a = 1\na = 2", "[s]\n[s]", "a = [1, 2", "key without value", "a = 'x' b = 1").forEach {
            assertEquals(ValidationStatus.INVALID, validate("a.toml", it), it)
        }
    }

    @Test
    fun `deep TOML nesting is invalid, and the stack does not overflow`() {
        assertEquals(ValidationStatus.INVALID, validate("a.toml", "a = " + "[".repeat(100_000) + "]".repeat(100_000)))
    }

    @Test
    fun `INI accepts sections, both separators, comments and continuation lines`() {
        val ini =
            """
            ; A comment
            [server]
            host = example.com
            Port: 8080
            # Another comment
            motd = first line
              second line

               third line
            empty =
            [DEFAULT]
            a = 1
            [client]
            host = other.example.com
            [DEFAULT]
            b = 2
            """.trimIndent()

        assertEquals(ValidationStatus.VALID, validate("a.ini", ini))
        assertEquals(ValidationStatus.VALID, validate("a.ini", ""))
        assertEquals(ValidationStatus.VALID, validate("a.ini", "[s]\r\nkey = a = b\r\n"))
        // Section names are case-sensitive, so [s] and [S] are two different sections.
        assertEquals(ValidationStatus.VALID, validate("a.ini", "[s]\n[S]\n"))
    }

    @Test
    fun `INI rejects what configparser rejects in strict mode`() {
        mapOf(
            "an entry before the first section" to "key = value\n[s]\n",
            "a duplicate section" to "[s]\na = 1\n[s]\nb = 2\n",
            "a duplicate key" to "[s]\na = 1\na = 2\n",
            "a duplicate key in another case" to "[s]\nKey = 1\nkey = 2\n",
            "a line without a separator" to "[s]\njust text\n",
            "an empty key" to "[s]\n= value\n",
            "an empty section name" to "[]\n",
            "an indented line right after a section" to "[s]\n  no separator\n",
        ).forEach { (case, text) -> assertEquals(ValidationStatus.INVALID, validate("a.ini", text), case) }
        val notUtf8 = "[s]\na = ".toByteArray() + 0xFF.toByte()
        assertEquals(ValidationStatus.INVALID, ConfigFormat.validate(ItemPath("a.ini"), notUtf8))
    }

    @Test
    fun `a long INI line of whitespace without a separator is invalid, and the check is fast`() {
        val line = "a" + " ".repeat(ConfigFormat.MAX_VALIDATED_BYTES - 10) + "b"

        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertEquals(ValidationStatus.INVALID, validate("a.ini", "[s]\n$line\n"))
        }
    }

    private fun format(path: String) = ConfigFormat.of(ItemPath(path))

    private fun validate(path: String, text: String) = ConfigFormat.validate(ItemPath(path), text.toByteArray())
}

/**
 * An unsafe YAML loader would make an instance of this class for its tag. The test examines that no instance exists.
 */
class Instantiated(val value: String = "") {
    init {
        created = true
    }

    companion object {
        @Volatile
        var created = false
    }
}
