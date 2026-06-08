---
name: phase11-telegram
description: Phase 11 Telegram Connector — key components, test surfaces, and gaps to cover
metadata:
  type: project
---

Phase 11 implements the first ConnectorSkill. Key components:

- `connector-api/src/main/scala/jorlan/connector/` — trait seam: Skill, ConnectorSkill, MessageIngress, InboundMessage, ChatKind, UnrecognizedIdentityPolicy
- `telegram/src/main/scala/jorlan/connector/telegram/TelegramConnectorSkill.scala` — long-poll loop, egress invoke (send_message/send_photo/send_file), allowedChatIds/allowedUserIds filtering
- `telegram/src/main/scala/jorlan/connector/telegram/TelegramApiClient.scala` — TelegramConfig, live impl, FakeTelegramApiClient
- `server/src/main/scala/jorlan/service/MessageIngressImpl.scala` — identity resolution, capability gate (agent.message), resolve-or-create session by chatRef, event log
- `server/src/main/scala/jorlan/service/ConnectorManager.scala` — startAll/stopAll; loaded from DB via EnvironmentBuilder
- `server/src/main/resources/sql/V024__session_chat_ref.sql` — adds chatRef column + index to agentSession

Connector config lives in `ConnectorInstance.configJson` (parsed as TelegramConfig). No GraphQL mutations exist yet for creating/updating ConnectorInstances (Phase 12 surface). The connector is loaded from DB rows at boot via EnvironmentBuilder.liveConnectorManagerLayer.

Egress tools: telegram.send_message, telegram.send_photo, telegram.send_file — all gated by capability `telegram.send`.

**Why:** Helps future test plan writers understand what already has unit tests vs what needs integration/manual coverage.
**How to apply:** When generating test plans for Phase 11+, prioritize MessageIngress pipeline, chatRef session persistence, connector lifecycle, and egress tool invocation.
