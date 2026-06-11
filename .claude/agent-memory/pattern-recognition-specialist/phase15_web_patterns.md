---
name: phase15-web-patterns
description: Architectural patterns and recurring smells in the Phase 15 web frontend (Scala.js + React + MUI)
metadata:
  type: project
---

## Module Architecture
- Entry: `Jorlan.scala` -> `JorlanWebApp` (thin delegation, correct)
- Auth gate: `JorlanWebApp` holds `useState(Option[Option[User]])` — triple-Option idiom for loading/unauthenticated/authenticated
- Routing: hash-based, `AppRouter` owns state, `AppPage` enum has `hash` + `label`
- Shell: `AppShell` wraps all pages — handles nav, logout, pending-approvals badge

## Dominant Anti-Pattern: Inline Adapter Construction
Every page (except SchedulerPage/ChatPage which extract `makeAdapter()`) constructs `ScalaJSClientAdapter(Uri.parse(...).fold(...), connectionId)` inline at the call site — duplicated 15+ times across the codebase. The URI construction string (`${if (https) "https" else "http"}://${host}/api/jorlan`) is repeated verbatim.

## Page Structure Pattern (followed by 7/9 pages)
- `case class State(...)` + `useState`
- `useEffectOnMountBy` fires one GraphQL query to load initial data
- Render: CircularProgress guard, then table/content
- Actions: inline `Callback { adapter.asyncCalibanCallWithAuth(...).flatMap(...).completeWith(...).runNow() }` closures

## GraphQL Client Pattern
- `JorlanClient` is hand-written (not generated) — phantom types for GraphQL schema types, SelectionBuilder composition
- `JorlanClientDecoders` provides `implicit val` codecs for all opaque IDs and enums
- `ScalaJSClientAdapter` owns HTTP (via `ApiClientSttp4`) and WebSocket handling

## ScalaJSClientAdapter Issues
- Is a `case class` (not a service/trait), extends `TimerSupport` (a React lifecycle mixin)
- Inner `WebSocketHandler` is built via `new WebSocketHandler { ... }` factory returning a concrete anonymous class
- `connectionState` is a plain `var` — mutable state outside React state management; not referentially transparent
- Reconnect logic, keep-alive, and message decoding all colocated — violates SRP

## `ClientConfiguration`
- `case class ClientConfiguration()` with only `val host: String` — should be an object or opaque value, not a parameterless case class
- Instantiated as `ClientConfiguration.live` but the `host` is recalculated each time the class is instantiated (reads `window.location` in the val body)

## MuiComponents Pattern
- Each wrapper is a standalone top-level `object` with an inner `Builder extends AnyVal with StBuildingComponent`
- Uses `implicit def make(companion: MuiXxx.type): Builder` conversion — non-obvious implicit; could trip up implicit resolution
- Uses string-typed prop setters (`set("variant", value)`) as fallback for props not in the type-safe builder — inconsistent within and across components
