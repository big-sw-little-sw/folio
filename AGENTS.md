# Agent instructions

Read `docs/folio-design.md` before each task. It decides scope, names and design. If a task needs something that
the design does not include, stop and ask. If you change a decision, update the design in the same pull request.

## Design rules

These rules come from John Ousterhout and Matt Pocock. Section 3 of the design gives the reasons. Use these checks
when you write or change code:

- **Depth.** A module gives much behaviour through a small interface. If callers must call several parts in the
  correct order, move that order into the module.
- **Deletion test.** If you delete a module and its complexity goes away, the module only passed calls through.
  Remove it. If the complexity goes to many callers, keep the module.
- **Seams.** Add an interface only when two adapters exist, or when the design names a near change. A test adapter
  counts as an adapter. Do not add interfaces, flags or parameters for possible changes.
- **Locality.** Put code for one concept in one module. Do not split a concept across layers or modules.
- **Information hiding.** Do not let callers see details such as Git, SQL or crypto. Do not use pass-through methods.
- **Errors.** Remove errors by design where possible. Keep the remaining errors few and specific.
- **Tests.** Test through the public interface of the module. If a test must change when the implementation changes,
  rewrite the test.

## Java runtime

- Use Java 25.
- Use virtual threads. Set `spring.threads.virtual.enabled: true`. Spring then runs requests, scheduled tasks and
  async work on virtual threads.
- Do not pool virtual threads. To limit concurrency, use a `Semaphore`, not a fixed thread pool.
- Do not keep large or long-lived values in `ThreadLocal`. Each virtual thread gets its own copy.
- Run the JVM with compact object headers: `-XX:+UseCompactObjectHeaders`. Set the flag for `bootRun`, for tests and
  in the container image.

## Kotlin

- Follow the official Kotlin coding conventions. ktlint (`ktlint_official`) and detekt enforce them.
- Prefer immutable data: `val`, data classes and read-only collections.
- Use sealed interfaces and exhaustive `when` for closed sets of alternatives.
- Use value classes for IDs inside a module. Use plain `UUID` and `String` at the HTTP and SQL edges.
- Use null safety. Do not use `!!`. Do not use `lateinit` outside tests.
- Use scope functions only when the intent is clear, for example `?.let` for a null check. Do not nest them.
- Use extension functions for mapping at module edges, not for domain logic.
- Use constructor injection only.

## Writing: ASD-STE100 in spirit

Apply these rules to documentation, code comments, commit messages, and error and log messages. Use the rules where
they make the text clearer. Do not apply the STE dictionary strictly: common technical words are permitted.

- Write one topic in each sentence.
- Keep sentences short: 20 words or fewer for instructions, 25 words or fewer for descriptions.
- Keep paragraphs to six sentences or fewer.
- Use the active voice. Say who or what does the action.
- Use simple tenses: present, past and future.
- Write instructions in the imperative.
- Use one word for one meaning. Use the terms in the design glossary. Do not use synonyms for them.
- Use simple verbs, not phrasal verbs. For example, write "find", not "find out".
- Keep the articles "a", "an" and "the".
- Use vertical lists for steps and for more than three items.

In code comments, state intent, invariants, limits and surprising choices. Do not repeat what the code says.
