# Folio design

Status: Proposed, 2026-10-10.

This document replaces the earlier design, the v1 scope, the plan and ADRs 0001 to 0041. The branch
`archive/pre-redesign` keeps them. This document includes the decisions from them that still apply.

This document is the single source of truth for Folio. It tells what Folio is, what v1 builds and why. The readers
are the people who maintain Folio and the agents who help them. If the code and this document do not agree, one of
them is wrong. Correct the code, or change this document in the same pull request.

---

## 1. What Folio is

Folio serves configuration files to applications, scripts, CLIs and agents. Teams keep their configuration in Git.
Folio does these tasks:

- It puts the configuration in a tree of namespaces.
- It controls who can read and who can administer the configuration.
- It keeps a synchronized copy.
- It serves files over HTTP at the latest revision or at an earlier published revision.

```text
Namespace            engineering
└── Namespace        └── search
    └── ConfigSet        └── service-a          ← what consumers read
        └── ConfigItem       ├── application.yaml
                             └── kafka/client.properties
Revision: a snapshot of the full ConfigSet, for example Git commit 3f9c…
```

A consumer resolves a path such as `engineering/search/service-a` one time. It keeps the stable ID of the ConfigSet.
After that, it reads files by ID.

---

## 2. Glossary

Use these terms in code, APIs, logs and reviews. Do not use synonyms for them.

### 2.1 Domain terms

| Term | Meaning |
|---|---|
| **Namespace** | A folder in the tree. It contains child Namespaces and ConfigSets. Most rules are set on Namespaces. |
| **ConfigSet** | A bundle of configuration files from one Source. Consumers read ConfigSets. Folio authorizes, versions and syncs each ConfigSet as one unit. A ConfigSet is always in a Namespace. |
| **ConfigItem** | One file in a ConfigSet. Its path relative to the ConfigSet root identifies it, for example `kafka/client.properties`. |
| **Revision** | An immutable snapshot of a full ConfigSet. ConfigItems do not have their own versions. |
| **Published revision** | A Revision that the Source made available to consumers. For Git, this is a commit that sync recorded. Consumers can read only published revisions. |
| **latest** | The newest published revision. |
| **Source** | The origin of the content of a ConfigSet. v1 has Git. A database Source is next. |
| **Catalog** | The tree of Namespaces and ConfigSets, with their rules. Also the name of the module that owns the tree. |
| **Path** | Slugs from the root, joined by `/`. Paths are for people and for discovery. A rename or a move changes a path. It does not change an ID. |
| **Slug** | The name of a Namespace or ConfigSet in its parent. It has 1 to 100 characters: lowercase letters, digits and single hyphens between them. |
| **Directory** | The origin of the groups of a caller: the token, or the Google Cloud Identity API (section 6.3). |
| **Caller** | The identity that makes a request. A caller is anonymous, or it is an authenticated subject with groups, an optional application ID and a super-admin status. |
| **Subject** | The identity that a rule names: `public`, `authenticated`, `user:<id>`, `group:<id>` or `application:<id>`. The ID of a subject has 1 to 500 characters and no control characters. It is exact: case and spaces count. |
| **Action** | The operation that a rule permits: `READ`, `MANAGE` or `GRANT` (section 7). |
| **Rule** | A grant of one action on one Namespace or ConfigSet to a set of one or more subjects. |
| **Super admin** | A caller that a subject in the deployment configuration names. A super admin can do all actions on all resources. Rules do not change this. |
| **Git instance** | A Git server that Folio can connect to. The deployment configuration gives its host, port, SSH user and trusted host keys. |
| **Credential** | The SSH identity that Folio uses to read repositories on one Git instance. A Namespace owns each credential. A credential contains key pairs. |
| **Key** | An SSH key pair of a credential. v1 generates only Ed25519 keys (section 10.4). Its status is `PENDING`, `ACTIVE` or `RETIRED`. |

### 2.2 Architecture terms

These terms come from John Ousterhout, *A Philosophy of Software Design*, and from the `codebase-design` skill of
Matt Pocock.

| Term | Meaning |
|---|---|
| **Module** | A unit with an interface and an implementation. In this document, "module" means a Spring Modulith application module (section 8). The design rules also apply to classes and functions in a module. |
| **Interface** | All that a caller must know to use a module: signatures, invariants, errors, order of calls and configuration. |
| **Depth** | The quantity of behaviour that a caller gets for each item of the interface that it must learn. A deep module is good. |
| **Seam** | A location where behaviour can change without a change to the caller. An interface is at a seam. |
| **Adapter** | An implementation that fills a seam. |
| **Locality** | A change to one concept changes one module only. |

---

## 3. Design principles

1. **Make modules deep.** A module gives its callers much behaviour through a small interface. Measure depth by what
   the callers get, not by the size of the code behind the interface. If a caller must call several parts in the
   correct order, move that order into the module.
2. **Prefer locality to layers.** Organize code by domain concept, not by technical layer. A module owns its HTTP
   routes, its logic, its SQL and its tables.
3. **Add a seam only where behaviour changes.** Add an interface when two adapters exist. Also add one when a change
   is known and near. The Source interface is the only seam that v1 adds for the future, because database Sources
   come next. The directory interface (section 6.3) is a seam because v1 has two adapters. Other possible changes do not get a seam now. Section 18 tells where each change goes.
4. **Remove errors by design where possible.** Folio serves raw content also when the content does not parse. A
   delete of a rule that does not exist succeeds. Keep the remaining errors few and specific.
5. **Use one method for each task.** Folio has one error style, one way to pass the caller, one tree lock and one way
   to write audit records.
6. **Give the reason.** Each decision in this document has its reason. Code comments give intent, invariants and
   surprises. They do not repeat the code.
7. **Test through interfaces.** A test calls a module in the same way as its callers. If a test must change when the
   implementation changes, the test examines too much of the implementation.

---

## 4. Scope

### 4.1 Items that v1 builds

- **Catalog.** Administrators create, describe, rename, move and delete Namespaces and ConfigSets. Consumers find a
  ConfigSet by its path.
- **Authorization.**
  - A rule gives one action (`READ`, `MANAGE` or `GRANT`) on one resource to one or more subjects.
  - A resource without a rule for an action uses the rule of its nearest ancestor.
  - Super admins can do all actions.
  - An administrator can ask Folio which rule permitted or denied an action.
- **Authentication.** Folio accepts JWT bearer tokens from one identity provider in each deployment. Section 6 gives
  the setup for Entra ID and for Google Workspace.
- **Source interface.** v1 has a Git adapter for production and an in-memory adapter for tests.
- **Git over SSH.**
  - The deployment configuration lists the Git servers and their SSH host keys.
  - An administrator connects a ConfigSet to a repository, and checks the connection.
  - Folio polls each repository for new commits. A lease makes sure that only one instance syncs a ConfigSet at a
    time.
  - Each instance keeps a local copy of the repositories. Folio can delete and rebuild this copy at any time.
  - If a read needs a commit that the local copy does not have, Folio fetches the commit.
- **Credentials.**
  - A Namespace owns each credential.
  - Folio generates the SSH keys. Private keys never leave Folio.
  - Administrators rotate keys in two steps, replace keys in an emergency, disable credentials and delete unused
    credentials.
  - Folio encrypts private keys with a versioned master key. Folio can encrypt them again with a new master key.
- **Consumption API.** Consumers find a ConfigSet by path, read its metadata, list its revisions and files, and
  read files as raw bytes. Folio streams large files and sends HTTP cache headers.
- **Validation.** Folio reports if a YAML, JSON, properties, TOML or INI file parses. Folio serves the file in all
  cases.
- **Web UI.** People sign in with a browser. Users browse the ConfigSets that they can read, and view revisions and
  files. Administrators do all administration tasks of the API. Section 12.3 gives the details.
- **Operations.** Audit records with a retention period, metrics for Prometheus and OpenTelemetry, and health
  checks.

### 4.2 Items that v1 does not build

- Edits of content in the web UI. They come with database Sources.
- LDAP login.
- The `folio` CLI and SDKs.
- More than one identity provider in one deployment.
- HTTPS Git, webhooks, `ls-remote` polling and provider apps such as GitHub Apps.
- Import of private keys. KMS or Vault.
- Bundle downloads.
- Database Sources and edits of content through Folio.
- Views, profiles, rendering and composition.
- Push delivery and compatibility with Spring Cloud Config.
- Deny rules, policy expressions and authorization for each file.
- A cache for authorization decisions.
- An API to read audit records.

### 4.3 Next item

**Database ConfigSets**, with edits through Folio. v1 designs the Source interface (section 9) and the Revision model
for this item. The database Source adds an adapter and an interface for edits. Consumers do not change.

---

## 5. Resource model

### 5.1 Tree

- Namespaces can contain Namespaces to any depth. The parent of a Namespace is another Namespace or the root.
- A ConfigSet is always in a Namespace. A ConfigSet is never at the root.
- The root is not a resource and has no rules. Thus only super admins can act at the root. For example, only super
  admins can create top-level Namespaces, move a Namespace to the top level and manage master keys.
- Each Namespace and each ConfigSet has a slug and an optional description of 1,000 characters or fewer. A blank
  description is no description. Thus an empty form field clears it.
- Folio counts the characters of a description as Unicode code points, as `char_length` in SQL does. It stores CRLF
  line breaks as LF, because browsers send form text with CRLF. Thus the UI and the API store the same text. A
  description cannot contain NUL, because PostgreSQL text rejects it.

### 5.2 Identifiers

- Each Namespace, ConfigSet, credential and key has one ID. The database, the modules and the API use the same
  UUID. Only its form changes.
- PostgreSQL makes each ID with the function `uuidv7()`, as a column default. These UUIDs are in time order and have
  74 random bits. An insert gives the ID back with `RETURNING id`.
- The credential module must know a key ID before the insert, because the encryption uses it (section 11.2). It gets
  the ID first with `select uuidv7()`. Thus one function makes all IDs.
- An API ID is a type prefix and the UUID as 32 lowercase hex characters: `ns_…`, `cfg_…`, `cred_…` or `key_…`. Folio
  rejects other forms, for example uppercase characters or hyphens, with status 400.
- In modules, IDs are Kotlin value classes, for example `NamespaceId` and `ConfigSetId`. Thus the compiler stops a
  mix of ID types. Kotlin requires `@JvmInline` on value classes for the JVM. HTTP and SQL use plain strings and UUIDs.
  The HTTP layer converts them.

*Reason for prefixes:* a person can see the resource type from the ID alone, for example in an explanation or in an
audit record.

### 5.3 Paths and slugs

- The path of a Namespace is its slugs from the root, for example `engineering/search`.
- The path of a ConfigSet is the path of its Namespace and its own slug, for example `engineering/search/service-a`.
- When Folio resolves a path, the last segment is always the ConfigSet. Thus a Namespace and a ConfigSet can have the
  same slug in the same parent.
- Slugs are unique among sibling Namespaces, also at the top level. Slugs are unique among the ConfigSets of one
  Namespace.
- A slug can change. A rename changes the slug in the row and keeps the ID. The unique constraint examines only the
  new slug. If a sibling already has it, Folio gives status 409. The old slug is free immediately.
- A path has no slash at the start or at the end, and no empty segments. A ConfigSet path has two or more segments.
- A rename or a move changes paths. It never changes IDs. An old path stops resolving immediately, and a new resource
  can use it. Thus consumers must keep the ID, not the path.

### 5.4 Delete

- Folio deletes a Namespace only when it is empty. Empty means that the Namespace contains no child Namespaces, no
  ConfigSets and no credentials. A Namespace owns its credentials (section 11.1), so Folio finds them with a query on
  the `credential` table. ConfigItems are not a separate condition, because they are always in a ConfigSet.
  - The catalog reads the `credential` table only in this SQL query. Thus the catalog does not depend on the
    `credential` module, and the dependency arrow of section 8 stays as it is.
  - A credential create takes only the shared form of the tree lock, when `Namespaces.require` finds and authorizes
    the Namespace (section 8.1). Thus a create and a delete of the Namespace do not overlap. The foreign key from
    `credential` to `namespace` still guards the delete: if a credential appears after the query, the foreign key
    stops the delete, and the catalog changes that violation to `NAMESPACE_NOT_EMPTY`. A create whose insert meets a
    deleted Namespace gets `NAMESPACE_NOT_FOUND` from the same foreign key.
- Folio does not cascade a delete. *Reason:* a cascade can delete ConfigSets that consumers use.
- A delete of a ConfigSet also deletes its Source definition, its rules and its revision history. After the delete,
  consumers get status 404.

### 5.5 Revisions

- A Revision belongs to a ConfigSet. A Revision never belongs to one ConfigItem.
- For consumers, a revision ID is an opaque string. For Git, it is the commit ID as 40 lowercase hex characters.
- `latest` is the newest published revision. If a ConfigSet has no published revision, Folio gives status 404 with
  the message "not published yet".
- A read of an exact revision is permitted only for a revision that the ConfigSet published. For Git, this excludes
  these commits:
  - Commits that a fetch got but that sync did not record.
  - Commits on other branches.
- A published revision does not change. Its list of files and their content stay the same. This is true because the
  Source of a ConfigSet does not change after creation (section 10.2).

---

## 6. Identity

### 6.1 Tokens

OAuth has three roles. Folio has one of them:

- The **identity provider** (the issuer) signs people and workloads in, and issues tokens. Examples are Entra ID and
  Google.
- A **client** gets a token from the identity provider and sends it with each request. Examples are the CLI, a
  service and a CI job.
- The **resource server** accepts tokens and serves the protected data. Folio is the resource server for
  configuration. Spring Security calls these settings `resourceserver`.

For the API, Folio never signs a person in, and never sees a password. It validates the token that a client sends:
the signature, the issuer, the audience and the expiry time. Only the web UI signs people in (section 6.2).

Each API request is anonymous or has a JWT bearer token.

- Folio trusts one issuer: `spring.security.oauth2.resourceserver.jwt.issuer-uri`. Folio gets the signing keys of
  the issuer from its OIDC discovery document.
- The audience is mandatory: `spring.security.oauth2.resourceserver.jwt.audiences`. Without it, Folio does not start.
  Folio rejects a token that the issuer signed for a different API.
- The names of the claims are in the configuration under `folio.security.claims`:
  - `subject`, default `sub`.
  - `groups`, default `groups`.
  - `application-id`, default `azp`.
- Most providers need only configuration. Google also needs its directory adapter (section 6.3).
- A token must be valid. Folio gives status 401 for a token that is not valid. Folio never treats it as anonymous.

Folio converts the request to a `Caller`:

```kotlin
sealed interface Caller {
    /** True if an entry of folio.super-admins names the caller. Identity sets it. */
    val superAdmin: Boolean

    data object Anonymous : Caller            // superAdmin is false

    data class Authenticated(
        val subject: String,
        val groups: Set<String>,
        val applicationId: String?,
        override val superAdmin: Boolean = false,
    ) : Caller

    /** Background work such as sync. It matches no rule. Audit records show it as SYSTEM. */
    data object System : Caller               // superAdmin is false
}
```

Services get the caller as an explicit parameter. *Reason:* background work and tests then need no security context.
Also, the signature shows which operations act for a caller.

Identity decides the super-admin status when it makes the `Caller`, for bearer tokens and for sign-in alike (section
7.2). It uses `SuperAdmins.caller(subject, groups, applicationId)`. Other modules read `caller.superAdmin`. Only an
explanation calls `SuperAdmins.caller`, for the caller that a request describes (section 7.4). *Reason:* the audit record of each module and the master-key operations need the
status. Only `identity` can compute it for all of them, because the other modules depend on it.

### 6.2 Bearer tokens and the different flows

Folio examines one thing: a token. The method to get the token depends on the type of principal. It does not depend
on the type of client.

| Principal | Typical client | How the client gets a token | Subject in rules |
|---|---|---|---|
| A person | The CLI, a script that a person starts, an agent that acts for a person | The OAuth device flow. It needs no browser redirect. | `user:` or `group:` |
| A workload | A service, a CI job, a scheduled script, an autonomous agent | Client credentials, or a workload identity such as a managed identity or a federated credential | `application:` |

The flows are different for these reasons:

- A person needs multi-factor authentication and conditional access. Audit records show the person.
- A workload has no person to prompt. Audit records show the workload.

A future SDK will accept a token provider. It will send the token that its host got. Thus the API needs no sessions,
no CSRF protection and no passwords.

**Browsers.** A person who uses the web UI (section 12.3) signs in with the authorization code flow and PKCE. For the
UI, Folio is also an OAuth client:

- Folio redirects the person to the identity provider, and gets an ID token back.
- Folio makes the `Caller` from the ID token, with the same claim names and the same directory as for bearer tokens.
- Folio keeps the `Caller` in a session. Spring Session stores the sessions in PostgreSQL, so each instance can serve
  each session.
- The UI routes use a session cookie and CSRF protection. The API routes under `/api` do not accept the session
  cookie. They accept only bearer tokens.
- A session ends after `folio.ui.session-timeout` (default 1 hour) without use.
- The session keeps the `Caller` from sign-in, with its groups and its super-admin status. Thus a change of the groups
  in a token or of `folio.super-admins` takes effect at the next sign-in. *Reason:* the same rule applies to both, and
  a request needs no lookup.
- The UI client uses the one trusted issuer of section 6.1. Folio does not start if the issuer of the UI client is
  different.
- Folio keeps only the `Caller`. It does not keep the access or refresh tokens of the identity provider.
- Deployments put Folio behind a proxy that ends TLS and sets `X-Forwarded-Proto` and `X-Forwarded-Host`. Folio
  trusts these headers only from internal addresses. The session cookie is `Secure`.

### 6.3 Groups and directories

A **directory** gives the groups of a caller. Rules name groups, so each provider needs a directory. The `identity`
module has one directory interface and two adapters. The deployment selects one with `folio.security.directory`.

| Directory | Where the groups come from | Use it for |
|---|---|---|
| `token` (default) | The groups claim of the token | Entra ID, Okta, Keycloak, Auth0, Ping and other providers that put groups in tokens |
| `google` | The Cloud Identity Groups API (section 6.5) | Google Workspace. Google tokens never contain groups. |

Only `identity` knows the directory. The `Caller` has the groups, so other modules do not change.

*Reason for a seam here:* two adapters exist in v1. A directory is also the location for a future provider that has
no groups in its tokens.

**Directories outside the identity provider.** Folio does not query LDAP or AD directly.

- **Entra ID with on-premises AD.** Entra Connect copies groups from AD to Entra. These groups are Entra groups, and
  they are in tokens. Rules name them by their Entra object ID.
- **Groups that are only in LDAP.** The identity provider must put them in the token. For example, use an OIDC
  provider that reads LDAP, or federate LDAP into Entra. The identity provider joins directories. Folio does not.
- **Many groups in Entra.** If a user is in more than approximately 200 groups, Entra does not put the groups in the
  token. This is the "overage" condition. To prevent it, configure the app registration to send only the groups that
  are assigned to the application. Then assign the groups that the Folio rules use.

### 6.4 Configure Folio in Entra ID

A Folio administrator does these steps.

1. **Register the API application "Folio".**
   - Set an Application ID URI.
   - Expose a delegated scope, for example `folio.access`.
   - Set `accessTokenAcceptedVersion` to 2.
   - In Folio, set `audiences` to the Application ID URI or the client ID.
   - In Folio, set `issuer-uri` to the v2 issuer of the tenant. The tenant issuer accepts only users and
     applications of the tenant.
2. **Set the subject claim.** Set `folio.security.claims.subject` to `oid`. Entra gives a different `sub` to each
   application. The `oid` is the same for all applications.
3. **Configure the groups claim.** Keep `folio.security.directory` as `token`.
   - Send security groups as object IDs, in access tokens and in ID tokens.
   - Send only the groups that are assigned to the application, to prevent overage.
   - Assign the groups that the rules and the super admins use.
4. **Register a client for the web UI.**
   - Use a web application registration (a confidential client).
   - Add the redirect URI `https://<folio-host>/login/oauth2/code/folio`.
   - Give Folio its client ID and secret in `spring.security.oauth2.client.registration.folio`. Keep the secret in a
     secret store. The scope must contain `openid`.
   - Set `spring.security.oauth2.client.provider.folio.issuer-uri` to the same issuer as `issuer-uri`. If the two
     values differ, Folio does not start.
5. **Register a client for the CLI and for scripts that people start.**
   - Use a public client registration.
   - Turn on "Allow public client flows" for the device flow.
   - Give the client permission for the `folio.access` scope.
6. **Register each workload.** Use one app registration or one managed identity for each workload. The workload uses
   client credentials or a federated credential. Rules name the workload as `application:<client-id>`.
7. **Prevent a wrong match on the application claim.** Entra and Keycloak also put `azp` in user tokens. Thus a rule
   for `application:<id>` also matches people who sign in through that client. To prevent this, do one of these:
   - Use different registrations for workloads and for interactive clients.
   - Set `application-id` to a claim that only workload tokens contain.
8. **Set the super admins.** Put a small Entra group in `folio.super-admins`, for example `group:<object-id>`.
9. **Give access to a new ConfigSet.** After a ConfigSet is created, an administrator with `GRANT` adds rules. The
   rules name group or application object IDs, for example `READ → application:<service-a-client-id>`.

### 6.5 Configure Folio in Google Workspace

Google differs from Entra in three ways:

- Google tokens never contain groups. Folio gets them from the Cloud Identity Groups API.
- Google access tokens are not JWTs. Clients send Google ID tokens as bearer tokens.
- The Google issuer signs tokens for all Google accounts, not only for one organization. Thus Folio must examine the
  domain of each token (step 5).

A Folio administrator does these steps.

1. **Create a Google Cloud project for Folio.** Set the OAuth consent screen to "Internal". Then only users of the
   Workspace organization can sign in.
2. **Create the OAuth clients.**
   - A "Web application" client for the web UI, with the redirect URI `https://<folio-host>/login/oauth2/code/folio`.
     Give its ID and secret to Folio in `spring.security.oauth2.client.registration.folio`. Set
     `spring.security.oauth2.client.provider.folio.issuer-uri` to the same issuer as `issuer-uri` (step 3).
   - A "TVs and Limited Input devices" client for the CLI. It uses the device flow with the scopes `openid` and
     `email`. The CLI sends the ID token. The audience of this token is the client ID of the CLI.
3. **Set the issuer and the audiences.**
   - `issuer-uri`: `https://accounts.google.com`.
   - `audiences`: the client ID of the CLI, and one URL for workloads, for example `https://folio.example.com`.
4. **Set the claims.**
   - `subject`: `sub`. Google gives the same `sub` to each client.
   - `application-id`: `azp`. For a service account, `azp` is the unique ID of the service account. For a person, it
     is the client ID of the CLI.
5. **Restrict the accounts.** Set these values in `folio.security.google`. Folio rejects a token that matches neither
   condition, with status 401.
   - `hosted-domain`: the Workspace domain. A person's token must have this value in its `hd` claim.
   - `service-account-projects`: the projects of the workloads. A workload token must have an `email` that ends with
     `@<project>.iam.gserviceaccount.com`.
6. **Give Folio access to groups.**
   - Set `folio.security.directory` to `google`.
   - Create a service account for Folio. Give it the "Groups Reader" admin role in the Admin console.
   - Run Folio with this service account through Application Default Credentials. On Google Cloud, attach the service
     account. Elsewhere, use workload identity federation. Use a key file only if no other way is possible.
   - Folio calls `groups.memberships.searchTransitiveGroups` with the email of the person. Thus nested groups count.
     This method needs Workspace Enterprise or Cloud Identity Premium. On other editions, set
     `folio.security.google.nested-groups` to `false`. Folio then uses `searchDirectGroups`, and only direct
     memberships count.
   - Folio caches the groups of each person for `folio.security.google.group-cache-ttl` (default 5 minutes). If the
     API fails and the cache has no entry, Folio gives status 503 with the code `DIRECTORY_UNAVAILABLE`. Folio never
     continues without groups, because a missing group can change a decision.
   - Folio looks up groups only for people. Rules name workloads as `application:<service-account-unique-id>`.
7. **Configure workloads.** Each workload runs as a service account in a configured project. It requests an ID token
   with the workload audience, and with the `email` claim. On Google Cloud, use the metadata server with
   `format=full`.
8. **Set the super admins.** Put a small group in `folio.super-admins`, for example `group:<group-id>`. Rules name a
   Google group by its stable group ID from Cloud Identity, not by its email address. *Reason:* an administrator can
   change the email of a group.
9. **Give access to a new ConfigSet.** This step is the same as step 9 for Entra ID.

### 6.6 Add an identity provider

Each deployment trusts one identity provider. To support a new provider, do these steps:

1. Examine its tokens. Find a stable subject claim, an application claim and a groups claim.
2. If the tokens have all three claims, configure the claim names. No code changes.
3. If the tokens have no groups, add a directory adapter in `identity`. Other modules do not change.
4. If the issuer signs tokens for accounts outside the organization, add a restriction such as step 5 of section
   6.5. Put it in the same adapter.
5. Add a setup section to this document.

## 7. Authorization

### 7.1 Actions

Folio has three actions. Folio examines each action on a Namespace or on a ConfigSet.

| Action | Permits |
|---|---|
| `READ` | See the resource, its path and its children. Resolve it. Read the metadata, revisions and files of a ConfigSet. |
| `MANAGE` | Create children. Rename, describe, move and delete. Create and check Git sources. Request sync. On a Namespace, also create, rotate, disable and use the credentials that the Namespace owns. |
| `GRANT` | List, put and delete rules. Explain decisions. |

*Reason for three actions:* no known v1 use case needs a finer split, for example "see a ConfigSet but not read its
files" or "create ConfigSets but not delete them". Each split adds more for policy authors to learn. `MANAGE` is broad
on purpose: a team that administers a Namespace usually does all of these tasks. A later split of `MANAGE` is easy: add the new action, and
copy the `MANAGE` rules to it in a migration.

The actions are independent. `MANAGE` does not include `READ`. If a person needs both actions, grant both.

### 7.2 Rules and inheritance

- A rule has a resource, an action and a set of one or more subjects. A resource has a maximum of one rule for each
  action. `PUT` replaces the rule. `DELETE` removes the rule, and then the action inherits again.
- *Reason for one or more subjects:* a rule with no subjects stops inheritance and denies all callers below it. That
  is a deny rule, and v1 has no deny rules.
- Folio decides an action on a resource in this sequence:
  1. If the caller is a super admin (`caller.superAdmin`), Folio permits the action.
  2. Folio looks for a rule for the action on the resource, then on each parent up to the top-level Namespace.
  3. The nearest rule decides. If a subject of the rule matches the caller, Folio permits the action. If not, Folio
     denies it.
  4. If no rule exists, Folio denies the action.
- A nearer rule replaces the inherited rules for that action. For example, a `READ` rule on `service-a` for its
  workload removes `READ` from all callers that the parent rule permits. To keep their access, put them in the nearer
  rule also.
- Super admins are in the configuration, `folio.super-admins`, in the subject format. They are not rules, so no rule
  can lock them out. Keep the list small. One group is best.
- `identity` owns the subject format and the matching of subjects to callers: `Subject.parse` and
  `Subject.matching(caller)`. Rules and super-admin entries use the same matcher. Identity applies the entries when it
  makes the caller (section 6.1), and the catalog applies the rules.
- `folio.super-admins` accepts only `user:`, `group:` and `application:` entries. `public` or `authenticated` would
  make each caller a super admin, so Folio does not start.
- `public` matches all callers, also anonymous callers. `authenticated` matches all valid tokens.
  `application:<id>` matches the application claim of the token.

### 7.3 Operations

| Operation | Necessary permissions |
|---|---|
| Create a Namespace | `MANAGE` on the parent. At the top level, a super admin. |
| Create a ConfigSet | `MANAGE` on its Namespace, and `MANAGE` on the Namespace that owns the credential |
| Rename, describe, delete | `MANAGE` on the resource |
| Move | `MANAGE` on the resource and on the new parent. If the resource, or a resource below it, has its own rules, also `GRANT` on the new parent. |
| Read, list, resolve | `READ` |
| Rules and explanations | `GRANT` |
| Git check, manual sync | `MANAGE` on the ConfigSet. The check also needs `MANAGE` on the Namespace of the credential. |
| Credential operations | `MANAGE` on the Namespace that owns the credential |
| Master-key status and re-encryption | A super admin (`Access.requireSuperAdmin`) |

*Reason for `GRANT` on a move with rules:* a moved resource keeps its own rules. These rules are then nearer than the
rules of the target. Without this check, a caller could move rules into a Namespace and lock out its administrators.

*Reason for `MANAGE` on the Namespace that owns a credential:* a ConfigSet with a credential can serve each repository
that the credential can read. Only people that the owner of the credential trusts can connect it to a repository.

### 7.4 Responses

- Folio finds the resource first, then authorizes:
  - If the resource does not exist, Folio gives status 404.
  - If the caller cannot do the action, Folio gives status 403 to an authenticated caller. Folio gives status 401 and
    `WWW-Authenticate: Bearer` to an anonymous caller.
  - The error body names the missing action.
  - *Reason:* nobody can guess an ID. The name of the action helps administrators correct grants.
- **Resolve by path is different**, because a person can guess a path. An authenticated caller gets the same 404 for
  a path that does not exist and for a path that it cannot see. An anonymous caller gets the same 401 for both.
- Lists do not show resources that the caller cannot `READ`.
- A caller that can see a resource can also see its path. Thus a grant deep in the tree shows the slugs above it.
- **The start page** (section 12.3) shows the top-most resources that the caller can `READ`, as GitLab does. These are
  the Namespaces and ConfigSets that the caller can `READ` and whose parent it cannot `READ`. Each has its full path.
  A slug of a Namespace that the caller cannot `READ` is plain text, not a link. The lists of the Namespace pages show
  the other readable resources, so the start page does not show them again. For a super admin, the start page shows
  the top-level Namespaces. An anonymous caller sees the resources that `public` permits.
  - For other callers, a resource gets `READ` without its own rule only from its parent. Thus each of their
    start-page resources has a `READ` rule that names the caller.
  - One query finds these rules: their subjects overlap the subjects that match the caller (`public`,
    `authenticated`, `user:`, each `group:` and `application:`). A GIN index on `rule.subjects` serves it. The same
    query walks up the ancestry of each resource.
  - The nearest-rule algorithm then decides each link of each path, because a nearer rule can take `READ` away. A
    resource below such a Namespace is then on the start page again, with its own path.
- `GRANT` on a resource permits an explanation of each decision on it. The explanation shows which rule decided, the
  resource of the rule and the matched subject. If a super-admin entry matched, the explanation shows that.

---

## 8. Modules

Folio is one Gradle module and one Spring Boot application. Each top-level package under
`io.github.big_sw_little_sw.folio` is a Spring Modulith application module. Other modules can use only the public API
of a module, which is its top-level package. Subpackages named `internal` are private. A test verifies these rules.

- **Calls are synchronous.** A module calls the public API of another module directly, in the same thread and
  usually in the same transaction. v1 has no application events. Sync runs on a schedule, not on events. *Reason:* one
  transaction keeps the catalog, the Source definition and the audit record consistent without retries or outboxes.
- **Why Spring Modulith.** It gives deep modules with checked boundaries in one deployable unit. A test enforces the
  boundaries, and the build stays one Gradle module. Java modules (JPMS) enforce boundaries at the JAR level. Thus
  they need one Gradle module for each module and `opens` for Spring reflection, and they give nothing more for Folio.
- **Not for microservices.** The modules are for locality, not for a later split into services. The calls share
  transactions, so a split needs a new design. The clear boundaries make that design easier, but v1 does not plan
  for it.

```text
          git ──────────────┐
           │  │             │
           ▼  ▼             ▼
      content  credential   │
           │      │         │
           ▼      ▼         ▼
           catalog ────► audit
              │            │
              ▼            │
           identity ◄──────┘
              │
              ▼
             web
```

Dependencies point down. Each module can also use `web` for `ApiId`, `FolioException` and the UI layout. The diagram
does not show these arrows. `audit` uses `identity` because each audit record takes the `Caller` and its super-admin
status. The `git` module
implements the Source interface of the `content` module. Thus `content` does not depend on `git`. Spring gives the
adapters to `content` as beans.

| Module | Owns | Public interface for other modules | Hides |
|---|---|---|---|
| `web` | API ID format (section 5.2), error responses, the UI layout | `ApiId`, `FolioException`, the page layout with its `PageContext`, `submit` for UI forms (section 12.3), the UI content security policy, and shared UI fragments | The map to problem details and statuses, the one `@RestControllerAdvice`, the `/error` route, htmx and the styles |
| `identity` | Token validation, browser sign-in, sessions, directories, `Caller`, subjects, super admins | `Caller`, `CurrentCaller` (HTTP layers only), `Subject`, `SuperAdmins`, `IdentityException` | The filter chains, the claim map, the directory adapters, URL rules |
| `audit` | `audit_event` | `AuditLog.record(entry)` | The insert, the retention period |
| `catalog` | Namespaces, ConfigSets, paths, rules, decisions | `Access`, `SnapshotRead`, `ConfigSets`, `ConfigSet`, `Namespaces`, `Namespace`, the ID types and their API IDs, `Slug`, `Path`, `Action`, `SourceType`, `CatalogException` | The tree lock, recursive queries, the nearest-rule algorithm, move checks |
| `credential` | Credentials, keys, the master-key ring | `Credentials.requireUsable`, `CredentialKeys.withActiveKey`, `CredentialKeys.withPendingKey`, `CredentialId`, `KeyId` and their API IDs, `CredentialException` | Key generation, encryption, rotation, OpenSSH format |
| `content` | The consumption API, the Source interface, validation | `ConfigSource`, for adapters to implement | Revision selection, cache headers, media types, validation |
| `git` | Git sources, sync, the cache | None. It is an adapter with its own admin routes. | SSH, host keys, JGit, leases, fetch limits, the cache, revision history |

Each module serves its own HTTP routes from a subpackage named `web`. This includes its UI pages (section 12.3).

### 8.1 catalog

The catalog is one module because Namespaces, ConfigSets and rules change together:

- A move changes paths and inherited rules.
- A delete of a Namespace depends on its ConfigSets.
- Each write needs the same lock.

If these were different modules, they would need events and lock APIs across modules to keep one invariant.

```kotlin
/** Authorization for other modules. The caller finds the resource first, so this never reports "not found". */
class Access {
    /** Throws Forbidden (403) or Unauthenticated (401) if the caller cannot do the action. */
    fun requireAllowed(caller: Caller, action: Action, resource: ResourceId)

    /** True if the caller can do the action. The UI uses it to show only the operations that the caller can do. */
    fun allows(caller: Caller, action: Action, resource: ResourceId): Boolean

    /** True if an anonymous caller can READ the resource. Content uses it for Cache-Control. */
    fun isPublic(resource: ResourceId): Boolean

    /** Throws Forbidden (403) on "the root" or Unauthenticated (401) if the caller is not a super admin. */
    fun requireSuperAdmin(caller: Caller)
}

/** ConfigSet creation for Source adapters. The HTTP routes do the other operations. */
class ConfigSets {
    /** Creates the ConfigSet. The adapter writes its Source definition in the same transaction. */
    fun create(caller: Caller, namespace: NamespaceId, slug: Slug, description: String?, sourceType: SourceType): ConfigSet

    /** Finds a ConfigSet without authorization. */
    fun find(id: ConfigSetId): ConfigSet?
}

/** Namespace lookups for other modules. The credential module uses it for the owner of a credential. */
class Namespaces {
    /** Finds a Namespace without authorization. */
    fun find(id: NamespaceId): Namespace?

    /** Finds the Namespace (404), then requires the action on it (403 or 401), under one tree state. */
    fun require(caller: Caller, action: Action, id: NamespaceId): Namespace
}

/** The ConfigSet with its current path. Consumers keep the ID. */
data class ConfigSet(val id: ConfigSetId, val namespaceId: NamespaceId, val slug: Slug, val description: String?,
                     val sourceType: SourceType, val path: Path)

/** A Namespace or a ConfigSet. `NamespaceId` and `ConfigSetId` are value classes that implement it. */
sealed interface ResourceId { val value: UUID }
```

- **Who matches.** `Caller.System` matches no rule, also not `public`, and is never a super admin. Background work
  acts without authorization, so it does not call `Access`. If it does, `Access` denies it with status 403.
- **Super admins.** `Access` reads `caller.superAdmin` first and needs no query for it. `requireSuperAdmin` is the
  same check as `MANAGE` at the root, because the root has no rules. Thus a master-key operation and a top-level
  create give the same errors. It is in `Access`, not in `identity`, because the catalog already owns the 403 and 401
  errors of authorization.
- **A resource that is gone.** If the resource was deleted after the caller found it, it has no rules. Then `Access`
  permits only super admins, and the next step of the caller finds nothing.

- **Authorization.** The catalog owns the rules, so it makes all decisions. Each module calls
  `Access.requireAllowed` in its service before it does an operation. Other modules never read rules.
- **Tree lock.** Each catalog write, also a rule write, first takes one PostgreSQL advisory lock for the transaction:
  `pg_advisory_xact_lock(hashtextextended('folio.catalog.tree', 0))`. `ConfigSets.create` takes it in the transaction
  of the Source adapter.
  Thus only one catalog write runs at a time. A write examines the tree and then changes it. The lock makes sure that
  the tree does not change between these two steps. Examples:
  - A move examines that the target is not below the moved Namespace. Without the lock, a parallel move could make a
    cycle.
  - A delete examines that the Namespace is empty. Without the lock, a parallel create could add a child.
  - A move examines the rules at the target for `GRANT`. Without the lock, a parallel rule write could change them.

  Writes are rare administrative operations, so the lock costs almost nothing. Reads do not take the lock.
- **Read snapshots.** A read that authorizes, also in `Access`, runs in one read-only `REPEATABLE READ` transaction.
  Thus the ancestry and the rules of a decision come from one snapshot. A decision never combines a tree and rules
  that did not exist together. Writes keep `READ COMMITTED`, because their snapshot must start after the tree lock.
  When `Access` joins the transaction of another module, that transaction sets the isolation. The annotation
  `@SnapshotRead` of the catalog gives this transaction. Put it on each read that authorizes, also in `content`,
  `credential` and `git`. *Reason:* one name for one policy. A read with the wrong isolation still compiles.
- **Writes of other modules.** A write of another module, for example a credential change, authorizes in its own
  `READ COMMITTED` transaction. There each statement has its own snapshot, so a catalog commit could fall between the
  ancestry read and the rules read. Thus `Access` and `Namespaces.require` take the shared form of the tree lock
  (`pg_advisory_xact_lock_shared`) before the ancestry read when the transaction is not read-only. The shared form
  waits for a catalog write to commit, and it blocks the next catalog write until the transaction ends. Thus the two
  reads see one committed tree state.
  - A catalog write already holds the exclusive form. PostgreSQL never blocks a transaction on its own locks, so a
    catalog write that calls `Access` does not wait for itself.
  - A write that also takes the exclusive form, for example through `ConfigSets.create`, must take it before it calls
    `Access` or `Namespaces.require`. Otherwise two such writes can each hold the shared form and wait for the
    exclusive form of the other.
- **Ancestors.** A recursive query on `parent_id` finds the ancestors. Trees are shallow. A closure table makes moves
  more difficult and gives no measurable gain.
- **Nearest-rule algorithm.** It is a pure function in `catalog.internal`. Unit tests examine it without Spring.
- **Lists.** A list reads the rules of the parent chain and of all children in one query, and applies the algorithm to
  each child.

The catalog declares these errors:

| Error | Status | Code | Meaning |
|---|---|---|---|
| `NamespaceNotFound`, `ConfigSetNotFound` | 404 | `NAMESPACE_NOT_FOUND`, `CONFIG_SET_NOT_FOUND` | No resource has the ID. |
| `PathNotFound` | 404 | `PATH_NOT_FOUND` | Resolve found no ConfigSet that the caller can read (section 7.4). |
| `Forbidden` | 403 | `ACTION_NOT_ALLOWED` | The detail names the action and the API ID, or "the root". |
| `Unauthenticated` | 401 | `AUTHENTICATION_REQUIRED` | An anonymous caller. The detail names the action. |
| `InvalidSlug`, `InvalidPath`, `InvalidDescription` | 400 | `INVALID_SLUG`, `INVALID_PATH`, `INVALID_DESCRIPTION` | The value does not have the correct form. |
| `NoSubjects` | 400 | `NO_SUBJECTS` | A rule needs one or more subjects. |
| `SlugTaken` | 409 | `SLUG_TAKEN` | A sibling has the slug. The repository that owns the unique constraint changes the violation to this error. |
| `NamespaceNotEmpty` | 409 | `NAMESPACE_NOT_EMPTY` | The Namespace has children, ConfigSets or credentials. |
| `MoveIntoOwnSubtree` | 409 | `MOVE_INTO_OWN_SUBTREE` | The target is the Namespace or a Namespace below it. |

Folio finds the resource, then authorizes, then examines the tree. Thus a caller without permission gets 403 or 401,
not a conflict. Values in the body, such as slugs, give status 400 before Folio finds the resource.

A subject that does not have the subject format gives `IdentityException.InvalidSubject` (400, `INVALID_SUBJECT`),
because `identity` owns the format (section 7.2).

### 8.2 content

The `content` module owns all that consumers see, for all Sources. For each read, it does these steps:

1. Find the ConfigSet.
2. Authorize `READ`.
3. Change `latest` to an exact revision.
4. Select the adapter for the `sourceType` of the ConfigSet, and call it.
5. Add the validation status and the cache headers.

Steps 1 and 2 run in one `@SnapshotRead` transaction. The adapter calls run outside a transaction. Thus a slow read or
a fetch on demand holds no database connection. Section 9 gives the interface that content calls.

### 8.3 credential

The `credential` module owns all of the credential lifecycle. `CredentialKeys.withActiveKey(credentialId) { key ->
… }` decrypts the active key for the duration of the block. It does not authorize, because sync runs without a person.
Only the `git` module calls it. No HTTP route can call it, and a modularity test verifies this.

```kotlin
/** Onboarding: MANAGE on the Namespace of the credential, and an enabled credential (section 7.3, 10.3). */
fun Credentials.requireUsable(caller: Caller, id: CredentialId): UsableCredential   // id, namespaceId, gitInstance

fun <T> CredentialKeys.withActiveKey(id: CredentialId, block: (DecryptedKey) -> T): T
/** The check with {"keyId"} (section 10.3). Throws KeyNotPending (409) if the key is not the pending key. */
fun <T> CredentialKeys.withPendingKey(id: CredentialId, key: KeyId, block: (DecryptedKey) -> T): T

class DecryptedKey(val gitInstance: Slug, val keyId: KeyId, val keyPair: KeyPair)        // toString shows no key
```

- `withPendingKey` exists because the check of section 10.3 tests a pending key before the activation. The module
  interface gets the operation now, so the `git` module never reads key rows.
- Both methods throw `CredentialNotFound` (404) and `CredentialDisabled` (409). A disabled credential gives no key.
- A key that does not decrypt is an unexpected error, not a credential error. Its `IllegalStateException` names the
  key and the credential, and never a secret. Thus no crypto exception type reaches the `git` module.
- The block runs outside a transaction. Thus a long fetch holds no database connection.
- Folio sets the decrypted PKCS#8 bytes to zero as soon as the JDK key object exists, before the block runs.

### 8.4 git

The `git` module is the Git adapter and all that it needs. Other modules do not call it. It owns these items:

- Onboarding, which calls `ConfigSets.create`.
- The check.
- Sync.
- The cache.
- The revision history.

Section 10 gives the details.

### 8.5 audit

`AuditLog.record` must run in the transaction of the operation (`Propagation.MANDATORY`). Thus a record commits with
its operation, and a failed insert rolls back the operation. Each module calls `AuditLog.record` directly for its own
operations. Thus a new audited operation changes one module only.

### 8.6 Errors

- Each module declares a sealed exception type in its public API. The type extends `FolioException`. There is one
  subclass for each expected failure.
- Each subclass has a kind and a stable `code`. The kinds are `NOT_FOUND`, `CONFLICT`, `INVALID`, `FORBIDDEN`,
  `UNAUTHENTICATED`, `UNAVAILABLE`, `UPSTREAM_FAILED`, `UPSTREAM_TIMEOUT` and `TOO_LARGE`.
- `ErrorKind` gives each kind one status: 404, 409, 400, 403, 401, 503, 502, 504 and 422. Modules never select HTTP
  statuses. The one advice in `web` writes an RFC 9457 problem detail with the status of the kind and the `code`. The UI
  form helper `submit` of `web` uses the same status, so a failed form has the status of the API (section 12.3).
- Some unique and foreign-key violations have a domain meaning. The repository that owns the constraint changes them
  to the related exception.
- Do not catch an exception of another module in your transaction. Spring already marked the transaction for
  rollback, so the commit fails. Do a check first, or let the exception go to the caller.

All API errors have the same body. This includes 401 and 403 responses from the security filters, 400 responses for
requests that are not valid, and unexpected errors. An unexpected error gives status 500 with the code `INTERNAL`, and
the body never contains the exception message.

- Spring MVC's own failures use the same body. A request that Spring cannot read, for example JSON that does not
  parse or a missing parameter, gives status 400 with the code `INVALID_REQUEST`. Other statuses use the name of the
  status as the code, for example `NOT_FOUND` or `METHOD_NOT_ALLOWED`.
- The security filters give their failures to the advice. A bearer token that is not valid gives `INVALID_TOKEN`. If
  Folio cannot get the discovery document or the signing keys of the issuer, a bearer token gives status 503 with
  `ISSUER_UNAVAILABLE`. A UI request without a valid CSRF token gives `CSRF_TOKEN_INVALID`. A failed browser sign-in
  gives status 401 with `SIGN_IN_FAILED`.
- The log gets the cause of a failure that the configuration can cause: each failed sign-in, and a bearer token that
  the issuer signed for Folio but whose claims give no caller. The client gets only the code. Folio does not log
  other token failures, such as a bad signature, because any client can cause them.
- Errors outside Spring MVC, for example a request that the firewall rejects, go to the `/error` route of `web`. That
  route gives them to the advice. A `FolioException` from a filter keeps its code and status.
- All error responses have `Cache-Control: no-store`.
- The body leaves out `type`, because RFC 9457 gives `about:blank` as its default.

```json
{
  "title": "Forbidden",
  "status": 403,
  "code": "ACTION_NOT_ALLOWED",
  "detail": "The caller does not have MANAGE on cfg_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e."
}
```

The `code` is the stable part for clients. The `detail` is for people and can change. One exception is permitted:
the Git check gives status 200 with a result body (section 10.3), because a failed check is a correct answer.

### 8.7 Threads

Folio runs on Java 25 virtual threads (`spring.threads.virtual.enabled: true`). Requests, scheduled tasks and sync
fetches run on virtual threads. Folio never pools virtual threads. A `Semaphore` sets each concurrency limit, for
example the limit on fetches. *Reason:* Git and database calls block. Virtual threads make blocking code cheap, and the
code stays simple.

---

## 9. The Source interface

The Source interface is the one seam that v1 adds for the future. It has a Git adapter now, a database adapter next
and an in-memory adapter for tests.

```kotlin
/**
 * The origin of the content of a ConfigSet. There is one adapter for each SourceType.
 *
 * A revision is a snapshot of the full ConfigSet. An adapter accepts only revisions that it
 * published, and a published revision never changes. Adapters do not authorize. Content does.
 */
interface ConfigSource {
    val type: SourceType

    /** The newest published revision, or null if no revision is published. */
    fun latest(configSet: ConfigSetId): RevisionId?

    /** Published revisions, newest first, with a maximum of [limit]. */
    fun revisions(configSet: ConfigSetId, limit: Int): List<Revision>

    /** The ConfigItems at a published revision. */
    fun list(configSet: ConfigSetId, revision: RevisionId): List<ConfigItemInfo>

    /**
     * Opens one ConfigItem, calls [consume] with its content as a stream, and closes the stream after [consume]
     * returns. Throws ItemTooLarge above [maxBytes] before it opens the content. The adapter gets the size and the
     * ETag without a read of the content. It reads the content only when [consume] reads the stream.
     */
    fun <T> read(
        configSet: ConfigSetId,
        revision: RevisionId,
        path: ItemPath,
        maxBytes: Long,
        consume: (ConfigItem, InputStream) -> T,
    ): T
}

// In the public API of catalog, because the catalog stores the type and content depends on the catalog.
enum class SourceType { GIT }

data class Revision(val id: RevisionId, val publishedAt: Instant)

data class ConfigItemInfo(val path: ItemPath, val size: Long)

data class ConfigItem(val path: ItemPath, val size: Long, val etag: String)
```

**Streaming.** `read` gives a stream, not a byte array. Thus memory does not increase with the size of a file. The
adapter owns the stream and closes it, so a caller cannot leak a repository handle or a database connection. Content
uses the stream in this way:

- For a file of 3 MB or less, content reads all bytes, validates them, sets the headers and writes the bytes.
- For a larger file, content sets the validation status to `UNKNOWN` and copies the stream to the response.

On virtual threads, a blocking copy to the response is cheap (section 8.7). The size and the ETag come before the
content: Git knows both from the object ID, and the database Source stores both.

**`SourceType`.** The enum names the Sources that this build contains. A new Source needs new code in any case: an
adapter, a creation route and a migration. Thus a closed enum costs nothing, and exhaustive `when` expressions show
each location that a new Source must update. Content keeps a map from `SourceType` to the adapter bean. Two adapters
for one type stop the start of Folio. A type without an adapter is an internal error (500) at the time of a read, not
at startup, because the main code has no adapter until the Git slice (section 20). The Git slice makes a missing
adapter a startup failure. The
`config_set.source_type` column stores the name. The enum is in the public API of the `catalog` module, because
`ConfigSets.create` takes it and `content` depends on the catalog. Folio has no plugins. If plugins become necessary, the enum changes to
a string.

The `content` module declares these errors:

| Error | Status | Code | Meaning |
|---|---|---|---|
| `NotPublishedYet` | 404 | `NOT_PUBLISHED_YET` | A read of `latest`, and the ConfigSet has no published revision (section 5.5). |
| `RevisionNotFound` | 404 | `REVISION_NOT_FOUND` | The ConfigSet did not publish this revision. |
| `RevisionUnavailable` | 404 | `REVISION_UNAVAILABLE` | The ConfigSet published the revision, but this instance cannot read it now. |
| `ItemNotFound` | 404 | `ITEM_NOT_FOUND` | The revision has no item at this path. |
| `ItemTooLarge` | 422 | `ITEM_TOO_LARGE` | The item is larger than `folio.content.max-file-size`. |
| `SourceBusy` | 503 | `SOURCE_BUSY` | The Source cannot do the read now. The response has `Retry-After`. |
| `SourceFailed` | 502 | The code of the adapter | The Source failed. The adapter gives its code and a fixed, safe summary. |
| `SourceTimedOut` | 504 | The code of the adapter | The Source did not answer in time. The adapter gives its code and a fixed, safe summary. |
| `InvalidRevision` | 400 | `INVALID_REVISION` | The `revision` value is not `latest` and not a revision ID. |
| `InvalidItemPath` | 400 | `INVALID_ITEM_PATH` | The item path is not in normal form. |

Adapters throw these errors too. A detail never repeats an item path, because a path can contain any text except a
backslash or NUL.

**`RevisionId`** has 1 to 64 lowercase letters and digits, and it is never `latest`. Each Source must give IDs of this
form. A Git commit ID has this form. *Reason:* content can reject a malformed `revision` value with status 400 before it
finds the ConfigSet, as for other values in a request (section 8.1). An ID also needs no escape in a URL.

**`ItemPath`** is relative and already in normal form. It has segments joined by `/`. No segment is empty, `.` or
`..`, and no segment contains a backslash or NUL. Folio rejects other paths. It never rewrites them. An adapter joins
the segments below the root of the ConfigSet, so a path cannot go outside the root.

The interface does not contain these items, and the reason for each:

- **Sync.** Sync has no meaning for a database. The Git adapter syncs on its own schedule.
- **Branches, tags, commits and connections.** These are details of the adapter.
- **Capability flags.** No caller uses them. If a Source cannot do an operation, the admin routes of its adapter do
  not offer the operation.
- **Writes.** The database Source adds edits as a different interface (section 18.1). Thus read-only Sources never
  implement methods that have no meaning for them.

**Test of the seam:** section 18.1 shows the database adapter with this interface. If the interface must change for
the database adapter, change it now, while one production adapter exists.

---

## 10. Git source

### 10.1 Git instances

The deployment configuration gives the Git servers. They are not database rows.

```yaml
folio:
  git:
    instances:
      github-internal:
        host: github.example.com
        port: 22          # default
        user: git         # default
        host-keys:
          - ssh-ed25519 AAAA…
```

- Instance names follow the slug rules.
- `user` is the SSH login name on the server. Most Git services use `git` for all accounts, and the key selects the
  account. Some self-hosted servers use a different name. The host keys have a different function: they let Folio
  authenticate the server. The user and the credential let the server authenticate Folio.
- `host-keys` is mandatory and must have one or more lines. Folio parses each line at startup. If a line is not valid,
  Folio does not start. The message names `folio.git.instances.<name>.host-keys[<index>]`. It never shows the key.
- Folio trusts only the configured host keys. It rejects unknown and changed keys, and never adds them. There is no
  "accept new" mode and no fallback to `known_hosts`.
- To rotate the host key of a server, add the new key to the list before the server changes. Remove the old key after
  the change.
- *Reason for configuration:* host keys are security decisions. A deployment review must approve them, and a change
  needs a new deployment.

### 10.2 Source definition

A Git ConfigSet has these items:

- **A credential.** The credential names the Git instance (section 11.1), so the Source does not name it again.
  *Reason:* a Git service trusts a key, and each key belongs to one service. If the Source named the instance, a
  person could send the key to a different server. Then that server could identify the key, and Folio would have two
  values that must agree.
- **A repository path** on that instance, for example `org/repo.git`. It has segments of letters, digits, `.`, `_` and
  `-`, joined by `/`. No segment is `.` or `..`. It has 500 characters or fewer. Thus it cannot contain a scheme, a
  user or a host.
- **A branch.** Folio validates it as a Git ref name. v1 does not support tags or pinned commits.
- **A root path.** The ConfigItems are below this path. An empty root path is the root of the repository.

Folio makes the SSH URL as `ssh://<user>@<host>:<port>/<repository path>`. The URL contains no secret.

Credentials are part of the Git Source only. The database Source keeps content in the Folio database, so it needs no
credential. A future Source that connects to an external system, for example HTTPS Git, adds its own type of
credential (section 18.2).

The Source definition does not change after creation. *Reason:* published revisions must not change (section 5.5). To
use a different repository, create a new ConfigSet. A later design can permit changes to a Source. That design must
also decide what happens to the revisions that Folio already served.

### 10.3 Onboarding and the check

1. `POST /api/v1/admin/git/configsets` creates the ConfigSet and its Git source in one transaction. It does not
   connect to the Git server. It needs `MANAGE` on the Namespace and on the Namespace of the credential. The
   credential must be enabled.
2. The administrator registers the public key of the credential with the Git service, in one of these ways. Folio
   cannot see which way.
   - As a deploy key of the repository: GitHub, GitLab, Bitbucket and Gitea support this.
   - On a bot account: use this for Azure DevOps, which has no deploy keys.
3. `POST /api/v1/admin/configsets/{id}/git:check` connects and does these steps:
   1. It runs `ls-remote` for the branch.
   2. It fetches the branch.
   3. It makes sure that the root path is a directory at the tip.
4. The check gives status 200 with one of these bodies. A failed check is a result, not an HTTP error.
   - `{"ok": true, "commitId": …}`
   - `{"ok": false, "code": …, "summary": …}`
5. Optionally, the check request has the body `{"keyId": "key_…"}`. Then the check uses the pending key of the
   credential. Thus an administrator can test a new key before activation.
6. Sync starts immediately, because a new ConfigSet is due at creation.

**Failure codes.** Each code has a fixed, safe summary:

- `HOST_KEY_REJECTED`, `AUTH_FAILED`, `REPOSITORY_NOT_FOUND`, `BRANCH_NOT_FOUND`, `ROOT_PATH_NOT_FOUND`
- `UNREACHABLE`, `TRANSPORT_FAILURE`, `DEADLINE_EXCEEDED`, `REPOSITORY_TOO_LARGE`
- `CREDENTIAL_DISABLED`, `GIT_INSTANCE_NOT_CONFIGURED`

Folio selects a code from exception types and results. It never parses messages. Some servers report "not found" when
a key has no access. The summary of `REPOSITORY_NOT_FOUND` says that both causes are possible.

### 10.4 Isolation and secrecy

Folio connects with stored private keys. Thus the environment of the server must not change what Folio trusts or
which key it sends.

- Each connection uses an empty SSH home directory below the cache directory.
- Each connection uses no SSH config, no agent, no default identities and no `known_hosts`.
- Each connection uses only `publickey` authentication, with the key of the credential as the only key.
- A JGit system reader for the full process gives an empty system and user Git config.
- The JGit and Apache sshd loggers are off by default, because their messages can contain server output. Server
  output can contain repository names, account names and text that the server controls.
- Folio logs a maximum of a ConfigSet ID, a failure code, the exception types and a duration. Exception messages and
  transport output never go to responses or to Folio's own logs.

**Diagnosis of connection problems.**

1. Run the check (section 10.3). The failure code and its summary identify most problems.
2. Read the Folio log for the check. It gives the exception types, for example `SshException` caused by
   `ConnectException`.
3. If you need more, set `logging.level.org.apache.sshd` and `logging.level.org.eclipse.jgit` to `DEBUG` on one
   instance. Run the check again. Then set the levels back to `OFF`. Use this procedure only on an instance whose logs
   are not public. sshd never logs private keys, but it logs server messages.

**Key type.** v1 generates only Ed25519 keys. This is a decision, not a limit of a library.

- All Git services that Folio targets accept Ed25519 keys. Azure DevOps added Ed25519 in 2025, and it is removing
  `ssh-rsa`.
- Ed25519 has no parameters to select. RSA needs a key size and a signature algorithm.
- Ed25519 keys and signatures are small and fast.
- One key type gives one code path, one test set and one encoding.

If a Git service refuses Ed25519, add RSA as a second key type in the `credential` module. The key row then stores
its type.

**Providers.**

- The JDK generates the Ed25519 keys. Apache sshd needs Bouncy Castle to sign with Ed25519. Thus `bcprov` is a
  `runtimeOnly` dependency, and only sshd uses it.
- Folio's own crypto uses only the JDK providers. A test makes sure that the JDK providers are still first.
- If sshd reports no Ed25519 support, Folio does not start.

### 10.5 Cache

- `folio.git.cache-directory` contains one bare repository for each ConfigSet. The ID of the ConfigSet is the name.
  Only the owner can access the directory (`0700`).
- Folio can delete and rebuild the cache at any time. The database is the source of truth for published revisions.
  Each instance has its own cache. If a repository is missing or damaged, the cost is one full fetch.
- Folio fetches only the configured branch. It does not fetch tags.
- JGit's automatic gc is off in each cache repository, because it races with reads and with the size check.
- At each sync interval, each instance deletes the repositories of ConfigSets that no longer exist. It lists the
  directory before it queries the database. Thus it never deletes the repository of a ConfigSet that exists. It never
  follows symbolic links.

### 10.6 Sync

Sync uses polling. The state is in the database, so several instances can share the work.

- **State.** `git_source` has one row for each ConfigSet. The row contains:
  - The last seen commit and the last published commit.
  - The time of the last attempt and the time of the last success.
  - The failure code, the summary and the number of consecutive failures.
  - The next due time.
  - The lease owner and the lease end.
- **Due time.** A ConfigSet is due `folio.sync.interval` (default 1 minute) after the last attempt. Each consecutive
  failure doubles the interval, to a maximum of `folio.sync.max-backoff` (default 30 minutes).
- **Claims.**
  - Every `folio.sync.poll-interval` (default 10 seconds), an instance claims due ConfigSets.
  - It claims a maximum of the free permits of its fetch semaphore, `folio.sync.max-concurrent-fetches` (default 4).
  - One statement makes the claim: `UPDATE … FROM (SELECT … FOR UPDATE SKIP LOCKED LIMIT n)`.
  - The fetch runs outside a transaction, on a virtual thread.
  - Folio records the result only if the lease still belongs to this claim.
  - All times come from the database clock.
- **Lease length.** The lease is the fetch deadline plus three minutes. The extra time covers SSH authentication,
  which Folio cannot stop (section 10.7). During a lease, the next due time is the lease end. Thus, if an instance
  stops, its ConfigSets become due again when the leases end.
- **No duplicate in one instance.** The poller never claims a ConfigSet that it syncs now.
- **Change detection.** Sync compares fetched commits. The fetched branch tip becomes the last published commit. If the
  tip changed, sync adds it to `git_revision`. After a failure, the last published commit does not change. This is
  also true when the branch was deleted.
- **Manual sync.** A request makes the ConfigSet due immediately. The response is status 202 with the sync state. The
  next poll of an instance syncs the ConfigSet.
- **Unexpected errors.** Folio releases the lease and records no failure. It logs the ConfigSet ID and the exception
  type.
- **Shutdown.** The poller waits ten seconds. Then it releases its leases and interrupts the remaining syncs.

### 10.7 Fetch limits

- **Deadline.** `folio.git.fetch-deadline` (default 5 minutes) limits each fetch by the wall clock. The deadline
  starts before the `ls-remote` and ends after delta resolution. It includes the wait for another fetch of the same
  ConfigSet.
  - At the deadline, a watchdog closes the streams of the SSH command and destroys the command.
  - The progress monitor also cancels local work.
  - Folio cannot stop SSH key exchange and authentication (sshd limit: 2 minutes) or the open of a channel (JGit
    limit: 30 seconds). Thus a fetch can end a maximum of 2.5 minutes after the deadline.
  - *Reason for the deadline:* the JGit timeout applies only to idle reads. A slow server that sends data continuously
    never triggers it.
- **Size.** After each fetch, Folio adds the sizes of the repository files on disk. It holds the fetch lock during this
  calculation. If the total is more than `folio.git.max-repository-size` (default 1 GB), Folio deletes the repository.
  The fetch then fails with `REPOSITORY_TOO_LARGE`. JGit cannot limit a pack during transfer, so the deadline limits
  the transfer time.
- **One fetch for each ConfigSet in each instance.** A lock in the process prevents parallel fetches of one ConfigSet.
  Folio takes the lock before the `ls-remote`. Sync, the check and reads all use this lock.

### 10.8 Fetch on demand

The published commit of a ConfigSet is possibly not in the cache of this instance. Then a read fetches it. These limits
prevent anonymous readers from causing too many fetches:

- An instance runs a maximum of `folio.content.max-concurrent-fetches` (default 2) fetches on demand. Above this
  limit, the read fails immediately with `SourceBusy` (503).
- A read waits a maximum of 5 seconds for another fetch of the same ConfigSet. After that, it fails with `SourceBusy`.
  When the read gets the lock, it examines the cache again. Thus a read that waited usually does not fetch.
- A published commit can be missing after a fetch, for example after a force push. Folio remembers this for one sync
  interval. During that time, reads of the commit fail with `RevisionUnavailable`.
- If the last sync failed with `REPOSITORY_TOO_LARGE`, a read that needs a fetch fails immediately with that code.

---

## 11. Credentials

### 11.1 Model and lifecycle

- A Namespace owns each credential. A credential names one Git instance. It has a name that follows the slug rules and
  is unique in its Namespace. Its status is `ENABLED` or `DISABLED`.
- **Git instance name.** Folio validates the name of the Git instance with the slug rules only. It does not compare
  the name with `folio.git.instances`. *Reason:* the `git` module owns that configuration, and `credential` must not
  depend on `git`. A deployment can also remove an instance after a credential names it. Thus the Git check reports
  `GIT_INSTANCE_NOT_CONFIGURED` in all cases (section 10.3).
- A credential contains its name, its Git instance, its status and its keys. Each key has a public key, a fingerprint
  and an encrypted private key. A credential contains no password, no token and no URL.
- **Use by more than one Namespace.** A ConfigSet can use a credential of any Namespace. The person who creates the
  ConfigSet needs `MANAGE` on the Namespace that owns the credential (section 7.3). Thus two teams can share one
  credential: put it in a common parent Namespace, and give `MANAGE` there to the people who connect repositories.
  Separate credentials for each team are safer, because each key then reads fewer repositories. Also, some services
  limit sharing. For example, GitHub accepts a public key as a deploy key on one repository only. To read many
  GitHub repositories with one key, register the key on a bot account.
- **Create** generates the first key and activates it.
- **Regenerate** adds a `PENDING` key and gives its public key. If a key is already pending, Folio gives status 409.
- **Activate** names the pending key: `{"keyId": "key_…"}`. If the key is not the pending key, Folio gives status 409.
  *Reason:* the name prevents the activation of a key that another person generated in the meantime. The previous
  active key becomes `RETIRED`.
- **Discard** names the pending key and retires it. The active key does not change.
- **Replace** is for emergencies. It retires the active key and the pending key, if one exists. Then it generates a new
  key and activates it.
- **Disable** is permanent in v1. A second disable does nothing. A disabled credential refuses key changes and gives
  no key to sync. Folio keeps its encrypted keys, so a later version can add "enable".
- A retired key keeps its public key and fingerprint. Folio erases its encrypted private key.
- A credential has a maximum of one `ACTIVE` key and one `PENDING` key. Partial unique indexes enforce this. Each key
  change locks the credential row first.
- Each credential always has exactly one `ACTIVE` key. Create and replace activate a key, and activate retires the
  previous key and activates the pending key in one transaction. Thus "no active key" is not an error of the
  interface, and the Git check has no failure code for it.
- Folio gives public keys as OpenSSH lines. The comment of the line is the API ID of the key. Thus an administrator can
  match a deploy key in the Git service to its Folio key.
- Folio never gives, accepts or writes a private key without encryption.
- **Delete** removes a credential and all its keys. Folio deletes a credential only when no Git source uses it.
  Otherwise it gives status 409 with the code `CREDENTIAL_IN_USE`. Thus a ConfigSet never loses its credential. The
  foreign key from `git_source` to `credential` does not cascade, and it enforces this rule.
- Rotation does not need a delete. Regenerate and activate retire the old key and erase its private key. Then remove
  the old public key from the Git service. Use delete when no ConfigSet needs the credential any more.
- Delete also works for a disabled credential. Until the Git slice adds `git_source`, a delete always succeeds. The
  Git slice adds the foreign key, and the credential repository then changes its violation to `CREDENTIAL_IN_USE`.
- Each operation finds the credential (404), then authorizes `MANAGE` on its Namespace (403 or 401), then examines
  the state (409). Thus a caller without permission never learns the state.
- Reads of credentials also need `MANAGE` on the Namespace, because the actions are independent (section 7.1).

The `credential` module declares these errors. A name or a Git instance that is not a slug gives `INVALID_SLUG` of the
catalog, because the catalog owns the slug rules.

| Error | Status | Code | Meaning |
|---|---|---|---|
| `CredentialNotFound` | 404 | `CREDENTIAL_NOT_FOUND` | No credential has the ID. |
| `NameTaken` | 409 | `CREDENTIAL_NAME_TAKEN` | Another credential of the Namespace has the name. |
| `CredentialDisabled` | 409 | `CREDENTIAL_DISABLED` | A key change, a use or a key for sync on a disabled credential. |
| `PendingKeyExists` | 409 | `PENDING_KEY_EXISTS` | A regenerate while a key is pending. |
| `KeyNotPending` | 409 | `KEY_NOT_PENDING` | Activate, discard or a check named a key that is not the pending key. |
| `CredentialInUse` | 409 | `CREDENTIAL_IN_USE` | A Git source uses the credential. The Git slice adds this error. |

### 11.2 Encryption at rest

- Folio encrypts each private key (PKCS#8) with **AES-256-GCM**.
- The encryption key comes from **HKDF-SHA256** with these inputs:
  - A master secret.
  - A new random salt of 32 bytes.
  - The fixed label `folio credential private key v1`.
- Each encryption uses a new random nonce of 96 bits and a tag of 128 bits.
- **Associated data** connects the ciphertext to its row. It has 36 bytes in fixed-width big-endian format: the
  credential UUID, the key UUID and the master-key version. If a ciphertext moves to another row or gets a different
  version, decryption fails.
- Each key row stores the algorithm name, the master-key version, the salt, the nonce and the ciphertext. These five
  columns are null only when the key is retired. A check constraint enforces this.
- Folio generates the key ID before the insert, because the associated data contains it.
- Folio sets to zero each byte array with a secret that it creates, after use.
- Classes that contain secrets override `toString`.
- During a fetch, Folio decrypts the private key into memory. The JDK key objects and the Bouncy Castle signer keep
  copies of the private key bytes. Folio cannot set these copies to zero. They stay in memory until garbage
  collection removes them. They are never on disk, unless a heap dump writes them. Thus protect heap dumps as
  secrets, or turn them off. A retired or deleted key has no ciphertext in the database, so a later decryption is not
  possible.
- A known-answer test decrypts a vector from a tool outside the JDK. Thus a change to an encoding stops the build.

### 11.3 Master keys and rotation

- `folio.crypto.master-keys` maps a positive integer version to a base64 secret of 32 bytes or more.
- `folio.crypto.active-key-version` names the version for new encryptions.
- Folio does not start in these conditions. The messages name versions and never secrets.
  - The ring is empty.
  - A version is not a positive integer.
  - The active version is missing.
  - A secret is not valid.
  - A stored key uses a version that is not in the configuration.
- `folio.crypto.master-keys` binds as a map from strings to strings, and the `MasterKeyRing` bean parses the
  versions. *Reason:* Spring Boot shows a property and its value in the report of a failed binding. A failed
  conversion of a version key reports the entry before it, which is a secret. A map of strings cannot fail to bind.
- Folio encrypts only private keys with the master key. Retired keys have no ciphertext.
- `POST /api/v1/admin/crypto:reencrypt` encrypts again each key that does not use the active version. Thus after one
  run, all encrypted data uses the active version. It uses one
  transaction for each row, with `update … where master_key_version = <version read>`. Thus parallel runs and
  repeated runs are safe.
- `GET /api/v1/admin/crypto` gives the number of keys for each version. The list has each configured version and each
  stored version.
- A run that fails, for example on a ciphertext that does not decrypt, keeps the rows that it already changed. Its
  audit record has the keys that it moved and `"complete": false`.
- A key that does not decrypt is an unexpected error (status 500). The log names the key and its credential.
  `:replace` on that credential erases the key that does not decrypt, and the next run can complete.
- The test configuration and `bootTestRun` have their own master keys. `application.yaml` has none, so a deployment
  must set them.

**Rotation procedure:**

1. Add the new version on all instances. Keep the old version active.
2. Make the new version active.
3. Run the re-encryption.
4. When the old version has zero keys, remove it.

Do each step in a different deployment. *Reason:* an instance cannot decrypt a key under a version that it does not
have. SSH keys and Git services do not change during rotation.

*Reason for no KMS in v1:* a KMS is an external dependency that each deployment must operate. The version on each row
is the location for an envelope-encryption scheme later.

---

## 12. HTTP API

The consumption routes are under `/api/v1/configsets`. The administration routes are under `/api/v1/admin`. Anonymous
`GET` and `HEAD` requests can get to the consumption routes, so that `public` rules can apply. The services authorize
each request.

### 12.1 Consumption

| Route | Response | Needs |
|---|---|---|
| `GET /api/v1/configsets:resolve?path=…` | `{id, path}` | `READ` |
| `GET /api/v1/configsets/{id}` | `{id, path, description, latestRevision}` | `READ` |
| `GET /api/v1/configsets/{id}/revisions` | `{revisions: [{id, publishedAt}]}`, newest first, maximum 100 | `READ` |
| `GET /api/v1/configsets/{id}/files?revision=…` | `{revision, files: [{path, size}]}` | `READ` |
| `GET /api/v1/configsets/{id}/files/{*path}?revision=…` | Raw bytes | `READ` |

- The `catalog` module serves `:resolve`, because it owns paths and their authorization (section 7.4). The `content`
  module serves the other routes.
- `revision` is `latest` (the default) or a revision ID. If the ID is not valid, Folio gives status 400.
- Folio refuses a file larger than `folio.content.max-file-size` (default 100 MB) with status 422, before it opens the
  file. Folio streams files, so this limit protects bandwidth, not memory. Folio keeps files of 3 MB or less in memory
  for validation (section 9).
- The media type comes from the file extension:
  - `application/yaml` for YAML.
  - `application/json` for JSON.
  - `application/toml` for TOML.
  - `text/plain` for properties and INI.
  - `application/octet-stream` for all other files.
- Folio sends no charset. Responses have `X-Content-Type-Options: nosniff`.
- File lists and file reads have the header `X-Folio-Revision`. File reads also have `X-Folio-Validation-Status`.
- The metadata has `latestRevision: null` if the ConfigSet has no published revision. Only reads of `latest` give
  `NOT_PUBLISHED_YET`. The revision list is then empty.
- An empty `revision` value is the default, `latest`.
- The Spring Security firewall rejects some file names in a URL with status 400, for example a name with `;` or an
  encoded `%` or `/`. Folio cannot serve such files. Git repositories for configuration rarely contain them.

**Cache headers**

- Exact revisions: `Cache-Control: max-age=31536000, immutable`. This is correct because published revisions do not
  change.
- `latest`, metadata and revision lists: `max-age` is `folio.content.latest-max-age` (default 30 seconds).
- If an anonymous caller can `READ` the ConfigSet, the response is `public`. If not, it is `private`.
- Public responses for exact revisions also have `s-maxage` equal to the latest max age. Thus shared caches revalidate.
  They stop serving the file soon after a `public` grant is removed.
- ETags:
  - For a file: the hash of its content, which is the Git blob ID.
  - For a file list: the revision.
  - For metadata and revision lists: a digest. The digest of the metadata covers the path, the description and the
    latest revision. The digest of a revision list covers each ID and time.
- Folio authorizes before it examines `If-None-Match`. Thus a 304 never bypasses a denial. A 304 has the `ETag` and
  the `Cache-Control` headers. A 304 for a file list or a file also has the `X-Folio-Revision` header. A 304 for a file
  reads none of the content, so it has no validation status.
- If the read of a file fails before the response starts, the error response has none of the headers of the file.
- Error responses have `Cache-Control: no-store`.

### 12.2 Administration

All routes in this table are under `/api/v1/admin`.

| Area | Routes |
|---|---|
| Namespaces | `POST /namespaces`, `GET /namespaces` (the top-level Namespaces that the caller can read), `GET /namespaces/{id}` (with the children that the caller can read), `PATCH /namespaces/{id}` (slug, description), `POST /namespaces/{id}:move`, `DELETE /namespaces/{id}` |
| ConfigSets | `GET /configsets/{id}`, `PATCH /configsets/{id}`, `POST /configsets/{id}:move`, `DELETE /configsets/{id}` |
| Rules | `GET …/{namespaces or configsets}/{id}/rules`, `PUT …/rules/{action}` with `{subjects}`, `DELETE …/rules/{action}`, `POST …/explain` with `{action, caller}` |
| Git | `POST /git/configsets`, `GET /configsets/{id}/git` (source and sync state), `POST /configsets/{id}/git:check`, `POST /configsets/{id}/git:sync` |
| Credentials | `POST /credentials`, `GET /credentials?namespaceId=…`, `GET /credentials/{id}`, `POST /credentials/{id}:regenerate`, `:activate`, `:discard`, `:replace`, `:disable`, `DELETE /credentials/{id}` |
| Crypto | `GET /crypto`, `POST /crypto:reencrypt` |

- A ConfigSet is created only through the route of its Source. In v1, this is `/git/configsets`. Thus each Source
  validates its own definition, and the catalog does not depend on Source types.
- Routes that are specific to a Source have the Source type in the path: `/<type>/configsets` to create, and
  `/configsets/{id}/<type>` for the Source definition and its operations. Routes for all ConfigSets have no type.
  *Reason:* each Source has a different definition and different operations. One route with a body for each type
  would need a registry of types in the catalog.
- A delete of a rule that does not exist succeeds.
- `POST /credentials` takes `{namespaceId, name, gitInstance}` and gives status 201. `:activate` and `:discard` take
  `{"keyId": "key_…"}`. Each credential response has `id`, `namespaceId`, `namespacePath`, `name`, `gitInstance`,
  `status` and `keys`. Each key has `id`, `status`, `publicKey` (the OpenSSH line) and `fingerprint`. A response
  never contains a private key or a ciphertext. `GET /credentials?namespaceId=…` gives `{credentials: [...]}` in name
  order.
- `GET /crypto` gives `{activeVersion, versions: [{version, keys}]}`. `POST /crypto:reencrypt` gives the same fields
  and `reencrypted: [{version, keys}]`, the number of keys that the run moved from each version.
- `PATCH` changes the fields in the body. An absent field stays the same. A null or blank `description` clears it.
- `:move` of a Namespace takes `{"parentId": "ns_…"}`. A null or absent `parentId` moves it to the top level. `:move`
  of a ConfigSet takes `{"namespaceId": "ns_…"}`.
- `GET …/rules` gives the own rules of the resource, not the inherited rules. An explanation shows the rule that
  decides.
- `explain` takes `{"action": …, "caller": {"subject": …, "groups": […], "applicationId": …}}`. A null or absent
  `caller` is an anonymous caller. The response has `allowed`, `reason` (`SUPER_ADMIN`, `GRANTED`, `NOT_GRANTED` or
  `NO_RULE`), `matchedSubject`, and the deciding `rule` with its `resourceId` and `path`. The rule has no
  `subjects`, because it is often on a Namespace above, and a list of the rules there needs `GRANT` there. Folio gives
  the described caller the super-admin status that `folio.super-admins` gives to a token with the same claims. The
  request cannot set it.
- `GET /namespaces` gives `{namespaces: [...]}`, the top-level Namespaces that the caller can read, in slug order. Each
  item has the shape of a child in `GET /namespaces/{id}`. An anonymous caller gets the Namespaces that `public`
  permits. A caller with `READ` only deeper in the tree gets an empty list. The UI start page shows such grants
  (section 7.4). *Reason:* the route stays simple and has the shape of the children list. A route for deep grants can
  come later, for example for the CLI, without a change to this route.
- springdoc generates the OpenAPI document for the `/api` routes at `/v3/api-docs`. Future SDKs and the `folio` CLI
  use it as their contract. Folio does not serve Swagger UI, because Swagger UI needs inline styles that the UI content
  security policy blocks.

### 12.3 Web UI

The web UI is part of v1. It serves people who use Folio and people who administer it. It also lets the team test each
slice by hand during development.

**Technology.**

- Folio renders HTML on the server with JTE templates in Kotlin (`.kte`). The build compiles the templates, so a
  template error stops the build.
- htmx updates parts of a page. Folio serves a pinned copy of htmx. There is no JavaScript build and no CDN.
- The UI routes are at `/`. The API routes stay at `/api`. *Reason:* one application, one deployment and one
  authorization path. A browser application would need a second build and its own token handling.

**Pages.**

| Who | Pages |
|---|---|
| Users with `READ` | Browse the tree. View a ConfigSet: its ID, path, description and latest revision (on the files page). List revisions. List the files at a revision. View a file as text with its validation status. Download a file. |
| Administrators with `MANAGE` | Create, rename, describe, move and delete Namespaces and ConfigSets. Connect a Git repository, run the check, request a sync and view the sync state. Manage credentials and keys. |
| Administrators with `GRANT` | View and edit rules. Explain a decision. |
| Super admins | View master-key status. Run re-encryption. |

**Rules.**

- UI controllers call the same module services as the API controllers. They contain no domain logic. Thus the UI and
  the API always agree.
- Each module serves its own pages. The `web` module gives the layout and the shared fragments.
- The services authorize each operation. The UI also calls `Access.allows` to hide operations that the caller cannot
  do. Hiding is for convenience, not for security.
- A form page needs the action of its operation, not `READ` (section 7.1). The rules page and the explain form need
  `GRANT`. The edit, move and delete forms need `MANAGE` on the resource. The create form needs `MANAGE` on the
  parent.
- Each form uses CSRF protection. htmx sends the CSRF token in a header.
- The UI shows file content as escaped text. It never renders a file as HTML. A download has
  `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff`.
- Each UI response has a strict content security policy: `default-src 'self'`, no inline scripts and
  `frame-ancestors 'none'`.
- The UI shows errors with the same `code` and `detail` as the API (section 8.6).
- `/sign-in` starts the sign-in, and a `POST` to `/sign-out` ends the session. The layout links to them. A failed
  sign-in shows the error page with `SIGN_IN_FAILED` (section 8.6). It does not start a new sign-in, because that
  could fail in the same way.
- The start page `/` is the tree browser of the catalog. It shows the top-most Namespaces and ConfigSets that the
  caller can read, each with its full path (section 7.4), and a form that opens a ConfigSet by path. In a path and in
  the breadcrumbs, only the resources that the caller can read are links. The catalog service `StartService.entries`
  makes the list. The API route `GET /namespaces` lists only the top-level Namespaces (section 12.2). For a super
  admin, the two lists are the same. `/me` shows the subject, groups and application ID of the caller, which
  rules name. The layout links to it.
- If a page needs an authenticated caller and the caller is anonymous, Folio sends the browser to `/sign-in`. After
  sign-in, Spring Security sends it back to the page. This applies to `GET` pages, not to the API. Anonymous form
  posts fail the CSRF check first.
- If a form fails with an expected error, the page shows the form again with the values, the `code` and the
  `detail`. The response has the status that the API gives for the error.
- The explain form uses `GET`, because it changes nothing. Thus it needs no CSRF token.
- A page view by an anonymous caller makes no session, except when Folio sends the browser to sign-in. Then the
  session keeps the page for the return after sign-in. Only a signed-in caller gets a CSRF token in the page.
- The layout loads a pinned htmx file with an `integrity` attribute. Thus the browser refuses a different file.
- The `content` module serves the content pages: `/configsets/{id}/revisions`, `/configsets/{id}/files?revision=…`,
  `/configsets/{id}/files/{path}?revision=…` (the file as escaped text) and `/configsets/{id}/download/{path}?revision=…`
  (the attachment). The catalog does not depend on `content`, so the ConfigSet page links to the files and revisions
  pages by path only. The files page of `latest` names the latest revision, and its links go to that exact revision.
  The UI shows text only for a file of 3 MB or less, because Folio keeps only these files in memory (section 9). For a
  larger file, the page offers the download. The file page shows the content as UTF-8 text. The download gives the raw
  bytes, and Folio does not validate them.
- The `credential` module serves the credential pages: `/namespaces/{id}/credentials` (the list and the create form),
  `/credentials/{id}` (the keys, the public key lines to copy and the forms of the operations) and `/crypto` (the
  master-key status and the re-encryption, for super admins). The catalog does not depend on `credential`, so the
  Namespace page and the start page link to these pages by path only.

---

## 13. Validation

- The status is `VALID`, `INVALID` or `UNKNOWN`. The file extension selects the format. Case is not important.
  - `.yaml` and `.yml`: YAML.
  - `.json`: JSON.
  - `.properties`: properties.
  - `.toml`: TOML 1.0.
  - `.ini`: INI, with the rules of the Python `configparser` module in its default strict mode.
  - All other files: `UNKNOWN`.
- Validation never stops sync or reads. *Reason:* Git is the source of truth. Folio reports what it found. A parse and
  a new serialization would change comments, order and format.
- Folio uses maintained parsers that have resource limits and never run code.
  - SnakeYAML reads only parser events, so Folio keeps no node graph in memory. A node graph uses about 60 times the
    size of the file. Folio checks the limits of the SnakeYAML composer on the events: nesting deeper than 50
    collections, an alias without an earlier anchor in its document, and more than 50 aliases to collections (alias
    bombs). It rejects non-standard `!!` tags, which are tags in the `tag:yaml.org,2002:` namespace such as Java class
    names. Other global tags and local tags are labels and stay valid.
  - Jackson reads exactly one JSON value.
  - `Properties.load` reads properties files.
  - The Jackson TOML module reads TOML files. The build adds the module, and the Jackson BOM of Spring Boot sets its
    version.
  - INI has no standard, and the Java INI libraries are not maintained. Folio has a small INI checker. It accepts
    `[section]` headers, `key = value` and `key: value` entries, `#` and `;` comments and indented continuation lines.
    It rejects an entry before the first section, a duplicate section and a duplicate key in a section. These rules
    match `configparser`, which most Python users use. The INI checker reads UTF-8. A file that is not UTF-8 is
    `INVALID`. The checker scans each line without regular expressions, so its time is linear in the size of the
    file.
- Files over 3 MB are `UNKNOWN`.
- Folio calculates the status when a file is read, and sends it as a header. File lists do not show the status,
  because that would parse each file.
- One `ConfigFormat` enum lists the formats. There is no validator interface. To add a format, add one enum entry and
  one parser call. `.cfg` stays `UNKNOWN`, because many unrelated formats use that extension.

---

## 14. Audit

- Folio records these operations, each in the transaction of the operation:
  - Namespace and ConfigSet: create, describe, rename, move, delete.
  - Rule: put, delete.
  - Git source: create.
  - Credential: create, regenerate, activate, discard, replace, disable, delete.
  - Master-key re-encryption: one record for each run, with the number of keys for each version.
  - Manual sync requests.
  - Sync results.
- Folio records sync results only when they change. Otherwise each interval would write a row.
  - `SYNC_REVISION_PUBLISHED`: a sync published a new revision.
  - `SYNC_FAILED`: the failure code is different from the code of the previous attempt.
  - `SYNC_RECOVERED`: the first success after a failure.
- Folio does not record these:
  - Reads, explanations and checks.
  - Denied requests and requests with a conflict.
  - Operations that change nothing, for example a delete of a missing rule or a disable of a disabled credential.
- Each record contains:
  - The time, from the database clock.
  - The actor: `AUTHENTICATED` with the subject and application ID, `ANONYMOUS`, or `SYSTEM`.
  - If the actor was a super admin. `AuditLog.record` reads it from the `Caller`, so no module passes it.
  - The resource type and UUID.
  - The path after the operation, or before the operation for a delete. Renames and moves also have the previous path.
  - A JSON object with details.
- A master-key re-encryption record has no resource UUID and no path. Its operation is `MASTER_KEYS_REENCRYPTED`.
  Its resource type is `MASTER_KEY_RING`. Its
  details have `activeVersion`, `reencrypted` (the number of keys for each previous version) and `complete`. A run that
  moves no key changes nothing, so it writes no record.
- A credential record has the resource type `CREDENTIAL`, the UUID of the credential and the path of the Namespace
  that owns the credential. A credential is not in the tree, so its own path does not exist. The operations are
  `CREDENTIAL_` with `CREATED`, `REGENERATED`, `ACTIVATED`, `DISCARDED`, `REPLACED`, `DISABLED` and `DELETED`. The
  details of a key change list each key whose status changed: its API ID, its fingerprint, its status and its previous
  status. A create and a delete also give the Namespace ID, the name and the Git instance.
- A record never contains keys, ciphertext, secrets, tokens, transport output or file content. Folio does not store
  groups.
- The table has no foreign keys, because records stay after their resources are deleted. It has an index on
  `(resource_id, occurred_at)`, and an index on `occurred_at` for retention.
- The module that does an operation names its operation and resource type as strings, for example
  `NAMESPACE_RENAMED` and `NAMESPACE`. Thus a new audited operation changes one module only (section 8.5).
- The catalog records `NAMESPACE_` and `CONFIG_SET_` with `CREATED`, `RENAMED`, `DESCRIBED`, `MOVED` and `DELETED`.
  A `PATCH` that changes the slug and the description writes two records, one for each operation. Thus a query for
  one operation finds all of them. Rule writes are `RULE_PUT` and `RULE_DELETED`, with the resource type, the ID and
  the path of the target of the rule. Their details have the action and the subjects before and after.
- **Retention.** `folio.audit.retention` has the default 90 days. Each instance deletes old records one time each day,
  in batches of 1,000, with `FOR UPDATE SKIP LOCKED`. Thus instances never wait for each other. If a deployment needs
  records for longer, it exports them to a log store.
- v1 has no API to read records. Operators query the table.

---

## 15. Operations

### 15.1 Metrics and traces

Folio records meters with Micrometer. Micrometer sends the same meters to each configured backend:

- **Prometheus.** The actuator endpoint `prometheus` serves the meters for a scrape. This is on by default.
- **OpenTelemetry.** The OTLP registry pushes the meters to an OpenTelemetry collector. This is off by default.
  Turn it on with `management.otlp.metrics.export.enabled` and the collector URL.
- **Traces.** Micrometer Tracing with the OpenTelemetry bridge records spans for HTTP requests, database calls and
  fetches. It exports them with OTLP. This is off by default (sampling probability 0).

Folio code uses only the Micrometer API. Thus a change of backend is a change of configuration and dependencies, not
of code.

Folio's meters have no IDs, paths, subjects or commits as tags. Thus the number of series stays small. Spans can
contain a ConfigSet ID, but never a path, a subject, a token or file content.

| Meter | Type | Tags |
|---|---|---|
| `folio.sync.attempts` | counter | `outcome` (`published`, `unchanged`, `failed`), `code` |
| `folio.sync.fetch.duration` | timer | none |
| `folio.content.fetches` | counter | `result` (`fetched`, `missing`, `busy`, `too_large`, `failed`) |
| `folio.authorization.decisions` | counter | `action`, `result`, `reason` (`super_admin`, `rule`, `not_granted`, `no_rule`) |
| `folio.git.cache.size` | gauge, bytes | none. The cache sweep measures it. |
| `folio.git.cache.repositories` | gauge | none |

Spring's `http.server.requests` gives the latency and status of content reads.

### 15.2 Actuator

- Folio exposes only `health` and `prometheus`. They are on a different management port (default 8081). The
  application port never serves them.
- Deployments keep the management port off public networks. Probes use the management port.
- *Reason for a different port:* the default is safe. If a deployment does not protect the port, the metrics are still
  not on the public API port.

### 15.3 Configuration and JVM

- Configuration uses immutable `@ConfigurationProperties` data classes. Folio validates them at startup.
- Each limit has a default. Change a limit only for a measured reason.
- The JVM runs with compact object headers: `-XX:+UseCompactObjectHeaders`. *Reason:* smaller objects use less memory
  and cache, and Java 25 supports the flag for production.

---

## 16. Persistence

Folio uses PostgreSQL 18, Spring `JdbcClient` with plain SQL, and Flyway migrations. Never edit a merged migration.

| Table | Main columns | Notes |
|---|---|---|
| `namespace` | `id`, `parent_id`, `slug`, `description` | `unique nulls not distinct (parent_id, slug)` also covers the top level |
| `config_set` | `id`, `namespace_id`, `slug`, `description`, `source_type` | `unique (namespace_id, slug)`. The foreign key to `namespace` does not cascade. |
| `rule` | `id`, `namespace_id` or `config_set_id`, `action`, `subjects text[]` | One target only. One rule for each target and action. One or more subjects. The rule is deleted with its target. A GIN index on `subjects` serves the start page (section 7.4). |
| `credential` | `id`, `namespace_id`, `git_instance`, `name`, `status` | `unique (namespace_id, name)`. The foreign key to `namespace` does not cascade. Keys are deleted with their credential. |
| `credential_key` | `id`, `credential_id`, `status`, `public_key`, `fingerprint`, encryption columns | Partial unique indexes for one `ACTIVE` key and one `PENDING` key. `id` has no default, because Folio gets it first (section 5.2). `public_key` has no comment. Folio adds the API ID when it shows the line. |
| `git_source` | `config_set_id`, `credential_id`, `repository_path`, `branch`, `root_path`, sync state, lease | Deleted with its `config_set`. The foreign key to `credential` does not cascade. |
| `git_revision` | `config_set_id`, `commit_id`, `published_at` | Deleted with its `git_source`. The statement that records a sync writes it. |
| `audit_event` | `id`, `occurred_at`, actor, resource, path, `details jsonb` | No foreign keys |
| `spring_session`, `spring_session_attributes` | The schema of Spring Session JDBC | UI sessions (section 6.2). Flyway creates them, not Spring Session. |

The unique constraints on slugs examine only the current values. A rename or a move updates one row and keeps the ID,
so no other table changes. Section 5.3 gives the rules.

---

## 17. Testing

- **Pure logic** has unit tests without Spring. Examples: the nearest-rule algorithm, slugs and paths, item paths,
  validation (also the INI checker), and encryption with known answers.
- **Modules** have tests through their public interface and HTTP routes, with PostgreSQL in Testcontainers.
- **Content** tests use the in-memory `ConfigSource` adapter. Thus tests of authorization, revisions, cache headers and
  validation need no Git.
  - The adapter is in the test sources. Tests publish revisions through it, and they can make it fail with a
    `ContentException`.
  - The adapter registers for `SourceType.GIT`, because the catalog has no other type. The Git slice must decide how
    the in-memory adapter and the Git adapter share the type in tests. One option is a `SourceType` for tests only.
    Another option is that content tests use the Git adapter with the SSH Git server.
- **Git** tests use an SSH Git server in Testcontainers. The test builds the image from a pinned `alpine` image with
  `openssh` and `git`. Thus the test controls the host key and the authorized keys, and needs no third-party image.
  The tests include these cases:
  - A rejected host key and a wrong key.
  - A missing branch and a missing root path.
  - The deadline and the size limit.
  - Leases with two pollers.
  - Folio ignores an SSH config, a key and a `known_hosts` file in the SSH home directory.
- **UI** tests use MockMvc with a signed-in test session. They examine the status, the important elements and the
  CSRF protection. v1 has no browser tests.
- **Local development.** `bootTestRun` starts PostgreSQL, the SSH Git server and a mock OIDC provider in
  Testcontainers. Thus a developer can sign in to the UI and test each slice without a real identity provider. The
  mock provider is `ghcr.io/navikt/mock-oauth2-server` at a pinned version. One test signs in through it.
- **Tokens in tests.** The other integration tests use an issuer in the test process. Thus a test can sign a token
  with any claims, key, issuer or expiry time, and Folio validates it with its production decoder.
- Tests that need Docker have `@Tag("integration")`.
- A modularity test verifies the module boundaries. It also verifies that no class in a `web` package and no
  controller depends on `CredentialKeys`.

---

## 18. Later work

### 18.1 Database Source (next)

This section shows that a second Source can use the interface in section 9 without a change.

```text
db_revision      (id, config_set_id, number, published_at, published_by, message)
db_blob          (hash primary key, bytes)
db_revision_item (revision_id, path, blob_hash)
```

- `latest` is the highest `number`.
- `revisions` reads `db_revision`.
- `list` and `read` join through `db_revision_item`.
- The ETag is the blob hash.
- `read` can stream the blob from the database, or load a small blob and give a `ByteArrayInputStream`. The interface
  permits both.

Edits add an interface. Only Sources that permit edits implement it.

```kotlin
interface EditableConfigSource : ConfigSource {
    /** Publishes a new revision. Fails with a conflict if [base] is no longer latest. */
    fun publish(configSet: ConfigSetId, base: RevisionId?, changes: List<ItemChange>, caller: Caller): RevisionId
}
```

The database Source also needs these items:

- A `WRITE` action.
- The creation route `POST /api/v1/admin/db/configsets`.
- `SourceType.DATABASE`.

### 18.2 Other possible changes, and their location

| Change | Location |
|---|---|
| Webhooks | A route in `git` that makes a ConfigSet due immediately. Polling stays, to correct missed events. |
| `ls-remote` before fetch | In the fetch of `git` |
| HTTPS Git | In `git`, with a new type of credential |
| More than one identity provider in one deployment | In `identity`, and an issuer column on rules. The migration uses the current issuer as the default. |
| A provider without groups in its tokens | A directory adapter in `identity` (section 6.6) |
| Content edits in the UI | Pages in `content` that call `EditableConfigSource` |
| `folio` CLI and SDKs | Clients of the OpenAPI contract. People use the device flow. |
| Rules at the root | A root resource in `catalog`, so that people other than super admins can do top-level work |
| Finer actions | Split `MANAGE`. A migration copies the rules. |
| Import of credentials, KMS, RSA keys | In `credential` only |
| One cache for all ConfigSets of a repository | In `git` only |
| Bundles, views, composition, push, Spring Cloud Config | Built on `content`, above the Source interface |
| API to read audit records | In `audit` |
| An API list of the top-most readable resources (deep grants) | A new route in `catalog` that serves `StartService.entries`. `GET /namespaces` does not change. |

---

## 19. Stack

| Topic | Selection |
|---|---|
| Language and runtime | Kotlin, Java 25, Spring Boot 4.1 |
| Threads | Virtual threads. `Semaphore` for limits. No thread pools for blocking work. |
| JVM | Compact object headers (`-XX:+UseCompactObjectHeaders`) |
| Modules | One Gradle module. Spring Modulith application modules, verified by a test. |
| Persistence | PostgreSQL 18, `JdbcClient`, plain SQL, Flyway. No JPA, no Spring Data, no jOOQ. |
| Security | Spring Security OAuth2 resource server (JWT) for the API. OAuth2 client and Spring Session JDBC for the UI. |
| UI | JTE templates in Kotlin, htmx. No JavaScript build. |
| Google directory | Google Auth Library for Application Default Credentials. Spring `RestClient` for the Cloud Identity API. |
| Git | JGit with its Apache sshd transport. No system `git` binary. |
| Crypto | JDK only for Folio's crypto. Bouncy Castle only as the Ed25519 provider of sshd. |
| Leases | `SELECT … FOR UPDATE SKIP LOCKED`. No ShedLock. |
| API documentation | springdoc OpenAPI |
| Checks | ktlint and detekt stop the build on a finding. |

---

## 20. Build order

Each slice is one branch and one pull request. Each slice keeps the build green. Each slice adds the UI pages for its
own operations, so the team can test the slice by hand. If a slice changes a decision, it updates this document.

1. **Foundation.** The build, `web` (API IDs, errors, the UI layout), `identity` (tokens, browser sign-in, sessions,
   the token directory, `Caller`), the mock OIDC provider for local runs, `audit` (record, retention), the
   actuator, virtual threads and the modularity test.
2. **Catalog.** Namespaces, ConfigSets, paths, resolve, rules, super admins, decisions, explanations and moves. Until
   slice 5, a test fixture creates ConfigSets.
3. **Credentials.** Credentials that Namespaces own, keys, delete, encryption, the master-key ring and re-encryption.
4. **Content.** The Source interface, the in-memory adapter, the consumption API, cache headers and validation.
5. **Git reads.** Instances and host keys, isolation, onboarding, the check, the cache and reads of published
   revisions.
6. **Git sync.** Polling, leases, backoff, fetch limits, fetch on demand, the cache sweep and sync audit records.
7. **Google Workspace.** The `google` directory, the account restrictions and the group cache.
8. **Metrics and traces** (Prometheus and OTLP), and a final review of logs, limits and documentation.

---

## 21. Decisions

This list gives the decisions in this document. Do not open a decision again without new information. Add each new
decision here with its reason.

| Decision | Reason | Section |
|---|---|---|
| Revisions are snapshots of a ConfigSet. Only published revisions are readable. | One model for Git and database Sources. Consumers see only what Folio recorded. | 5.5, 9 |
| Stable, prefixed IDs. Paths are only for discovery. | Renames and moves never break consumers. | 5.2, 5.3 |
| The last path segment is always the ConfigSet. | Namespaces and ConfigSets can share slugs, and paths stay clear. | 5.3 |
| No cascade when a Namespace is deleted. | Folio never removes a ConfigSet that consumers use by accident. | 5.4 |
| One issuer for each deployment, configurable claims, mandatory audience. | Most OIDC providers need only configuration. | 6.1 |
| A directory gives the groups: the token, or Google Cloud Identity. | Google tokens have no groups. Other modules see only the `Caller`. | 6.3, 6.5 |
| Folio never continues without groups when the directory fails. | A missing group can change a decision. | 6.5 |
| The caller is an explicit parameter. | Background work and tests need no security context. | 6.1 |
| Folio does not query LDAP or AD. | The identity provider joins directories. | 6.3 |
| Three actions. | Less for policy authors to learn. A later split is easy. | 7.1 |
| The nearest rule decides. Grants only. Rules have one or more subjects. | Decisions are predictable and easy to explain. No hidden deny. | 7.2 |
| Super admins come from configuration, and Folio examines them first. | A recovery path that rules cannot lock out. | 7.2 |
| A move with rules needs `GRANT` at the target. | A move of rules is a policy change at the target. | 7.3 |
| 403 for an ID. The same 404 or 401 for all hidden paths. | Nobody can guess an ID. A person can guess a path. | 7.4 |
| The catalog is one module with one tree lock. | Moves, deletes and rules share one invariant. | 8.1 |
| Each module writes its own audit records. | A change to one operation changes one module. | 8.5 |
| Virtual threads and semaphores. | Blocking code stays simple and cheap. | 8.7 |
| The Source interface exists in v1. | Database Sources come next. The in-memory adapter tests through the interface. | 9 |
| The Source definition does not change after creation. | Published revisions stay immutable. | 10.2 |
| Git instances and host keys are in configuration. Folio trusts host keys exactly. | Security review at deployment time. | 10.1 |
| The environment cannot change SSH behaviour. Git loggers are off. | Nobody can redirect or expose stored keys. | 10.4 |
| Each instance has its own cache. The database is the source of truth. | Each instance can serve, and a lost cache costs one fetch. | 10.5 |
| Leases with `SKIP LOCKED`, a wall-clock deadline and a size limit. | Time, disk and duplicate work have limits. | 10.6, 10.7 |
| Fetches on demand have limits. | Anonymous readers cannot cause unlimited fetches. | 10.8 |
| Namespaces own credentials. Use of a credential needs `MANAGE` on its Namespace. | Delegation without super admins, and no misuse of a credential by a third party. | 7.3, 11.1 |
| Folio generates Ed25519 keys. Rotation has two steps. | Private keys never leave Folio. | 11.1 |
| HKDF and AES-GCM under a versioned ring, with associated data for each row. | No external KMS. Rotation without downtime. | 11.2, 11.3 |
| Folio always serves raw content. Validation is metadata. | Git is the source of truth. A parse can change content. | 13 |
| Audit records in the transaction, results only on change, 90-day retention. | Records are complete, limited in size and without noise. | 14 |
| The actuator has its own port. | The default is safe. | 15.2 |
| Compact object headers. | Less memory for each object. | 15.3 |
| A server-rendered web UI (JTE, htmx) in v1, for users and administrators. | One deployment and one authorization path. People can test each slice by hand. | 12.3 |
| UI sessions in PostgreSQL. The API accepts only bearer tokens. | Each instance serves each session. CSRF protection applies only to the UI. | 6.2 |
| PostgreSQL makes all IDs with `uuidv7()`. | One ID for each object in all layers, and one function makes it. | 5.2 |
| Source reads give a stream that the adapter closes. | Large files do not use memory. The seam is easy to change now and difficult later. | 9 |
| Only Ed25519 keys in v1. | All target services accept them. One key type gives one code path. | 10.4 |
| A credential can be deleted only when no Git source uses it. | A ConfigSet never loses its credential. | 11.1 |
| Micrometer with Prometheus by default and OTLP by configuration. | One API in code. Each deployment selects its backend. | 15.1 |
| Folio serves the OpenAPI document, but not Swagger UI. | The UI content security policy blocks the inline styles of Swagger UI. | 12.2 |
| The catalog serves resolve. `SourceType` is in the public API of the catalog. | The catalog owns paths and stores the Source type. Content depends on the catalog. | 9, 12.1 |
| A `PATCH` of slug and description writes two audit records. | Each audited operation has one name, so a query finds all of its records. | 14 |
| An anonymous browser goes to sign-in and back to the page. | People see the page that they asked for, not an error. The API keeps its 401. | 12.3 |
| Folio runs behind a TLS proxy. It trusts forwarded headers only from internal addresses, and the session cookie is `Secure`. | Sign-in needs an `https` redirect URI, and the session cookie never goes over plain HTTP. | 6.2 |
| Identity decides the super-admin status when it makes the `Caller`, and the `Caller` carries it. Audit records and `Access` read it from the `Caller`. | Each module that audits or does master-key work needs the status. The other modules depend on `identity`, so only `identity` can compute it for all of them. | 6.1, 7.2, 8, 14 |
| `identity` owns subjects: their format and one matcher for rules and super-admin entries. | Rules and super-admin entries use one format. One matcher cannot disagree with a copy. | 7.2, 8 |
| An explanation gives the described caller the super-admin status of the configuration. | The explanation must match what a token with the same claims gives. | 7.4, 12.2 |
| `Access.requireSuperAdmin` guards master-key operations. It is the check for `MANAGE` at the root. | The catalog already owns the 403 and 401 errors of authorization. A master-key operation and a top-level create then give the same errors. | 7.3, 8.1 |
| A UI session keeps the super-admin status from sign-in. | The same rule as for groups. A request needs no lookup. | 6.2 |
| The start page shows the top-most resources that the caller can read, with their full paths. Slugs that the caller cannot read are plain text. | A caller with `READ` only deep in the tree finds its resources, as in GitLab. Design 7.4 already shows the path of a visible resource. | 7.4, 12.3 |
| `GET /api/v1/admin/namespaces` lists only the top-level Namespaces that the caller can read. | The route stays simple and has the shape of the children list. A route for deep grants can come later. | 12.2 |
| `@SnapshotRead` gives each read that authorizes its transaction, in each module. | One policy has one name. A read with the wrong isolation compiles, so each site must not spell it. | 8.1 |
| A failed sign-in is an error with the code `SIGN_IN_FAILED`. One failure handler logs each cause. | One error style for the UI and the API. Spring logs these failures only at debug levels. | 8.6, 12.3 |
| A credential stores its Git instance name with the slug rules only. The Git check reports a name that the configuration does not have. | `credential` must not depend on `git`, which owns `folio.git.instances`. A deployment can also remove an instance later. | 11.1 |
| The catalog finds credentials of a Namespace with SQL on the `credential` table. The foreign key changes a parallel credential create into `NAMESPACE_NOT_EMPTY`. | The catalog does not depend on `credential`, and the delete never removes a credential. | 5.4 |
| `Namespaces.find` and `Namespaces.require` are in the public API of the catalog. | The credential module needs the path of the owner for its pages and audit records. `require` finds and authorizes under one tree state, so a catalog commit cannot fall between the two steps. `find` mirrors `ConfigSets.find`. | 8.1 |
| `CredentialKeys.withPendingKey` exists next to `withActiveKey`. | The check tests a pending key before the activation (section 10.3). Only the credential module reads key rows. | 8.3, 10.3 |
| A credential audit record has the path of the Namespace that owns the credential. | A credential has no path of its own. The path shows the owner of the credential. | 14 |
| A re-encryption run that moves no key writes no audit record. A failed run records the keys that it moved. | Operations that change nothing write no record. Each change has a record. | 11.3, 14 |
| The credential pages are in `credential`. The Namespace page links to them by path. | Each module serves its own pages, and the catalog does not depend on `credential`. | 12.3 |
| Folio validates the master-key ring in a bean, not in the binding of `folio.crypto`. The versions bind as strings, and the bean parses them. | Spring Boot can show property values in the report of a failed binding. A failed conversion of a version key reports the previous entry, which is a secret. | 11.3 |
| The Git check has no `NO_ACTIVE_KEY` code. Each credential always has one active key. | Create and replace activate a key, and activate swaps keys in one transaction. Without a cause that can occur, the code would be dead interface. | 10.3, 11.1 |
| A write outside the catalog that authorizes takes the shared tree lock in `Access`. Read-only reads stay without a lock. | A `READ COMMITTED` decision reads the ancestry and the rules in two statements. Without the lock, it could combine a tree and rules that never existed together. | 5.4, 8.1 |
| A key that does not decrypt gives an `IllegalStateException` that names the key and the credential. | Callers never see crypto types. The operator can find the credential to replace, and a failed re-encryption can then complete. | 8.3, 11.3 |
| Content finds and authorizes in one snapshot, and calls the adapter outside a transaction. | A decision uses one snapshot. A slow read or a fetch holds no database connection. | 8.2 |
| A revision ID has 1 to 64 lowercase letters and digits, and is never `latest`. | Content rejects a malformed value with 400 before it finds the ConfigSet. An ID needs no escape in a URL. | 9, 12.1 |
| Content selects the adapter at the time of a read. Two adapters for one type stop the start. | The main code has no adapter until the Git slice, and Folio must still start. | 9, 20 |
| The metadata shows `latestRevision: null` before the first publish. Only reads of `latest` give `NOT_PUBLISHED_YET`. | A consumer can see that the ConfigSet exists but has no content yet. | 5.5, 12.1 |
| The content pages are in `content`. The ConfigSet page links to them by path, and the files page names the latest revision. | Each module serves its own pages, and the catalog does not depend on `content`. | 12.3 |
| The UI shows text only for files of 3 MB or less. | Folio keeps only these files in memory. A larger file is a download. | 9, 12.3 |
| The in-memory adapter is in the test sources and registers for `SourceType.GIT`. | It is for tests only (section 4.1). The catalog has no other type until the Git slice decides. | 17 |
| YAML validation reads parser events and checks the limits of the composer itself. | A node graph uses about 60 times the size of the file. Each read of a file of 3 MB or less validates it again. | 13 |
| The INI checker reads UTF-8 only. | `configparser` uses the locale encoding, which Folio cannot know. UTF-8 is the common encoding of configuration files. | 13 |
| `SourceFailed` (502) and `SourceTimedOut` (504) are two errors. | Each expected failure has one subclass and one kind (section 8.6). A flag would hide the choice at the call site of an adapter. | 9 |
