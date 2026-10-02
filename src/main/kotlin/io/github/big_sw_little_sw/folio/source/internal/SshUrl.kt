package io.github.big_sw_little_sw.folio.source.internal

import io.github.big_sw_little_sw.folio.credential.GitInstance
import io.github.big_sw_little_sw.folio.source.RepositoryPath

/**
 * The SSH URL of [repository] on [instance], for example `ssh://git@github.com:22/org/repo.git` (ADR 0022). It holds
 * no secret: authentication uses the credential's key, never the URL.
 */
fun sshUrl(
    instance: GitInstance,
    repository: RepositoryPath,
): String {
    val host = if (':' in instance.host) "[${instance.host}]" else instance.host
    return "ssh://${instance.user}@$host:${instance.port}/$repository"
}
