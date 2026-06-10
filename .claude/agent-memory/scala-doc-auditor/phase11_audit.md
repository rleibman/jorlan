---
name: phase11-audit
description: Documentation audit results for Phase 11 Telegram Connector branch — gaps, implementation drifts, and verified facts
metadata:
  type: project
---

Audit run: 2026-06-07. Branch: phase-11/telegram.

## Key findings (all verified against source)

1. `UnrecognizedIdentityPolicy` not consulted — `MessageIngressImpl.handleUnrecognized` always drops/logs (Reject behavior) regardless of `TelegramConfig.unrecognizedPolicy`. Class-level doc at line 23 says "Applies UnrecognizedIdentityPolicy" but the implementation ignores it.
2. Reply routing not implemented — phase11 mini-design §4.2 describes subscribing to the session stream and forwarding the completed agent response to `telegram.send_message`. No such routing exists in `MessageIngressImpl` or `TelegramConnectorSkill`. The doc implies the reply path works; the code relies on Phase 12 NotificationRouter.
3. `useWebhook` field unimplemented — `TelegramConfig.useWebhook = true` is accepted but `TelegramConnectorSkill` always long-polls. The `@param` doc is accurate about intent but never honored at runtime.
4. Phase placement drift — mini-design §3 and §4.2 place `Skill`/`MessageIngress` in `model/src/main/scala/jorlan/service/`. Actual implementation uses a new `connector-api` module (`connector-api/src/main/scala/jorlan/connector/`). CLAUDE.md module table omits `connector-api`.
5. Migration number wrong in mini-design §8 — doc says "V023 is the next migration number" for a potential quarantine table. V023 was used for scheduler index fixes (P10); session_chat_ref landed in V024.
6. `TelegramApiClient` trait methods `getUpdates`, `sendMessage`, `sendPhoto`, `sendDocument` — no `@param` ScalaDoc on any of them.
7. `Skill.descriptor` and `Skill.invoke` — no `@param`/`@return` on the individual method declarations (class-level doc covers intent).
8. `SkillDescriptor` case class — three fields (`name`, `tier`, `tools`) have no `@param`.
9. `ConnectorManager.empty` val — undocumented.
10. `TelegramApiClientLive.make` — no `@param`/`@return` doc.
11. `TelegramConnectorSkill.make` — no `@param`/`@return` doc.
12. `MessageIngress.receive` companion accessor — trivial delegation, no doc needed (correct).

## Verified accurate facts
- Long-poll timeout is 30 seconds (hardcoded in pollLoop, not from config).
- `filterUpdates` applies `allowedChatIds` first, then `allowedUserIds`.
- `TelegramMessageNormalizer.normalize` falls back to `ChatKind.Private` for unknown `chat.type` strings.
- `resolveOrCreateSession` searches up to 100 sessions via `AgentSessionSearch(pageSize=100)`.
- V024 adds `chatRef VARCHAR(255) NULL` + index on `(userId, chatRef)`.
- `ConnectorManager.fromSkills` doc accurately states Phase 12 replaces it.
- `FakeTelegramApiClient` drains updates in batches of 10 — matches `splitAt(10)` in `getUpdates`.
