package io.github.big_sw_little_sw.folio.content.internal

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionEndEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.DocumentStartEvent
import org.yaml.snakeyaml.events.Event
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.reader.UnicodeReader
import java.io.ByteArrayInputStream

/**
 * The YAML checker (design 13). SnakeYAML reads only parser events, so Folio keeps no node graph in memory. A node
 * graph uses about 60 times the size of the file. The checker applies the limits of the SnakeYAML composer itself:
 *
 * - Nesting deeper than 50 collections is an error.
 * - An alias must name an anchor that comes before it in the same document.
 * - More than 50 aliases to collections in a file is an error. Thus an alias bomb is invalid. The checker never
 *   expands an alias.
 * - A non-standard tag in the `tag:yaml.org,2002:` namespace is an error, for example the class name in
 *   `!!java.net.URL`. Other global tags and local tags, such as `!Ref`, are labels and stay valid.
 */
internal object YamlChecker {
    private const val MAX_NESTING = 50
    private const val MAX_COLLECTION_ALIASES = 50

    fun parses(bytes: ByteArray): Boolean = try {
        Yaml(LoaderOptions()).parse(UnicodeReader(ByteArrayInputStream(bytes))).all(Check()::accept)
    } catch (_: YAMLException) {
        false
    }

    /**
     * A Check holds the state of one parse. [accept] takes the events in order and returns false at the first error.
     */
    private class Check {
        /**
         * The map holds the anchors of the current document. The value is true for the anchor of a collection, because
         * only aliases to collections count toward the alias limit.
         */
        private val anchors = mutableMapOf<String, Boolean>()
        private var depth = 0
        private var collectionAliases = 0

        fun accept(event: Event): Boolean = when (event) {
            is DocumentStartEvent -> {
                anchors.clear()
                true
            }

            is CollectionStartEvent -> {
                event.anchor?.let { anchors[it] = true }
                ++depth <= MAX_NESTING && isAllowed(event.tag)
            }

            is CollectionEndEvent -> {
                depth--
                true
            }

            is ScalarEvent -> {
                event.anchor?.let { anchors[it] = false }
                isAllowed(event.tag)
            }

            is AliasEvent -> {
                alias(event.anchor)
            }

            else -> {
                true
            }
        }

        private fun alias(anchor: String): Boolean {
            val collection = anchors[anchor] ?: return false
            if (collection) collectionAliases++
            return collectionAliases <= MAX_COLLECTION_ALIASES
        }

        /**
         * The composer of SnakeYAML resolves a missing tag and the `!` tag from the value. Thus these two tags are
         * never custom global tags.
         */
        private fun isAllowed(tag: String?): Boolean = tag == null || tag == "!" || !Tag(tag).isCustomGlobal
    }
}
