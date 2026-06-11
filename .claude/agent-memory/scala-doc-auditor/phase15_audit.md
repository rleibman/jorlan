---
name: phase15-audit
description: Audit results for Phase 15 web frontend; key ScalaDoc gaps, stubs, and doc issues identified
metadata:
  type: project
---

Audit of `web/` module and `server/.../StaticFileRoutes.scala` on branch `phase-15/webfrontend`.

**Key findings:**

- `JorlanWebApp`, `AppRouter`, `AppShell`, `ClientConfiguration`, and all nine page objects lack ScalaDoc.
- `AppRouter.AppPage` enum has no variant-level docs and no `hash`/`label` field docs.
- `ScalaJSClientAdapter` is undocumented; `makeWebSocketClient` has 14 parameters with no `@param` docs; `WebSocketHandler` trait has no doc.
- `ApiClientSttp4.withAuth` has no `@param` docs and an unexplained `onAuthError` default (auto-reload on any auth failure).
- `ClientConfiguration` class and `live` singleton have no doc; the conditional port logic is non-obvious.
- `JorlanClient` has no object-level ScalaDoc; `Formality` type alias and `CapabilityGrantView.approvalMode: String` inline comment `// String or Enum?` signals unresolved design ambiguity.
- Debug `println` calls remain in `ScalaJSClientAdapter` (6 instances) — acceptable in production Scala.js only if redirected to the browser console, but these go to STDOUT, which is a no-op in browser context.
- `AppRouter` hash-change listener is a placeholder (`useEffect(Callback.empty)`): routing breaks on browser back/forward — documented nowhere.
- `SessionsPage` Terminate button wired to `Callback.empty` — a known stub, undocumented.
- `ChatPage.streamBuffer` is tracked in state but `agentResponseStream` WebSocket subscription is never wired up — streaming display is dead code.
- `JORLAN_WEB_ROOT` env var not present in `.env.example`.
- `manual-testing-guide.md` has no Phase 15 section and its "Last updated" header still reads Phase 11.
- README `application.conf` default for `jorlan.web.root` is documented as `/opt/jorlan/www` in the server endpoints table but the actual default in `application.conf` is `"debugDist"`.

**Why:** Phase 15 was a large new module; documentation was deferred.
**How to apply:** All items above are candidates for the phase review fix pass.
