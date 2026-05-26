---
name: Jorlan Codebase Patterns
description: Recurring idioms, duplication hotspots, and conventions confirmed across Phases 0-2 code review
type: project
---

## Confirmed Conventions
- Braces required (`-old-syntax`, `-no-indent`), `-Yexplicit-nulls`, `-Werror`
- Opaque Long-backed IDs in `model/domain/ids.scala` — 19 types, each identical except name
- Domain enums use `.values.find(_.toString == s).toRight(...)` for JSON decoding — 12 enums all identical pattern
- All Quill repositories: every method ends with `.provideLayer(ds).mapError(RepositoryError(_))`
- `onConflictUpdate` tuples use multi-line `(t, e) =>` style (Quill macro requirement)
- `RepositoryTask[A] = IO[RepositoryError, A]` is the universal ZIO repo effect alias
- ZIO repo traits are thin aliases: `trait XxxZIORepository extends XxxRepository[RepositoryTask]`
- `QuillRepositories.live` constructs all 9 repos from one `QuillCtx`, then fans out into ZLayer per repo type

## Duplication Hotspots
1. **ids.scala** — 19 identical companion object bodies; only the type name varies. Each needs 6 lines. A macro or typeclass approach can't be used due to opaque type limitations, but a code-comment noting the pattern is sufficient.
2. **Enum JSON decoders** — 12 enums, each has the same 3-line decoder body. Could use a shared inline helper.
3. **Quill repo methods** — Every method wraps with `.provideLayer(ds).mapError(RepositoryError(_))`. A private helper method `run[A](q: => Task[A])` in a base class could eliminate this repetition.
4. **`onConflictUpdate` formatting** — multi-line tuple style is forced by Quill's inline macro; not a style choice.

## Bugs / Design Notes Found
- `ChannelIdentity.id` is typed as `UserId` but is the PK of the channel_identity table — should logically be its own opaque ID type (flagged as design question in review)
- `Permission.id` is typed as `RoleId` — same concern
- `EventLogRepository.search` applies `from`/`to` filters in-memory after SQL `take(limit)` — this means date filtering happens on an already-truncated result set; the limit applies before the date filter (potential correctness bug)
- `ConfigurationServiceImpl.appConfig` is a `lazy val` on a `new ConfigurationService { ... }` — calling `.appConfig` twice returns the same cached `IO`, which is fine since `orDie` makes it a `UIO` internally but the declared type is `IO[ConfigurationError, AppConfig]`

## Simplification Patterns Applied / Suggested
- Shared `runQ` helper in a base Quill repo class would reduce per-method boilerplate significantly
- Inline helper `enumDecoder[E <: reflect.Enum](valueOf: String => E)` could unify all 12 enum decoders
- `QuillRepositories.live` ZLayer fan-out could use `ZLayer.succeedEnvironment(ZEnvironment(...))` to avoid 9 separate `ZLayer.succeed` concatenations
