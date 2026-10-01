package io.github.big_sw_little_sw.folio

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.github.big_sw_little_sw.folio.credential.CredentialKeyPairs
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

class ModularityTests {
    @Test
    fun `application modules respect their boundaries`() {
        ApplicationModules.of(FolioApplication::class.java).verify()
    }

    /** Private keys must not leave Folio (v1-scope); `CredentialKeyPairs` is for Git access only (ADR 0018). */
    @Test
    fun `no HTTP layer uses decrypted key pairs`() {
        val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackagesOf(FolioApplication::class.java)

        noClasses()
            .that()
            .resideInAPackage("..web..")
            .or()
            .areAnnotatedWith(RestController::class.java)
            .or()
            .areAnnotatedWith(RestControllerAdvice::class.java)
            .should()
            .dependOnClassesThat()
            .areAssignableTo(CredentialKeyPairs::class.java)
            .check(classes)
    }
}
