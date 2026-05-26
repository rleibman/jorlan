---
name: User Profile
description: Roberto Leibman — project owner and lead developer of Jorlan
type: user
---

Roberto Leibman is the sole developer and owner of the Jorlan project. He is an experienced Scala 3 / ZIO 2 engineer who:
- Enforces strict compiler flags: `-Yexplicit-nulls`, `-Werror`, `-old-syntax`, `-no-indent`
- Uses functional style throughout; no mutable state, no null in domain code
- Uses opaque types for all IDs (type-safety over brevity)
- Prefers ZIO for all effects with explicit error types
- Uses Quill for DB, Caliban for GraphQL, zio-http for HTTP
- Wants `sbt --error` always used to reduce output
