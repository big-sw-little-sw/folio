package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.credential.uniqueCredentialName
import io.github.big_sw_little_sw.folio.source.Branch
import io.github.big_sw_little_sw.folio.source.RepositoryPath
import io.github.big_sw_little_sw.folio.source.SourceDefinition
import io.github.big_sw_little_sw.folio.source.SourcePath

/**
 * A source with a new credential on the test configuration's `example` instance, for tests that never contact the
 * Git service. The caller must be authenticated as a super admin.
 */
fun CredentialService.exampleSource(): SourceDefinition =
    SourceDefinition(
        create("example", uniqueCredentialName()).id,
        RepositoryPath("org/repo.git"),
        Branch("main"),
        SourcePath.parse("config"),
    )
