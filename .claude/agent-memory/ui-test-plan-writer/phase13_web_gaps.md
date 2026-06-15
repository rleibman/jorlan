---
name: phase13-web-gaps
description: Phase 13 (Email/Calendar) web frontend gaps — OAuth, invokeTool, toolEvents all absent from web client
metadata:
  type: project
---

Phase 13 added startOAuth, revokeOAuth, invokeTool, oauthStatus, listOAuthProviders GraphQL ops and a
toolEvents subscription on the server, but the Scala.js web client (JorlanClient.scala) has none of them.

**Why:** The shell client (shell/src/.../JorlanClient.scala) was fully updated; the web client was not.

**How to apply:** Any web UI test for OAuth or email/calendar will fail at the client binding level.
There is no OAuth page, no OAuth status widget, no invokeTool UI, and the oauth=success/?oauth=error
query-param redirect from OAuthRoutes is silently ignored by JorlanWebApp.
