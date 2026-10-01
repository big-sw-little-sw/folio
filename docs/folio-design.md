# Configuration Management Platform

## Architecture and Design Specification

**Status:** Proposed  
**Target stack:** Kotlin, Spring Boot 4, Spring Security, PostgreSQL  
**Initial source support:** Remote Git over SSH  
**Initial synchronization:** Polling  
**Initial consumption model:** Pull-based  

---

## 1. Executive summary

This document specifies a configuration-management platform for onboarding, organizing, securing, versioning, synchronizing, and serving configuration bundles.

The platform organizes resources as a hierarchy:

```text
Namespace
└── Namespace
    └── ConfigSet
        └── ConfigItem
            └── Revision
```

The initial implementation uses remote Git repositories as the source of truth. A ConfigSet maps to a directory within exactly one Git source. ConfigItems correspond to files beneath that directory, and Git commits provide immutable revisions.

The platform separates four major concerns:

1. **Identity and authentication**, handled primarily by Spring Security.
2. **Hierarchical authorization**, implemented as application-domain policy.
3. **Content access and versioning**, hidden behind source-neutral interfaces.
4. **Change detection and synchronization**, initially implemented through polling.

The design intentionally leaves room for:

- database-backed configuration;
- webhook-triggered synchronization;
- HTTPS Git credentials and provider-native applications;
- generated or computed views;
- composite ConfigSets above the source layer;
- push-based delivery;
- a Spring Cloud Config-compatible facade.

These are not part of v1 unless explicitly stated otherwise.

---

## 2. Goals

The platform should:

- organize configuration through nested namespaces;
- make ConfigSets stable resources with immutable IDs;
- support inheritable, action-based authorization;
- allow policy overrides at namespace and ConfigSet boundaries;
- support browser-based onboarding and administration;
- support CLI, SDK, script, workload, and agent consumption;
- use OIDC/OAuth 2.0 where available;
- support direct LDAP deployments when necessary;
- keep Git-provider-specific behavior out of the core domain;
- serve complete bundles and individual files;
- support immutable revision retrieval;
- validate known content formats without making validation a storage prerequisite;
- preserve the option to introduce other sources later.

---

## 3. Non-goals for v1

The following are intentionally deferred:

- explicit deny rules;
- a general-purpose policy language;
- attribute-based access control expressions;
- automatic webhook installation;
- webhook-only synchronization;
- GitHub App, GitLab OAuth, or similar provider-specific onboarding;
- database-backed ConfigSets;
- editing Git-backed content through the platform;
- config overlay or profile-composition semantics;
- multi-source or composite ConfigSets;
- push-based delivery to consumers;
- per-file authorization;
- Spring Cloud Config compatibility;
- secret-management functionality for configuration payloads.

---

## 4. High-level architecture

```text
Clients
├── Administrative Web UI
│   └── OIDC authorization-code login or LDAP-backed login
├── CLI
│   └── OAuth device flow for interactive users
├── Scripts
│   ├── OAuth client credentials for automation
│   └── OAuth device flow for interactive use
├── Generated or handwritten SDKs
│   └── Bearer-token authentication
├── Applications and workloads
│   └── Workload identity or OAuth client credentials
└── Agents
    └── Same consumption API and authentication as other clients

                    │
                    ▼

Spring Boot 4 application
├── Client/API layer
│   ├── Administration API
│   ├── Discovery API
│   ├── Consumption API
│   └── Optional server-rendered UI or SPA backend
├── Spring Security
│   ├── oauth2Login for browser users
│   ├── OAuth2 Resource Server for bearer tokens
│   ├── LDAP authentication for direct on-premises deployments
│   ├── session management
│   └── request and method authorization hooks
├── Domain layer
│   ├── Namespace management
│   ├── ConfigSet management
│   ├── policy resolution
│   ├── authorization decisions
│   ├── content retrieval
│   ├── synchronization orchestration
│   └── validation
├── Source adapters
│   ├── Git source, v1
│   └── Database source, future
└── Infrastructure
    ├── PostgreSQL
    ├── credential protection
    ├── local Git cache
    ├── background scheduling
    └── audit and observability

                    │
                    ▼

External systems
├── Identity provider or LDAP directory
├── Remote Git service instances
└── Vault, KMS, Key Vault, or externally supplied master secret
```

### 4.1 Architectural boundary

Spring Security establishes and carries identity. The domain policy service decides whether that identity may perform an action on a namespace or ConfigSet.

The Git adapter retrieves content. It does not decide authorization.

The synchronization trigger requests synchronization. It does not implement content retrieval.

---

## 5. Client architecture

### 5.1 Administrative Web UI

The UI supports:

- namespace creation, rename, move, and deletion;
- ConfigSet onboarding;
- source configuration;
- policy management;
- policy-decision explanation;
- sync status and manual synchronization;
- repository-access validation;
- bundle and file browsing;
- content validation results;
- audit-history inspection.

The UI should use paths for human navigation but IDs for mutations and durable references.

Authentication:

```text
Browser
  → OIDC authorization-code flow
  → Spring Security session
  → administration API
```

A direct LDAP deployment can replace OIDC login with Spring Security LDAP authentication and a server-side session.

### 5.2 CLI

Illustrative commands:

```bash
configctl login
configctl discover engineering/ai/hive/production/service-a
configctl bundle get cfg_01H...
configctl file get cfg_01H... application.yaml
configctl file get cfg_01H... application.yaml --revision rev_abc123
configctl sync cfg_01H...
configctl policy explain cfg_01H... CONFIG_ITEM_READ
```

Interactive authentication should use OAuth device flow where the identity platform supports it. The API sees only the resulting bearer token.

### 5.3 Scripts and CI/CD

Automation should use client credentials, workload identity, certificate-based credentials, or another non-human application identity.

Example:

```bash
curl \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Accept: application/yaml' \
  https://config.example/api/v1/configsets/cfg_01H/files/application.yaml
```

Human credentials and personal access tokens should not be the default for continuous consumption.

### 5.4 SDKs

SDKs should be thin clients over the HTTP API rather than alternate policy or composition implementations.

Illustrative Kotlin API:

```kotlin
val configSet = client.configSets().get(configSetId)
val bytes = client.configSets()
    .file(configSetId, "application.yaml")
    .latest()
```

An SDK may provide discovery conveniences:

```kotlin
val configSet = client.discovery()
    .resolve("engineering/ai/hive/production/service-a")

val bytes = client.configSets()
    .file(configSet.id, "application.yaml")
    .latest()
```

Discovery is not required on every request. A consumer normally resolves a path once, persists the stable ConfigSet ID, and retrieves content by ID thereafter.

### 5.5 Agents

Agents consume the same API as scripts and applications. The platform should not expose a separate AI-specific content or authorization path.

---

## 6. Resource hierarchy

### 6.1 Namespace

A Namespace is an organizational and policy boundary. It can contain child namespaces and ConfigSets.

```text
engineering
└── ai
    ├── hive
    │   ├── development
    │   │   └── service-a
    │   └── production
    │       └── service-a
    └── assistant
        └── backend
            └── application
```

`engineering`, `ai`, `hive`, `production`, and `backend` are namespaces. `service-a` and `application` are ConfigSets.

Namespace names are unique among siblings. Identity is based on immutable IDs, not names or paths.

### 6.2 ConfigSet

A ConfigSet is the:

- authorization boundary for configuration content;
- synchronization boundary;
- source-association boundary;
- version-selection boundary;
- primary resource consumed by clients.

A ConfigSet maps to exactly one source in v1.

For a Git source, a ConfigSet typically maps to:

```text
repository + ref configuration + root path
```

### 6.3 ConfigItem

A ConfigItem is an individual content item inside a ConfigSet. For Git-backed sources, it normally corresponds to a path beneath the ConfigSet root.

```text
ConfigSet: service-a
├── application.yaml
├── logging.yaml
└── kafka/client.properties
```

The full relative path identifies the ConfigItem within its ConfigSet.

### 6.4 Revision

A Revision is an immutable source-specific version exposed through a source-neutral platform identifier.

Examples:

- Git commit SHA;
- future database version ID;
- future object-store version ID.

The public API should expose `latest` and immutable revision identifiers. Branches, tags, and Git ref syntax remain source configuration or adapter concerns rather than general consumption API concepts.

---

## 7. Identity versus path

Paths are for human discovery and display:

```text
engineering/ai/hive/production/service-a
```

Stable IDs are for durable client references:

```text
cfg_01J8ZX...
```

A namespace rename or move changes its path but not its ID. A ConfigSet retains its ID when its containing namespace moves.

Authorization follows current namespace membership, not the historical path. Therefore, moving a ConfigSet can change its inherited permissions without breaking its consumer URL.

This requires:

- move operations to be explicitly authorized;
- an effective-policy preview before committing a move;
- audit records containing old and new parents and policies;
- policy caches to be invalidated after a move;
- optional protection against moves that would make a previously restricted ConfigSet public.

Renamed namespace slugs may be reused. Uniqueness is enforced only among current siblings. Historical audit data continues to identify resources by immutable ID and records the path visible at the time of the event.

---

## 8. Kotlin domain model

Use Kotlin value classes for identifiers and sealed interfaces for closed domain alternatives.

```kotlin
@JvmInline
value class NamespaceId(val value: UUID)

@JvmInline
value class ConfigSetId(val value: UUID)

@JvmInline
value class SourceId(val value: UUID)

@JvmInline
value class RevisionId(val value: String)
```

### 8.1 Namespace

```kotlin
data class Namespace(
    val id: NamespaceId,
    val parentId: NamespaceId?,
    val name: String,
    val slug: String,
    val version: Long
)
```

### 8.2 ConfigSet

```kotlin
data class ConfigSet(
    val id: ConfigSetId,
    val namespaceId: NamespaceId,
    val name: String,
    val slug: String,
    val sourceId: SourceId,
    val policyMode: PolicyMode,
    val version: Long
)

enum class PolicyMode {
    INHERIT,
    OVERRIDE
}
```

`OVERRIDE` applies per action. Defining a local rule for one action does not discard inherited rules for other actions.

### 8.3 Resource references

```kotlin
sealed interface ResourceRef {
    data class NamespaceRef(val id: NamespaceId) : ResourceRef
    data class ConfigSetRef(val id: ConfigSetId) : ResourceRef
}
```

### 8.4 Revisions

```kotlin
sealed interface RevisionRef {
    data object Latest : RevisionRef
    data class Exact(val id: RevisionId) : RevisionRef
}
```

### 8.5 Content

```kotlin
data class ConfigItemDescriptor(
    val path: String,
    val mediaType: String?,
    val size: Long,
    val validation: ValidationSummary
)

data class ConfigBundle(
    val configSetId: ConfigSetId,
    val revision: RevisionId,
    val items: List<ConfigItemDescriptor>
)

data class ConfigContent(
    val configSetId: ConfigSetId,
    val revision: RevisionId,
    val path: String,
    val mediaType: String?,
    val bytes: ByteArray,
    val validation: ValidationSummary
)
```

---

## 9. Authorization model

### 9.1 Action-based permissions

Persist actions rather than treating broad roles as the fundamental model.

```kotlin
enum class Action {
    NAMESPACE_VIEW,
    NAMESPACE_CREATE,
    NAMESPACE_RENAME,
    NAMESPACE_MOVE,
    NAMESPACE_DELETE,

    CONFIG_SET_VIEW,
    CONFIG_SET_CREATE,
    CONFIG_SET_MOVE,
    CONFIG_SET_DELETE,

    CONFIG_ITEM_READ,
    CONFIG_ITEM_CREATE,
    CONFIG_ITEM_UPDATE,
    CONFIG_ITEM_DELETE,

    CONFIG_VERSION_LIST,
    CONFIG_VERSION_READ,
    CONFIG_VERSION_ROLLBACK,

    POLICY_VIEW,
    POLICY_UPDATE,

    SOURCE_VIEW,
    SOURCE_UPDATE,
    SOURCE_SYNC
}
```

User-facing roles may be templates that expand into actions, but authorization decisions ultimately evaluate actions.

### 9.2 Subjects

```kotlin
sealed interface SubjectSelector {
    data object Public : SubjectSelector
    data object AnyAuthenticated : SubjectSelector

    data class User(
        val provider: IdentityProvider,
        val externalId: String
    ) : SubjectSelector

    data class Group(
        val provider: IdentityProvider,
        val externalId: String
    ) : SubjectSelector

    data class Application(
        val provider: IdentityProvider,
        val externalId: String
    ) : SubjectSelector
}

enum class IdentityProvider {
    ENTRA,
    LDAP,
    OIDC
}
```

Use stable provider identifiers, not mutable display names.

### 9.3 Inheritance semantics

For a requested action:

1. Start at the target ConfigSet or Namespace.
2. Look for a local rule for that action.
3. If a rule exists, it is authoritative.
4. If no rule exists, walk to the parent Namespace.
5. Continue until a rule is found or the root is reached.
6. If no rule exists, deny.
7. If a rule exists but no subject matches, deny.

The system has grants only in v1. It does not support explicit deny rules.

Example:

```text
engineering
  CONFIG_ITEM_READ → authenticated

engineering/ai
  CONFIG_ITEM_CREATE → ai-config-editors

engineering/ai/hive/production
  CONFIG_ITEM_CREATE → production-config-editors
  CONFIG_ITEM_DELETE → production-config-admins

engineering/ai/hive/production/service-a
  CONFIG_ITEM_READ → service-a-runtime
```

For `service-a`:

- read resolves locally to `service-a-runtime`;
- create resolves at `production`;
- delete resolves at `production`;
- actions with no rule anywhere are denied.

### 9.4 Authorization service

```kotlin
data class AuthorizationRequest(
    val principal: ApplicationPrincipal?,
    val action: Action,
    val resource: ResourceRef
)

data class PolicyDecision(
    val allowed: Boolean,
    val policySource: ResourceRef?,
    val matchedSubject: SubjectSelector?,
    val reason: String
)

interface PolicyAuthorizationService {
    fun authorize(request: AuthorizationRequest): PolicyDecision
}
```

Illustrative implementation:

```kotlin
@Service
class HierarchicalPolicyAuthorizationService(
    private val policyRepository: PolicyRepository,
    private val hierarchyRepository: HierarchyRepository,
    private val subjectMatcher: SubjectMatcher
) : PolicyAuthorizationService {

    override fun authorize(request: AuthorizationRequest): PolicyDecision {
        val resources = hierarchyRepository.selfAndAncestors(request.resource)

        for (resource in resources) {
            val rule = policyRepository.findRule(resource, request.action)
                ?: continue

            val matched = rule.subjects.firstOrNull {
                subjectMatcher.matches(request.principal, it)
            }

            return if (matched != null) {
                PolicyDecision(
                    allowed = true,
                    policySource = resource,
                    matchedSubject = matched,
                    reason = "Matched effective rule"
                )
            } else {
                PolicyDecision(
                    allowed = false,
                    policySource = resource,
                    matchedSubject = null,
                    reason = "Effective rule does not match caller"
                )
            }
        }

        return PolicyDecision(
            allowed = false,
            policySource = null,
            matchedSubject = null,
            reason = "No applicable grant"
        )
    }
}
```

### 9.5 Explainability

Every decision should be explainable to authorized administrators:

```json
{
  "allowed": true,
  "action": "CONFIG_ITEM_READ",
  "resourceId": "cfg_01J8ZX...",
  "policySource": {
    "type": "NAMESPACE",
    "id": "ns_01J8ZY..."
  },
  "matchedSubject": {
    "type": "GROUP",
    "provider": "ENTRA",
    "externalId": "..."
  }
}
```

Do not expose internal policy details to unauthorized consumers.

---

## 10. Spring Security integration

Spring Security should provide:

- interactive OIDC login;
- bearer-token validation;
- LDAP authentication where needed;
- session management;
- CSRF protection for browser workflows;
- request-level coarse authorization;
- method-level authorization hooks;
- authenticated identity through `SecurityContext`.

The domain layer should provide resource authorization.

### 10.1 Security configuration

```kotlin
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtConverter: Converter<Jwt, AbstractAuthenticationToken>
    ): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/actuator/health/**", permitAll)
                authorize("/api/v1/configsets/*/files/**", permitAll)
                authorize("/api/v1/configsets/*/bundle", permitAll)
                authorize("/api/v1/admin/**", authenticated)
                authorize("/ui/**", authenticated)
                authorize(anyRequest, authenticated)
            }

            oauth2Login { }

            oauth2ResourceServer {
                jwt {
                    jwtAuthenticationConverter = jwtConverter
                }
            }
        }

        return http.build()
    }
}
```

Consumption routes are permitted at the HTTP layer because a ConfigSet can be public. The application service still authorizes every request against the target resource.

### 10.2 Normalized principal

```kotlin
data class ApplicationPrincipal(
    val subjectId: String,
    val displayName: String?,
    val provider: IdentityProvider,
    val groups: Set<String>,
    val roles: Set<String>,
    val applicationId: String?
)
```

JWT, OIDC-session, LDAP, and workload identities should be converted into this representation before domain evaluation.

### 10.3 Method security

```kotlin
@Service
class NamespaceApplicationService(
    private val namespaceRepository: NamespaceRepository
) {

    @PreAuthorize(
        "@policyGuard.allowed(authentication, " +
            "T(com.example.security.Action).NAMESPACE_CREATE, #parentId)"
    )
    fun createNamespace(
        parentId: NamespaceId,
        command: CreateNamespaceCommand
    ): Namespace {
        return namespaceRepository.create(parentId, command)
    }
}
```

For content reads, explicit authorization in the service is often clearer than large SpEL expressions:

```kotlin
@Service
class ConfigConsumptionService(
    private val principalMapper: PrincipalMapper,
    private val authorization: PolicyAuthorizationService,
    private val contentProviderRegistry: ContentProviderRegistry
) {

    fun readFile(
        authentication: Authentication?,
        configSetId: ConfigSetId,
        path: String,
        revision: RevisionRef
    ): ConfigContent {
        val principal = principalMapper.from(authentication)

        authorization.authorize(
            AuthorizationRequest(
                principal = principal,
                action = Action.CONFIG_ITEM_READ,
                resource = ResourceRef.ConfigSetRef(configSetId)
            )
        ).requireAllowed()

        return contentProviderRegistry.forConfigSet(configSetId)
            .read(configSetId, path, revision)
    }
}
```

---

## 11. Authentication flows

### 11.1 Browser onboarding and administration

Preferred flow:

```text
Browser
  → OIDC authorization-code flow
  → Spring Security OAuth2 login
  → server-side session
  → administration services
```

The onboarding user must be authorized by the platform policy to create a ConfigSet beneath the selected namespace. Repository access is separately verified using the source credential selected during onboarding.

### 11.2 CLI and interactive scripts

```text
CLI
  → device authorization flow with identity provider
  → access token
  → consumption API
  → Spring Resource Server validation
  → domain-policy evaluation
```

Device flow affects token acquisition by the client. The API processes the resulting bearer token like any other access token.

### 11.3 Workloads and unattended scripts

```text
Workload
  → workload identity or client-credentials token
  → consumption API
  → application-subject policy evaluation
```

Application identities are first-class policy subjects.

### 11.4 Direct LDAP deployment

```text
Browser
  → Spring form login
  → LDAP bind authentication
  → group lookup
  → server-side session
  → administration services
```

Direct LDAP does not provide OAuth device flow. For CLI use in an LDAP-only environment, the preferred architecture is an OIDC/OAuth authorization server backed by LDAP. Collecting enterprise LDAP passwords in a CLI should not be the default design.

---

## 12. Source abstraction

The source abstraction should expose capabilities explicitly rather than assuming every source behaves like Git.

```kotlin
interface ConfigContentProvider {
    fun capabilities(): SourceCapabilities

    fun list(
        configSetId: ConfigSetId,
        revision: RevisionRef
    ): ConfigBundle

    fun read(
        configSetId: ConfigSetId,
        path: String,
        revision: RevisionRef
    ): ConfigContent
}

data class SourceCapabilities(
    val supportsImmutableRevisions: Boolean,
    val supportsMutation: Boolean,
    val supportsExplicitSync: Boolean,
    val supportsViews: Boolean
)
```

A separate mutation interface prevents read-only Git sources from implementing meaningless methods:

```kotlin
interface MutableConfigContentProvider : ConfigContentProvider {
    fun write(command: WriteConfigItemCommand): RevisionId
    fun delete(command: DeleteConfigItemCommand): RevisionId
}
```

### 12.1 Source definitions

```kotlin
sealed interface SourceDefinition {
    val id: SourceId
}

data class GitSourceDefinition(
    override val id: SourceId,
    val repositoryUri: URI,
    val configuredRef: String,
    val rootPath: String,
    val credentialRef: CredentialRef,
    val transport: GitTransport
) : SourceDefinition

data class DatabaseSourceDefinition(
    override val id: SourceId
) : SourceDefinition

enum class GitTransport {
    SSH,
    HTTPS
}
```

Only Git/SSH is implemented in v1. The type model leaves room for HTTPS and database sources.

### 12.2 One source per ConfigSet

A ConfigSet maps to exactly one source in v1.

Future composition should be implemented above the source layer, for example:

```text
Composite publication
├── ConfigSet A
├── ConfigSet B
└── rendering/composition policy
```

Do not make `ConfigSet.sourceIds` plural preemptively.

---

## 13. Git integration

### 13.1 Responsibility split

Separate:

1. **Git transport**, used to fetch repository content.
2. **Credentials**, used by the transport.
3. **Change detection**, used to decide whether synchronization is needed.
4. **Sync triggers**, used to request synchronization.
5. **Provider integration**, used only for vendor-specific discovery, installation, and administration.

```text
Git source
├── Transport
│   ├── SSH, v1
│   └── HTTPS, future
├── Change detector
│   ├── Fetch-based polling, v1
│   ├── ls-remote optimization, future
│   └── provider API, future
├── Sync trigger
│   ├── Polling, v1
│   ├── Manual
│   └── Webhook, future
└── Provider integration, future
    ├── GitHub
    ├── GitLab
    └── Other providers
```

### 13.2 SSH onboarding

Suggested onboarding flow:

1. User selects the parent namespace.
2. Platform authorizes `CONFIG_SET_CREATE`.
3. User enters repository SSH URI, configured ref, and root path.
4. User selects a credential associated with the Git-service instance.
5. Platform validates host-key configuration.
6. Platform attempts `ls-remote` or clone/fetch with the credential.
7. Platform verifies that the configured ref and root path exist.
8. Platform previews the ConfigSet and effective policy.
9. Platform persists metadata and schedules initial synchronization.

The long-running synchronization identity should be a non-human service identity rather than the onboarding user's identity.

### 13.3 Credential scope

A practical default is one credential per bot identity per Git-service instance:

```text
github.example.com        → credential A
gitlab.example.com        → credential B
gitlab-region.example.com → credential C
```

The service identity must be granted repository or group access within the Git provider. Entra ID may authenticate human users to the provider, but repository authorization remains a Git-provider concern.

Where broad instance-level credentials create excessive reach, support a narrower credential per repository or repository group. Credential scope should be a deployment and risk decision, not hard-coded into the domain model.

### 13.4 Host-key verification

Never disable SSH host-key verification.

Maintain trusted host keys or certificates per Git-service instance. Onboarding should fail if the presented host key does not match the trusted configuration.

### 13.5 Local repository cache

Maintain a local mirror or bare clone per source:

```text
Remote Git service
  → local bare repository or mirror
  → revision and path reads
```

Benefits:

- incremental fetches;
- fast repeated reads;
- revision access without re-downloading;
- reduced load on the remote Git service.

The local cache is disposable. PostgreSQL contains authoritative metadata; remote Git contains authoritative configuration content.

---

## 14. Synchronization architecture

### 14.1 Trigger abstraction

```kotlin
interface SyncTrigger {
    fun request(configSetId: ConfigSetId, reason: SyncReason)
}

enum class SyncReason {
    POLL,
    MANUAL,
    WEBHOOK,
    RECONCILIATION
}
```

All triggers produce the same sync request. They do not retrieve content themselves.

### 14.2 v1 polling

Start with fetch-based polling:

```text
Scheduler
  → acquire per-source sync lease
  → git fetch
  → resolve configured ref
  → compare remote revision with last seen revision
  → validate and index changes when different
  → update sync state
```

Git fetch already transfers missing objects rather than downloading the entire repository again.

If polling scale requires optimization, add `git ls-remote` before fetch:

```text
ls-remote
  → remote revision unchanged: stop
  → remote revision changed: fetch
```

This optimization should remain internal to the Git change detector.

### 14.3 Webhooks later

A webhook is a notification, not content transport:

```text
Push to remote Git
  → webhook event
  → SyncRequested
  → normal Git fetch pipeline
```

Even with webhooks, periodic reconciliation remains useful to recover from missing or deleted webhook deliveries.

### 14.4 Sync state

Track at least:

```text
last_seen_revision
last_synced_revision
last_success_at
last_attempt_at
last_error_code
last_error_summary
consecutive_failure_count
```

Do not store sensitive transport output or credentials in sync-error messages.

---

## 15. Content and validation semantics

### 15.1 Raw content is authoritative

For Git-backed ConfigSets, remote Git is the source of truth. The platform should synchronize and serve raw content even when a known format is malformed.

Validation is metadata, not a prerequisite for storage or raw retrieval.

### 15.2 Validation status

```kotlin
enum class ValidationStatus {
    UNKNOWN,
    VALID,
    INVALID
}

data class ValidationIssue(
    val code: String,
    val message: String,
    val line: Int?,
    val column: Int?
)

data class ValidationSummary(
    val status: ValidationStatus,
    val format: ConfigFormat?,
    val issues: List<ValidationIssue>
)
```

### 15.3 Known formats

Initial or future validators can support:

```kotlin
enum class ConfigFormat {
    YAML,
    JSON,
    PROPERTIES,
    TOML,
    XML,
    TEXT,
    BINARY
}
```

Keep raw bytes authoritative. Parsing and reserialization can alter comments, ordering, aliases, formatting, or vendor extensions.

### 15.4 Validator extension point

```kotlin
interface ConfigValidator {
    fun supports(path: String, mediaType: String?): Boolean
    fun validate(bytes: ByteArray): ValidationSummary
}
```

### 15.5 Raw serving

Raw endpoints return content regardless of validation status, subject to authorization.

Useful headers include:

```text
Content-Type
Content-Length
ETag
Last-Modified
X-Config-Revision
X-Config-Validation-Status
```

A future rendered or computed view may fail when malformed content cannot be parsed. That does not change raw retrieval semantics.

---

## 16. Consumption API

### 16.1 Discovery

```http
GET /api/v1/configsets:resolve?path=engineering/ai/hive/production/service-a
```

Illustrative response:

```json
{
  "id": "cfg_01J8ZX...",
  "name": "service-a",
  "path": "engineering/ai/hive/production/service-a"
}
```

Discovery is normally a one-time or infrequent operation. Consumers persist the stable ConfigSet ID.

### 16.2 ConfigSet metadata

```http
GET /api/v1/configsets/{configSetId}
```

### 16.3 List bundle items

```http
GET /api/v1/configsets/{configSetId}/files
GET /api/v1/configsets/{configSetId}/files?revision={revisionId}
```

### 16.4 Read a raw file

```http
GET /api/v1/configsets/{configSetId}/files/{*path}
GET /api/v1/configsets/{configSetId}/files/{*path}?revision={revisionId}
```

The wildcard path is relative to the ConfigSet root. Normalize it and reject absolute paths and traversal segments.

### 16.5 Retrieve a bundle

Possible representations:

```http
GET /api/v1/configsets/{configSetId}/bundle
Accept: application/zip
```

or a manifest containing signed or authorized item URLs. ZIP is simpler for v1 if full-bundle download is required.

### 16.6 Revision retrieval

```http
GET /api/v1/configsets/{configSetId}/revisions
GET /api/v1/configsets/{configSetId}/revisions/{revisionId}
```

Expose immutable platform revision IDs. Do not require consumers to understand branch or tag syntax.

### 16.7 HTTP caching

Support:

- ETags derived from immutable revision and item path;
- `If-None-Match` and `304 Not Modified`;
- immutable caching for exact revision reads;
- shorter cache policy for `latest` reads.

Runtime refresh remains a client decision in v1.

---

## 17. Optional views and rendering

Views are intentionally outside the v1 core.

```kotlin
@JvmInline
value class ConfigView(val name: String)

interface ConfigRenderer {
    fun render(
        bundle: ConfigBundle,
        view: ConfigView
    ): RenderedConfig
}
```

Potential views:

```text
default
npd
prod
staging
```

Potential rendering strategies:

- raw pass-through;
- base plus profile overlay;
- Spring-style profile rendering;
- custom organization convention;
- externally built/materialized publication.

### 17.1 v1 decision

V1 serves raw bundles and files. It does not define merging, overlay precedence, or repository conventions for profiles.

Teams may commit pre-built profile-specific files or directories, but those conventions are outside platform semantics.

### 17.2 Future profile convention

If profiles become a platform feature, define them as named views over a revision rather than embedding `profile` into the core source API:

```text
ConfigSet + Revision + View → Rendered output
```

The renderer, not the Git source, owns merge semantics.

---

## 18. Database source, future

A future database source should implement the same read contract.

Conceptual model:

```text
ConfigSet
└── ConfigItem
    └── ConfigItemVersion
```

Example tables:

```sql
create table config_item (
    id uuid primary key,
    config_set_id uuid not null references config_set(id),
    path varchar(1000) not null,
    media_type varchar(200),
    current_version_id uuid,
    unique (config_set_id, path)
);

create table config_item_version (
    id uuid primary key,
    config_item_id uuid not null references config_item(id),
    version_number bigint not null,
    content bytea not null,
    content_hash varchar(128) not null,
    created_at timestamptz not null,
    created_by varchar(255) not null,
    unique (config_item_id, version_number)
);
```

A database source may implement `MutableConfigContentProvider`, allowing editing through the platform in a later release.

Git-backed sources remain read-only through the platform. Git is their source of truth.

---

## 19. Persistence design

PostgreSQL stores topology, policies, source definitions, sync state, non-secret credential metadata, validation metadata, and audit events.

### 19.1 Namespace

```sql
create table namespace (
    id uuid primary key,
    parent_id uuid references namespace(id),
    name varchar(200) not null,
    slug varchar(100) not null,
    version bigint not null,
    created_at timestamptz not null,
    created_by varchar(255) not null,
    updated_at timestamptz not null,
    updated_by varchar(255) not null,
    unique (parent_id, slug)
);
```

Root-level uniqueness requires special handling because SQL nulls do not compare as equal in a standard unique constraint. Use a partial index, a designated root parent, or a normalized parent key.

### 19.2 Namespace closure

```sql
create table namespace_closure (
    ancestor_id uuid not null references namespace(id),
    descendant_id uuid not null references namespace(id),
    depth integer not null check (depth >= 0),
    primary key (ancestor_id, descendant_id)
);
```

This supports efficient ancestor and descendant queries. Namespace moves update closure rows transactionally.

### 19.3 ConfigSet

```sql
create table config_set (
    id uuid primary key,
    namespace_id uuid not null references namespace(id),
    name varchar(200) not null,
    slug varchar(100) not null,
    source_id uuid not null,
    policy_mode varchar(20) not null,
    version bigint not null,
    created_at timestamptz not null,
    created_by varchar(255) not null,
    updated_at timestamptz not null,
    updated_by varchar(255) not null,
    unique (namespace_id, slug)
);
```

### 19.4 Policy rules

```sql
create table policy_rule (
    id uuid primary key,
    namespace_id uuid references namespace(id),
    config_set_id uuid references config_set(id),
    action varchar(80) not null,
    version bigint not null,
    check (
        (namespace_id is not null and config_set_id is null) or
        (namespace_id is null and config_set_id is not null)
    )
);
```

Enforce one active rule per resource and action.

### 19.5 Policy subjects

```sql
create table policy_subject (
    policy_rule_id uuid not null references policy_rule(id) on delete cascade,
    subject_type varchar(40) not null,
    identity_provider varchar(40),
    external_id varchar(500),
    primary key (
        policy_rule_id,
        subject_type,
        identity_provider,
        external_id
    )
);
```

Public and authenticated subjects need a normalized representation because nullable columns complicate composite primary keys.

### 19.6 Git source

```sql
create table git_source (
    id uuid primary key,
    repository_uri varchar(2000) not null,
    configured_ref varchar(500) not null,
    root_path varchar(2000) not null,
    transport varchar(20) not null,
    credential_ref uuid not null,
    last_seen_revision varchar(500),
    last_synced_revision varchar(500),
    last_attempt_at timestamptz,
    last_success_at timestamptz,
    last_error_code varchar(100),
    last_error_summary varchar(1000),
    version bigint not null
);
```

### 19.7 Credential metadata

```sql
create table credential (
    id uuid primary key,
    type varchar(40) not null,
    git_service_instance varchar(500) not null,
    principal_name varchar(500),
    ciphertext bytea,
    encrypted_data_key bytea,
    external_secret_ref varchar(1000),
    key_version varchar(100),
    created_at timestamptz not null,
    rotated_at timestamptz,
    disabled_at timestamptz,
    version bigint not null
);
```

A deployment should use either externally referenced secrets or encrypted database storage. Plaintext credential material must never be persisted.

---

## 20. Credential protection

### 20.1 Preferred: envelope encryption

Envelope encryption separates a long-lived key-encryption key from randomly generated data-encryption keys.

```text
Vault/KMS-managed master key
  → protects a random data-encryption key
  → data-encryption key encrypts SSH private key
```

Persist:

- encrypted SSH private key;
- encrypted data key;
- algorithm and key-version metadata.

The master key remains outside the application database.

Advantages:

- centralized access policy;
- key-use auditing where supported;
- simpler master-key rotation;
- a unique random data key per credential or secret;
- reduced reuse of a single application encryption key.

### 20.2 Portable fallback: derivation from external master secret

A deployment without KMS or Vault may derive encryption keys from a high-entropy master secret stored outside the system.

Use a standard KDF such as PBKDF2 or HKDF according to the deployment's security requirements. Store a unique random salt and algorithm parameters alongside each ciphertext.

Conceptually:

```text
external high-entropy master secret
  + unique random salt
  + context information
  → KDF
  → per-credential encryption key
  → authenticated encryption of SSH private key
```

Requirements:

- use authenticated encryption such as AES-GCM;
- generate a unique nonce according to the cipher's requirements;
- never derive keys from repository URI, namespace, or other guessable metadata alone;
- version the KDF and encryption parameters;
- support re-encryption during rotation;
- keep the master secret outside the database and source repository;
- document restore and rotation procedures.

This design is deterministic only with respect to the same master secret, salt, and context. The random per-record salt ensures different credentials obtain different keys.

### 20.3 Plain deployment secrets

Database passwords and application deployment secrets should be supplied through normal deployment secret mechanisms, such as Kubernetes Secrets integrated with an external secret store, environment variables, or mounted secret files. The configuration platform is not a general-purpose secret manager.

---

## 21. Spring Boot 4 and Kotlin implementation guidance

### 21.1 Module structure

A pragmatic multi-module structure:

```text
config-platform
├── app
│   └── Spring Boot application and wiring
├── domain
│   └── pure Kotlin domain model and policies
├── application
│   └── use cases and transaction boundaries
├── api
│   └── HTTP controllers and DTOs
├── security
│   └── Spring Security adapters and principal mapping
├── persistence-jdbc
│   └── PostgreSQL repositories
├── source-git
│   └── Git source and polling implementation
├── source-db
│   └── future database source
└── client-kotlin
    └── optional generated or handwritten SDK
```

Do not split every package into a module prematurely. The important boundaries are domain, application, source adapters, persistence, and security.

### 21.2 Constructor injection

```kotlin
@Service
class ConfigSetApplicationService(
    private val configSetRepository: ConfigSetRepository,
    private val policyAuthorizationService: PolicyAuthorizationService,
    private val sourceRegistry: SourceRegistry
)
```

Avoid field injection.

### 21.3 Configuration properties

```kotlin
@ConfigurationProperties("config-platform.git")
data class GitPlatformProperties(
    val cacheDirectory: Path,
    val pollInterval: Duration,
    val maxConcurrentFetches: Int
)
```

Prefer immutable configuration properties and validation at startup.

### 21.4 Transactions

Place transaction boundaries on application services:

```kotlin
@Service
class MoveNamespaceUseCase(
    private val namespaceRepository: NamespaceRepository,
    private val closureRepository: NamespaceClosureRepository,
    private val policyPreviewService: PolicyPreviewService,
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional
    fun move(command: MoveNamespaceCommand): Namespace {
        val preview = policyPreviewService.previewMove(command)
        preview.requireSafeOrExplicitlyAcknowledged(command)

        val moved = namespaceRepository.move(command)
        closureRepository.rebuildForMovedSubtree(moved.id)
        eventPublisher.publishEvent(NamespaceMoved(moved.id))
        return moved
    }
}
```

### 21.5 Scheduling

```kotlin
@Component
class GitPollScheduler(
    private val pollCandidates: PollCandidateRepository,
    private val syncRequester: SyncRequester
) {

    @Scheduled(fixedDelayString = "\${config-platform.git.poll-interval}")
    fun poll() {
        pollCandidates.findDue().forEach {
            syncRequester.request(it.configSetId, SyncReason.POLL)
        }
    }
}
```

The scheduler should request work rather than executing a long Git fetch inline. Use bounded executors and per-source leases.

### 21.6 Events

Use application events for cache invalidation and audit enrichment, not as a replacement for durable workflow state.

```kotlin
data class PolicyChanged(val resource: ResourceRef)
data class NamespaceMoved(val namespaceId: NamespaceId)
data class SourceSynchronized(
    val configSetId: ConfigSetId,
    val revision: RevisionId
)
```

### 21.7 Persistence technology

Spring Data JDBC or jOOQ is a good fit for explicit relational modeling and recursive or closure-table queries. JPA can work, but deep object graphs should not drive the domain model.

---

## 22. Spring Cloud Config positioning

Spring Cloud Config is not the core platform model.

It is oriented around application, profile, label, and property-source concepts. This platform is oriented around:

```text
Namespace hierarchy
ConfigSet
ConfigItem
Revision
Inherited policy
Multiple source types
```

A future compatibility adapter may map:

```text
application → ConfigSet
profile     → optional view
label       → immutable revision or configured alias
```

The core system should not be constrained by Spring Cloud Config's property-oriented API or repository conventions.

---

## 23. Security considerations

### 23.1 Authorization

- Default deny.
- Enforce at the application-service boundary.
- Do not rely solely on URL matching.
- Invalidate effective-policy caches after policy changes and moves.
- Audit policy changes and sensitive reads.
- Preview inherited-policy changes before moves.

### 23.2 Git

- Verify SSH host keys.
- Use least-privilege read-only repository access in v1.
- Do not store onboarding-user credentials for continuous synchronization.
- Do not log private keys, tokens, or full command environments.
- Bound repository size, fetch duration, and concurrent fetch count.
- Normalize and constrain ConfigSet root paths.
- Reject path traversal during file retrieval.

### 23.3 Content

- Treat content as untrusted bytes.
- Limit file and bundle size.
- Stream large responses.
- Do not execute repository content.
- Run validators with bounded resources.
- Return safe media types and content-disposition headers where appropriate.

### 23.4 Credentials

- Encrypt credential material before persistence.
- Keep master key material outside the database.
- Version encryption metadata.
- Support rotation and disablement.
- Minimize credential blast radius based on deployment needs.
- Maintain an audit trail of credential use without exposing secret material.

---

## 24. Observability and audit

### 24.1 Operational metrics

Track:

- sync attempts, successes, failures, and duration;
- fetch bytes where available;
- polling lag;
- authentication failures;
- authorization allows and denies by action;
- policy-resolution latency;
- content-read latency and response size;
- validation results by format;
- cache hit rate;
- credential age and upcoming rotation state.

### 24.2 Audit events

Record at least:

- namespace create, rename, move, and delete;
- ConfigSet create, move, source update, and delete;
- policy create, update, and delete;
- credential create, rotate, disable, and use;
- manual synchronization requests;
- synchronization outcome;
- revision access for protected content when required by policy.

Audit records should include immutable resource IDs and the human-readable path at event time.

---

## 25. Implementation sequence

### Phase 1: Domain foundation

1. Namespace and ConfigSet IDs and entities.
2. Namespace adjacency and closure persistence.
3. Namespace create, rename, move, and delete rules.
4. ConfigSet registration and stable path discovery.
5. Policy actions, subjects, and persistence.
6. Effective-policy resolution.
7. Unit tests for inheritance and move behavior.

### Phase 2: Security

1. Spring Security baseline.
2. OIDC browser login.
3. OAuth2 resource-server JWT validation.
4. `ApplicationPrincipal` mapping.
5. Method-level policy guard.
6. Public, authenticated, group, user, and application subjects.
7. LDAP authentication adapter and group mapping where required.

### Phase 3: Git source

1. Source-neutral content interfaces.
2. Git source definition.
3. Credential metadata and secret-protection interface.
4. SSH transport and host-key verification.
5. Local bare-clone or mirror cache.
6. Initial clone and fetch.
7. ConfigSet root-path and file listing.
8. Latest and exact-revision reads.

### Phase 4: Synchronization

1. Sync-request model.
2. Poll scheduler.
3. Per-source leasing.
4. Fetch-based change detection.
5. Sync-state persistence.
6. Failure handling and metrics.
7. Optional `ls-remote` optimization when justified by measurements.

### Phase 5: Consumption API and clients

1. Path discovery API.
2. ConfigSet metadata API.
3. File listing and raw retrieval.
4. Bundle download.
5. Revision listing and exact-revision reads.
6. ETags and conditional GET.
7. CLI authentication and basic get/list commands.
8. SDK generation or a thin Kotlin SDK.

### Phase 6: Administration UI

1. Namespace navigation.
2. Policy editor and effective-policy preview.
3. Git ConfigSet onboarding.
4. Repository-access validation.
5. Sync status and manual sync.
6. Validation status and file browser.
7. Audit views.

### Later phases

- database source and in-platform editing;
- webhook triggers plus polling reconciliation;
- HTTPS Git transport;
- provider-native onboarding;
- optional views and rendering;
- Spring Cloud Config compatibility;
- push delivery;
- composition above ConfigSets.

---

## 26. Testing strategy

### 26.1 Domain tests

Test:

- nearest-rule inheritance;
- absent-rule denial;
- mismatched-subject denial;
- public and authenticated rules;
- namespace moves changing inherited policy;
- path reuse after rename;
- sibling-slug uniqueness;
- ConfigSet ID stability.

### 26.2 Security integration tests

Test:

- anonymous access to public ConfigSets;
- denial of anonymous access to protected ConfigSets;
- JWT role and group mapping;
- application identity matching;
- LDAP group mapping;
- method-security enforcement;
- CSRF behavior for UI mutations.

### 26.3 Git integration tests

Use disposable SSH Git servers or containers to test:

- initial clone;
- incremental fetch;
- host-key mismatch;
- invalid credentials;
- missing ref;
- missing root path;
- exact revision read;
- deleted and renamed files;
- malformed YAML and JSON synchronization;
- concurrent sync suppression.

### 26.4 Contract tests

Every content provider should pass a shared contract:

```text
list latest
read latest
list exact revision
read exact revision
missing item behavior
immutable revision behavior
capability reporting
```

This makes adding the future database source safer.

---

## 27. Architecture decisions

### ADR-001: Hierarchical namespaces

Use nested Namespaces containing child Namespaces and ConfigSets.

### ADR-002: Stable IDs for consumers

Paths support discovery and UI navigation. Consumers use stable ConfigSet IDs for durable retrieval.

### ADR-003: ConfigSet as bundle boundary

A ConfigSet represents a bundle or tree of ConfigItems, not a single file.

### ADR-004: Individual file retrieval

Consumers may list a bundle, retrieve the entire bundle, or retrieve an individual ConfigItem.

### ADR-005: Action-based authorization

Persist permissions as actions. Role templates may map to actions.

### ADR-006: Nearest-rule inheritance

For each action, the nearest rule in the ConfigSet-to-root chain is authoritative.

### ADR-007: Grants only in v1

No explicit deny rules or general policy expressions.

### ADR-008: Spring Security plus domain authorization

Spring Security authenticates and supplies authorization hooks. Domain services resolve hierarchical resource policy.

### ADR-009: Git over SSH in v1

Remote Git over SSH is the initial source integration.

### ADR-010: Polling in v1

Use Git fetch-based polling first. Preserve sync-trigger and change-detector extension points.

### ADR-011: Git remains source of truth

Git-backed content is read-only through the platform in v1.

### ADR-012: One source per ConfigSet

A ConfigSet maps to exactly one source. Future composition occurs above ConfigSets.

### ADR-013: Raw content remains retrievable

Malformed known formats are synchronized and served as raw content. Validation status is metadata.

### ADR-014: Source-neutral revisions

Expose `latest` and immutable platform revision IDs. Do not expose branches and tags as general API concepts.

### ADR-015: Pull delivery in v1

Clients pull configuration. Preserve an event model that can support push delivery later.

### ADR-016: Views deferred

Do not define profile merging or overlay semantics in v1. Preserve a renderer extension point.

### ADR-017: Credential encryption

Prefer envelope encryption backed by external key management. Permit a documented KDF-based fallback using an external high-entropy master secret.

---

## 28. Deferred decisions

The following should be decided only when their implementation phase begins:

- exact admin UI technology;
- Spring Data JDBC versus jOOQ;
- ULID versus UUID identifiers;
- ZIP versus manifest representation for bundle download;
- policy-decision cache implementation;
- local Git cache eviction strategy;
- exact encrypted-secret provider;
- provider-specific onboarding sequence;
- profile/view merge semantics;
- push-delivery protocol;
- composite-publication model.

---

## 29. Definition of v1

V1 is complete when:

- administrators can sign in through OIDC, with optional LDAP deployment support;
- administrators can create and manage nested namespaces;
- administrators can assign inheritable action policies;
- administrators can onboard a Git/SSH-backed ConfigSet;
- the platform can protect and use an SSH credential;
- the platform polls and synchronizes remote Git changes;
- consumers can authenticate with bearer tokens or access public ConfigSets anonymously where allowed;
- consumers can discover a ConfigSet by path and persist its stable ID;
- consumers can list files, download a bundle, and retrieve individual raw files;
- consumers can request latest or an immutable revision;
- malformed known formats are exposed with validation metadata rather than silently rejected;
- policy, authentication, content, synchronization, and audit behavior are observable and tested.

