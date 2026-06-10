<!--
 Copyright (c) 2026 Roberto Leibman - All Rights Reserved
-->

# Phase 11: Telegram Connector Design

**Branch:** `phase11/telegram-connector` (planned)
**Status:** Design — 2026-06-05
**Date:** 2026-06-05

See `doc/mini-designs/plugin-architecture.md` for the cross-phase model this phase first
implements. Phase 11 builds the **foundational plugin seam** (`Skill` / `ConnectorSkill` traits +
reusable ingress pipeline) and uses **Telegram as the first `ConnectorSkill`**. The full
`SkillRegistry`, manifest validation, and ReAct tool dispatch remain Phase 12.

---

## 1. Problem Statement

Users can only reach Jorlan through the shell and the GraphQL API. Phase 11 adds Telegram: a user
sends a message to the bot, it is resolved to a canonical `User`, and an agent processes it and
replies — including in group chats and channels. In doing so we lay the connector seam every future
connector (Slack, email, SMS) will reuse.

---

## 2. Pre-work: Rename `Skill` record → `SkillRecord`

Pure rename, no semantic change. `SkillId`, `SkillVersion`, `SkillTier`, `SkillStatus`,
`ConnectorInstance` all stay. This frees the name `Skill` for the runtime trait.

- `model/src/main/scala/jorlan/domain/skill.scala` — `case class Skill` → `SkillRecord`.
- `model/src/main/scala/jorlan/repository.scala` — `SkillRepository.getById` / `upsert` / `search`
  return types and the `SkillSearch` sort enum references.
- `db/src/main/scala/jorlan/db/repository/QuillRepositories.scala` — Quill query mappings.
- Grep `\bSkill\b` to catch any GraphQL / test references. Leave `SkillVersion`, `SkillId`,
  `SkillTier`, `SkillStatus` untouched.

---

## 3. Runtime Trait Seam (connector-api)

New file `connector-api/src/main/scala/jorlan/connector/Skill.scala` with `Skill`, `ConnectorSkill`,
`SkillDescriptor`, `ToolDescriptor`, `InvocationContext` exactly as specified in
`plugin-architecture.md` §3. No registry yet — just the contract Phase 12 will populate.

---

## 4. Reusable Ingress Pipeline

### 4.1 New domain types

```scala
// model/src/main/scala/jorlan/domain/ingress.scala

/** A connector-normalized inbound message, before identity resolution. */
case class InboundMessage(
                           channelType: ChannelType,
                           channelUserId: String, // channel-native sender id (Telegram numeric user id, as String)
                           chatRef: String, // Telegram chat id; equals channelUserId for private chats
                           chatKind: ChatKind, // Private | Group | Channel | Supergroup
                           content: String,
                           receivedAt: Instant,
                         ) derives JsonEncoder, JsonDecoder

enum ChatKind derives JsonEncoder, JsonDecoder {
  case Private, Group, Channel, Supergroup
}

/** Policy for an inbound message whose sender does not resolve to a known, verified user. */
enum UnrecognizedIdentityPolicy derives JsonEncoder, JsonDecoder {
  case Reject, Quarantine
}
```

`ChannelType.Telegram` already exists (`domain/user.scala`) — no enum change needed.

### 4.2 `MessageIngress` service

The connector-agnostic path every future connector reuses.

```scala
// connector-api/src/main/scala/jorlan/connector/MessageIngress.scala
trait MessageIngress {
  def receive(
    msg: InboundMessage,
    unrecognizedPolicy: UnrecognizedIdentityPolicy = UnrecognizedIdentityPolicy.Reject,
  ): IO[JorlanError, Unit]
}
```

Server impl (`server/.../service/MessageIngressImpl.scala`) does:

1. **Resolve identity** — `UserRepository.userByChannelIdentity(msg.channelType, msg.channelUserId)`.
2. **Unrecognized policy** — if `None` (or unverified), apply the connector's
   `UnrecognizedIdentityPolicy`: `Reject` (drop + event log) or `Quarantine` (log-only in Phase 11, no DB).
3. **Authorize** — capability gate `agent.message` for the resolved user (existing
   `CapabilityEvaluator`).
4. **Session** — resolve-or-create the `AgentSession` for `(user, chatRef)`; one durable session per
   Telegram chat so conversations persist across restarts. The chatRef filter is pushed to the DB query.
5. **Dispatch** — `AgentRunner.processMessage(sessionId, msg.content, Some(userId))`
   (signature: `(AgentSessionId, String, Option[UserId]) => IO[JorlanError, Unit]`).
6. **Event log** — write the inbound receipt with the resolved `sessionId`.

**Reply path deferred to Phase 12**: The agent's streamed reply was originally intended to be
forwarded by `TelegramConnectorSkill` (via `AgentRunner.subscribe`) back to the originating chat.
This path was not implemented in Phase 11. It is deferred to the Phase 12 `NotificationRouter`, which
will dispatch outbound messages to `ConnectorSkill` egress by `channelType`. See P11-001 in the phase
review.

---

## 5. Telegram Bot API Client (server)

`TelegramApiClient` over `zio-http`, behind a trait so tests inject a fake (no live token in CI):

```scala
trait TelegramApiClient {
  def getUpdates(offset: Long, timeoutSeconds: Int): IO[JorlanError, List[TelegramUpdate]] // long-poll

  def sendMessage(chatId: String, text: String): IO[JorlanError, Unit]

  def sendPhoto(chatId: String, photo: Array[Byte], caption: Option[String]): IO[JorlanError, Unit]

  def sendDocument(chatId: String, file: Array[Byte], filename: String): IO[JorlanError, Unit]
}
```

`TelegramConfig` parsed from the bound `ConnectorInstance.configJson`:

```scala
case class TelegramConfig(
                           botToken: String,
                           allowedChatIds: Set[String], // empty = allow all (subject to identity policy)
                           allowedUserIds: Set[String],
                           unrecognizedPolicy: UnrecognizedIdentityPolicy,
                           useWebhook: Boolean = false, // long-poll first; webhook is a later toggle
                         )
```

`botToken` lives only in `configJson` (already redacted by `ConnectorInstance.toString`).

---

## 6. `TelegramConnectorSkill extends ConnectorSkill` (server)

- `connectorType = Telegram`, `instanceId = <bound ConnectorInstance>`.
- **`start`** — fork a long-poll loop: `getUpdates(offset, 30)` → `TelegramMessageNormalizer` →
  `MessageIngress.receive`. Advance `offset`. Handle `chatKind` (private/group/channel/supergroup)
  so the bot works in groups and channels (roadmap item). For groups, gate on `allowedChatIds`.
- **`stop`** — interrupt the polling fiber.
- **`invoke`** — egress tools, each gated by capability `telegram.send` (RiskClass `ExternalEffect`):
    - `telegram.send_message` `{ chatId, text }`
    - `telegram.send_photo`   `{ chatId, photo, caption? }`
    - `telegram.send_file`    `{ chatId, file, filename }`
- `descriptor` lists those three `ToolDescriptor`s with their JSON schemas + `telegram.send`.

`TelegramMessageNormalizer`: `TelegramUpdate` → `InboundMessage` (maps Telegram `chat.type` →
`ChatKind`, `from.id` → `channelUserId`, `chat.id` → `chatRef`).

---

## 7. Minimal `ConnectorManager` + Boot Wiring

The first sliver of the future registry — enough to run connectors before Phase 12:

```scala
trait ConnectorManager {
  def startAll: IO[JorlanError, Unit] // start every registered ConnectorSkill's ingress

  def stopAll: IO[JorlanError, Unit]
}
```

- Wire `TelegramConnectorSkill.live` + `MessageIngressImpl.live` + `TelegramApiClient.live` in
  `server/src/main/scala/jorlan/EnvironmentBuilder.scala`.
- In `server/src/main/scala/jorlan/Jorlan.scala`, fork `ConnectorManager.startAll` as a daemon
  alongside the existing `TriggerEngine` startup (mirror that pattern).

Outbound `NotificationRouter` (Phase 12) will dispatch to `ConnectorSkill` egress; for Phase 11 the
reply path in §4.2 calls `telegram.send_message` directly. Documented as a seam.

---

## 8. Migration V024 (chatRef column)

V024 adds `chat_ref VARCHAR(128)` and index `idx_agent_session_chat_ref` to the `agentSession` table
for durable connector-bound sessions. Note: V023 was already used by the scheduler index fix in a
prior phase; the actual migration is **V024**, not V023 as originally planned in this document.

`Quarantine` persistence is log-only in Phase 11 (no DB table). A persistent `quarantined_inbound`
table can be added in a future phase if needed.

---

## 9. Tests

- `TelegramMessageNormalizerSpec` — update JSON → `InboundMessage` for private/group/channel/supergroup.
- `MessageIngressSpec` — identity resolution hit; `Reject` vs `Quarantine` miss; capability gate;
  resolve-or-create session; dispatch to `AgentRunner`. Uses `InMemoryRepositories` + a fake
  `AgentRunner`.
- `TelegramConnectorSkillSpec` — `start`/`stop` lifecycle and egress `invoke` via a fake
  `TelegramApiClient` returning canned `getUpdates`.
- Integration test driving the long-poll loop end-to-end against the mock client (no live token).
- Follow existing conventions: `ZLayer.make`, `ZIOSpec` rules, fake gateways (see prior phases).

---

## 10. Roadmap Checklist Mapping

Each `doc/development_roadmap.md` Phase 11 item maps as: Bot API → §5; `TelegramConnector` →
§6; `MessageNormalizer` → §6; identity resolution → §4.2; unrecognized policy → §4.1/§4.2/§7;
NotificationRouter outbound → §7 (seam); channel config → §5; mock-API tests → §9; groups/channels
→ §6. Check each `[x]` as it lands.

---

## 11. Resolved Decisions

1. `ConnectorSkill extends Skill`; Telegram is the first concrete `ConnectorSkill`.
2. `Skill` record renamed `SkillRecord` (pre-work, §2).
3. Foundational seam only — `SkillRegistry` + ReAct dispatch are Phase 12.
4. MCP / SKILL.md import are Phase 17; only the trait seam is built now.
5. Tier-driven isolation — Telegram is Tier-0 BuiltIn, in-process.
