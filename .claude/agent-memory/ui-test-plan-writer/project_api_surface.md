---
name: project-api-surface
description: Jorlan has no traditional UI; primary external API is Caliban GraphQL at server/src/main/scala/jorlan/graphql/JorlanAPI.scala
metadata:
  type: project
---

Jorlan exposes its entire control-plane via a Caliban GraphQL API. There is no frontend UI.

Test plans must target GraphQL queries, mutations, and subscriptions — not browser screens.

Key files:
- `server/src/main/scala/jorlan/graphql/JorlanAPI.scala` — all queries, mutations, subscriptions
- `server/src/main/scala/jorlan/graphql/JorlanRoutes.scala` — HTTP wiring for the GraphQL endpoint
- Authentication: Bearer token via `AuthServer`; session injected as `JorlanSession` into the ZIO env

**Why:** No web frontend exists. Test plans written as screen-based UI tests would be wrong.
**How to apply:** Frame all test cases as GraphQL operations (queries/mutations/subscriptions) or as unit/integration test layer assertions.
