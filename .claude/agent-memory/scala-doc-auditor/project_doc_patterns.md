---
name: project-doc-patterns
description: Recurring ScalaDoc patterns, confirmed architectural facts, and verified config keys for Jorlan
metadata:
  type: project
---

## Recurring gaps
- `object Foo { val live: URLayer[...] }` ZLayer values are consistently undocumented across all service objects.
- Case class `@param` coverage is incomplete — some fields get docs, many do not.
- Methods in skill classes (SchedulerSkill, MemorySkill pattern) tend to have no ScalaDoc on individual public methods.

## Confirmed architectural facts
- Header/info split does NOT apply in Jorlan (this is a DMScreen pattern). Jorlan uses direct case class columns in MariaDB via Quill.
- All domain types use ZIO effects (`IO[JorlanError, _]`, `UIO[_]`, `URIO[R, _]`).
- Repository pattern: abstract traits in `model/`, ZIO concrete implementations in `db/`.
- `SchedulerZIORepository` is the ZIO-fixed alias for `SchedulerRepository[IO[RepositoryError, *]]`.
- TriggerEngine runs as a daemon fiber started from `Jorlan.run`.
- Caliban GraphQL is the primary external API. Capability gate for scheduler: `scheduler.manage`.
- `JobStatus` variants: Pending, Running, Succeeded, Failed, Cancelled, Paused.
- `TriggerType` variants: Cron, Interval, OneShot, Event (Event-type triggers are no-ops in TriggerEngine.advanceTriggers).

## V-migration latest
- V021: scheduler extensions (retry, lease, userId columns).
- V022: user.email NOT NULL; backfills with `<displayName>-<id>@jorlan.internal`.
- V023 is next.
