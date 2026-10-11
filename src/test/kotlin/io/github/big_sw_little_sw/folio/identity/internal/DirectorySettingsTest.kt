package io.github.big_sw_little_sw.folio.identity.internal

import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** These tests check the startup validation of `folio.security.directory` and `folio.security.google` (design 6.5). */
class DirectorySettingsTest {
    private val google = IdentityProperties.Google(hostedDomain = "example.com")

    @Test
    fun `the token directory is the default, and the google settings have defaults`() {
        val properties = IdentityProperties()

        assertEquals(IdentityProperties.DirectoryType.TOKEN, properties.directory)
        assertEquals(true, properties.google.nestedGroups)
        assertEquals(Duration.ofMinutes(5), properties.google.groupCacheTtl)
        assertEquals(emptySet(), properties.google.serviceAccountProjects)
    }

    @Test
    fun `the google directory needs a hosted domain`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                IdentityProperties(
                    directory = IdentityProperties.DirectoryType.GOOGLE,
                    google = IdentityProperties.Google(serviceAccountProjects = setOf("workloads")),
                )
            }
        assertEquals("folio.security.directory google needs folio.security.google.hosted-domain", failure.message)
    }

    @Test
    fun `the google directory starts with a hosted domain and no workload projects`() {
        IdentityProperties(directory = IdentityProperties.DirectoryType.GOOGLE, google = google)
    }

    @Test
    fun `google settings without the google directory stop the start, because no account restriction would apply`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                IdentityProperties(directory = IdentityProperties.DirectoryType.TOKEN, google = google)
            }
        assertEquals("folio.security.google applies only when folio.security.directory is google", failure.message)
    }

    @Test
    fun `google settings must have the correct form, and the messages name the settings without values`() {
        mapOf(
            "folio.security.google.hosted-domain must not be blank" to {
                IdentityProperties.Google(
                    hostedDomain = " ",
                )
            },
            "folio.security.google.service-account-projects must list project IDs" to {
                IdentityProperties.Google(serviceAccountProjects = setOf("ok", "sa@bad"))
            },
            "folio.security.google.group-cache-ttl must be positive" to {
                IdentityProperties.Google(groupCacheTtl = Duration.ZERO)
            },
        ).forEach { (message, settings) ->
            assertEquals(message, assertFailsWith<IllegalArgumentException> { settings() }.message)
        }
    }

    @Test
    fun `the token directory cannot serve the Google issuer, because it does not restrict accounts`() {
        listOf("https://accounts.google.com", "https://Accounts.Google.com/").forEach { issuer ->
            val failure =
                assertFailsWith<IllegalArgumentException>(issuer) { directory(IdentityProperties(), issuer) }
            assertEquals(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri names Google, so folio.security.directory " +
                    "must be google",
                failure.message,
            )
        }
    }

    private fun directory(properties: IdentityProperties, issuer: String): Directory {
        val beans = StaticListableBeanFactory()
        val resourceServer = OAuth2ResourceServerProperties().apply { jwt.issuerUri = issuer }
        return Directories().directory(properties, resourceServer, beans.getBeanProvider(CloudIdentity::class.java))
    }
}
