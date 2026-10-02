package io.github.big_sw_little_sw.folio

import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.github.big_sw_little_sw.folio.configset.ConfigSetSources
import io.github.big_sw_little_sw.folio.credential.CredentialKeyPair
import io.github.big_sw_little_sw.folio.credential.CredentialKeyPairs
import io.github.big_sw_little_sw.folio.source.SourceAccess
import io.github.big_sw_little_sw.folio.source.SourceCache
import io.github.big_sw_little_sw.folio.sync.SyncedRevisions
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ModularityTests {
    private val modules = ApplicationModules.of(FolioApplication::class.java)

    @Test
    fun `application modules respect their boundaries`() {
        modules.verify()
    }

    /**
     * Regenerates the committed module diagrams and canvases; a change shows up in the diff. Modulith writes the
     * diagrams' relations in an order that differs between runs, so they are sorted to keep the files stable.
     */
    @Test
    fun `module diagrams and canvases are written to the docs`() {
        Documenter(modules, Documenter.Options.defaults().withOutputFolder(MODULE_DOCS)).writeDocumentation()
        Path(MODULE_DOCS).listDirectoryEntries("*.puml").forEach(::sortRelations)
    }

    private fun sortRelations(diagram: Path) {
        val lines = diagram.readText().split("\n")
        val relations = lines.filter { it.startsWith("Rel(") }.sorted().iterator()
        diagram.writeText(lines.joinToString("\n") { if (it.startsWith("Rel(")) relations.next() else it })
    }

    /**
     * Private keys must not leave Folio (v1-scope): `CredentialKeyPairs`, active and pending, and the key pairs it
     * returns are for Git access only (ADR 0018, ADR 0027).
     */
    @Test
    fun `no HTTP layer uses decrypted key pairs`() {
        httpLayer()
            .should()
            .dependOnClassesThat(
                assignableTo(CredentialKeyPairs::class.java).or(assignableTo(CredentialKeyPair::class.java)),
            ).check(classes)
    }

    /**
     * `SourceAccess`, `SourceCache`, `ConfigSetSources` and `SyncedRevisions` do not authorize; HTTP goes through the
     * services that do, such as `ConfigSetService`, which checks `CREDENTIAL_USE`, and `ConsumptionService`.
     */
    @Test
    fun `no HTTP layer uses Git access or unauthorized lookups directly`() {
        httpLayer()
            .should()
            .dependOnClassesThat(
                assignableTo(SourceAccess::class.java)
                    .or(assignableTo(SourceCache::class.java))
                    .or(assignableTo(ConfigSetSources::class.java))
                    .or(assignableTo(SyncedRevisions::class.java)),
            ).check(classes)
    }

    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackagesOf(FolioApplication::class.java)

    private fun httpLayer() =
        noClasses()
            .that()
            .resideInAPackage("..web..")
            .or()
            .areAnnotatedWith(RestController::class.java)
            .or()
            .areAnnotatedWith(RestControllerAdvice::class.java)

    private companion object {
        const val MODULE_DOCS = "docs/modules"
    }
}
