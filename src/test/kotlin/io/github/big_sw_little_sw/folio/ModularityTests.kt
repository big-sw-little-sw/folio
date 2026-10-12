package io.github.big_sw_little_sw.folio

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.github.big_sw_little_sw.folio.credential.CredentialKeys
import org.springframework.modulith.core.ApplicationModules
import org.springframework.stereotype.Controller
import kotlin.test.*

class ModularityTests {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(FolioApplication::class.java.packageName)

    @Test
    fun `application modules respect their boundaries`() {
        ApplicationModules.of(FolioApplication::class.java).verify()
    }

    /**
     * Only the git module decrypts keys, through CredentialKeys. Without this rule, an HTTP route or a page could use a
     * private key. The rule checks each class in a `web` package (routes, pages and compiled templates) and each
     * controller in another package. The rule does not cover the internal crypto classes of the credential module.
     */
    @Test
    fun `no HTTP layer calls CredentialKeys`() {
        noClasses()
            .that()
            .resideInAPackage("..web..")
            .or()
            .areMetaAnnotatedWith(Controller::class.java)
            .should()
            .dependOnClassesThat()
            .areAssignableTo(CredentialKeys::class.java)
            .check(classes)
    }
}
