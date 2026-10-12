package io.github.big_sw_little_sw.folio.content.internal

import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.util.unit.DataSize
import java.time.Duration
import kotlin.test.*

class ContentPropertiesTest {
    @Test
    fun `the defaults are 100 MB and 30 seconds`() {
        assertEquals(DataSize.ofMegabytes(100), ContentProperties().maxFileSize)
        assertEquals(Duration.ofSeconds(30), ContentProperties().latestMaxAge)
    }

    @Test
    fun `the documented keys bind`() {
        val properties = bind("folio.content.max-file-size" to "1KB", "folio.content.latest-max-age" to "0s")

        assertEquals(1024, properties.maxFileSize.toBytes())
        assertEquals(Duration.ZERO, properties.latestMaxAge)
    }

    @Test
    fun `the limits must make sense`() {
        assertFailsWith<IllegalArgumentException> { ContentProperties(maxFileSize = DataSize.ofBytes(0)) }
        assertFailsWith<IllegalArgumentException> { ContentProperties(latestMaxAge = Duration.ofSeconds(-1)) }
        assertFailsWith<BindException> { bind("folio.content.max-file-size" to "0B") }
        ContentProperties(latestMaxAge = Duration.ZERO)
    }

    private fun bind(vararg values: Pair<String, String>): ContentProperties =
        Binder(MapConfigurationPropertySource(mapOf(*values)))
            .bindOrCreate("folio.content", ContentProperties::class.java)
}
