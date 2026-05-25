# Software Design Description (SDD)
## Secure Agent Runtime and Orchestration Platform

Version: 0.1 Draft  
Status: Design Skeleton / Initial Architecture  
Date: 2026-05-24  
Primary Implementation Language: Scala 3  
Primary Runtime: JVM  
Database: MariaDB

---

# 1. Introduction

## 1.1 Purpose

This Software Design Description (SDD) defines the initial high-level design for a secure, multi-user, observable, extensible AI agent runtime and orchestration platform.

The design is intended to guide implementation by a development team or AI coding assistant. It builds on the previously defined Software Requirements Specification and executive summary, translating requirements into an initial technical structure while avoiding excessive implementation detail.

This document is intentionally a skeleton-level design. It establishes the major system components, boundaries, data flows, extension points, technology choices, and design principles. Detailed class design, database DDL, GraphQL schema definitions, and exact module APIs are deferred to implementation-specific design notes.

---

## 1.2 Scope

The system shall provide a secure runtime for AI agents capable of:

- Multi-user interaction
- Autonomous and human-supervised execution
- Scheduled task execution
- Skill and connector execution
- Capability-based permission enforcement
- Durable event logging and traceability
- Shared and user-specific memory
- Integration with LLMs, especially local models via Ollama
- Integration with external systems such as email, Google services, Telegram, Slack, shell, web search, Lyrion, and market data services

The first implementation shall target Linux and macOS. Microsoft Windows is explicitly out of scope for the initial implementation.

---

## 1.3 Relationship to Requirements

This document corresponds to the SRS for the Secure Agent Runtime and Orchestration Platform.

The SRS defines what the system shall do. This SDD defines the proposed structure for how the system will be organized.

---

## 1.4 Design Goals

The design is guided by the following goals:

1. Safety over unrestricted autonomy
2. Observable execution over opaque agent behavior
3. Strongly typed contracts over prompt conventions
4. Durable state over ephemeral context
5. Explicit permissions over implicit trust
6. Human supervision over uncontrolled automation
7. Composable skills over monolithic agents
8. Multi-user support from day one
9. Local-first operation where possible
10. Practical implementation in Scala using ZIO ecosystem libraries

---

## 1.5 Definitions

| Term | Definition |
|---|---|
| Agent | Runtime entity that uses models, tools, memory, and policies to perform work |
| User | A canonical account representing a human actor across one or more communication channels |
| Channel Identity | A Telegram account, email address, shell user, Slack user, phone number, or other external identity linked to a canonical user |
| Skill | A reusable unit of agent functionality with typed inputs, outputs, permissions, and execution behavior |
| Connector | A communication or external-system integration, such as Telegram, Gmail, Slack, Lyrion, or Google Calendar |
| Capability | A specific authority granted to an agent, skill, connector, user, or role |
| Role | A named set of permissions assigned to users |
| Resource Permission | A direct permission on a specific resource, independent of role assignment |
| Event Log | Durable append-only record of significant system activity |
| Memory Checkpoint | A summarized conversation or execution state committed into long-term memory |
| Shared Memory | Knowledge available to the system across users, subject to policy |
| User Context | Information about a particular user's preferences, history, active work, and channel identities |
| Scheduler | Durable subsystem responsible for delayed, recurring, and trigger-based execution |

---

# 2. System Overview

## 2.1 Architectural Summary

The system is a Scala-based, JVM-hosted agent runtime composed of a core orchestration service, durable persistence layer, permission system, scheduler, model gateway, memory subsystem, skill runtime, connector framework, and external API layer.

At a high level:

```text
Users / Channels
  -> Connectors
  -> Message Ingress
  -> Identity Resolution
  -> Agent Runtime
  -> Skills / Tools / Models / Memory / Scheduler
  -> Event Log + Persistent State
  -> Responses / Effects / Notifications
```

The system supports multiple users communicating through multiple channels. Every inbound message is associated with a canonical user before agent processing begins.

The runtime maintains per-conversation context temporarily. At configured checkpoints, or before/after meaningful external effects, the system summarizes relevant context and commits it to shared and/or user-scoped memory.

All significant actions are written to the event log.

---

## 2.2 Primary Architectural Style

The design combines:

- Modular service architecture
- Capability-secure execution
- Event-sourced tracing for auditability
- Relational persistence as source of truth
- Typed skill contracts
- Connector-driven message ingress
- GraphQL-first external API surface
- ZIO-based functional effect system

The platform shall avoid hidden prompt-driven behavior where structured contracts are possible.

---

## 2.3 Runtime Boundaries

The system is divided into the following major boundaries:

| Boundary | Responsibility |
|---|---|
| Core Runtime | Agent orchestration, planning, execution, state transitions |
| Permission Kernel | Capability grants, role checks, approval policies |
| Skill Runtime | Skill registration, validation, execution, sandboxing |
| Connector Framework | External communication systems and service integrations |
| Memory System | User context, shared memory, vector retrieval, checkpoints |
| Scheduler | Durable delayed, recurring, and trigger-based execution |
| Model Gateway | LLM provider abstraction and routing |
| Persistence Layer | MariaDB-backed durable state and event logging |
| API Layer | GraphQL, HTTP, shell, and connector-facing interfaces |
| Observability Layer | Logs, traces, metrics, replay support |

---

# 3. Design Constraints and Technology Choices

## 3.1 Implementation Language

The system shall be implemented primarily in Scala 3.

Scala 3 is selected because it supports:

- Strong type modeling
- Functional effect systems
- JVM ecosystem access
- Safe domain modeling
- Good fit with ZIO

---

## 3.2 Runtime Platform

The primary runtime shall be the JVM.

Scala Native and Windows support are not initial targets.

Linux and macOS shall be the initial supported operating systems.

---

## 3.3 Core Libraries

The initial library stack shall include:

| Library | Purpose |
|---|---|
| ZIO | Effect system, concurrency, scheduling primitives, resource management |
| zio-json | JSON encoding/decoding |
| zio-http | HTTP server/client where appropriate |
| zio-test | Unit and integration testing |
| Caliban | GraphQL interface |
| Flyway | Database migration management |
| Quill | Database access layer |
| LangChain4j | LLM integration and model tooling where useful |
| Testcontainers | Integration testing with external systems |

---

## 3.4 Database

MariaDB shall be used as the primary relational database.

MariaDB shall serve as:

- Canonical state store
- Event log store
- Configuration store
- User and role store
- Skill and connector registry
- Scheduler state store
- Memory metadata store

MariaDB vector capabilities may be used for semantic indexing.

Vector indexes shall be treated as derived indexes, not canonical state.

---

## 3.5 Configuration Format

System configuration, skill manifests, connector manifests, and permission declarations shall use JSON.

All externally supplied configuration shall be schema-validated.

---

# 4. System Architecture

## 4.1 Component View

```text
+---------------------------------------------------------------+
|                        External Users                         |
| Shell | GraphQL Client | Telegram | Slack | Email | SMS | etc. |
+-------------------------------+-------------------------------+
                                |
                                v
+---------------------------------------------------------------+
|                      Connector Framework                      |
| Message ingress, outbound delivery, channel identity mapping   |
+-------------------------------+-------------------------------+
                                |
                                v
+---------------------------------------------------------------+
|                        API / Ingress Layer                    |
| GraphQL, shell adapter, connector API, internal service API     |
+-------------------------------+-------------------------------+
                                |
                                v
+---------------------------------------------------------------+
|                         Core Runtime                          |
| Agent sessions, planning, execution, context, intervention     |
+----------+-------------+------------+-----------+-------------+
           |             |            |           |
           v             v            v           v
+----------+--+   +------+-----+ +----+------+ +--+-------------+
| Permission |   | Skill      | | Scheduler | | Memory System  |
| Kernel     |   | Runtime    | |           | |                |
+----------+-+   +------+-----+ +----+------+ +--+-------------+
           |            |            |           |
           v            v            v           v
+---------------------------------------------------------------+
|                         Persistence Layer                     |
| MariaDB, event log, relational state, vector indexes, Flyway    |
+---------------------------------------------------------------+
```

---

## 4.2 Layered View

The system shall be organized into layers:

```text
Interface Layer
  GraphQL, shell, Telegram, HTTP, external connectors

Application Layer
  Commands, queries, workflows, use cases

Domain Layer
  Agent, skill, permission, memory, scheduler, user, role models

Infrastructure Layer
  MariaDB, Quill repositories, LLM providers, external APIs

Observability Layer
  Event log, audit trails, telemetry, replay
```

The domain layer shall not depend directly on connector-specific or database-specific implementations.

---

# 5. Multi-User and Identity Design

## 5.1 Canonical User Model

Each human user shall have one canonical user account.

A canonical user may be linked to multiple channel identities:

```text
Canonical User
  - Shell identity
  - Telegram account
  - Slack account
  - Email address
  - WhatsApp number
  - SMS number
```

All inbound messages must be resolved to a canonical user before agent execution.

---

## 5.2 Channel Identity Resolution

The identity subsystem shall map external channel identifiers to canonical users.

Unrecognized identities shall be rejected, quarantined, or routed to an onboarding/verification flow depending on connector policy.

For signed or cryptographically verified channels, the system shall validate signatures before accepting commands.

---

## 5.3 User Context

The system shall maintain user context including:

- Preferences
- Communication history summaries
- Active work
- Known projects
- Permission grants
- Role assignments
- Channel identities

User context shall be available to agents subject to permission and policy checks.

---

## 5.4 Shared Memory

The system shall support shared memory across users.

Shared memory may include:

- Summaries of completed work
- Decisions
- Project facts
- Organization-level knowledge
- Reusable observations
- Skill usage history
- Successful workflows

Shared memory shall be available to the system for decision-making, subject to policy constraints.

---

## 5.5 Memory Checkpointing

The runtime shall maintain ephemeral conversation context during interaction.

At configured checkpoint moments, the runtime shall summarize relevant context and commit it to memory.

Checkpoint triggers shall include:

- Before an external effect
- After an external effect
- At timed intervals
- At conversation end
- At explicit user request
- Before scheduled task creation
- After workflow completion

External effects include:

- Saving files
- Sending email
- Modifying calendar events
- Running shell commands
- Posting to external systems
- Making purchases
- Triggering device actions

The checkpointing system shall distinguish:

- User-specific memory
- Shared memory
- Workspace memory
- Private/sensitive memory
- Non-persisted transient context

---

# 6. Authorization and Permission Design

## 6.1 Role-Based Access Control

The system shall include a role system.

Users may be assigned one or more roles.

Roles shall define sets of permissions and capability grants.

Example roles:

- Administrator
- Developer
- Operator
- Auditor
- Standard User
- Guest
- Service Account

---

## 6.2 Resource-Specific Permissions

In addition to roles, the system shall support direct permissions on individual resources.

Examples:

- User A may read workspace X
- User B may approve shell commands for workspace Y
- User C may manage Telegram connector Z
- User D may invoke skill S

Resource-specific permissions shall override or refine role-based permissions according to explicit policy rules.

---

## 6.3 Capability-Based Runtime Permissions

Runtime actions shall require capabilities.

A capability grant shall include:

- Capability name
- Scope
- Grantee
- Grantor
- Approval mode
- Expiration
- Resource constraints
- Audit metadata

Example capability:

```json
{
  "capability": "filesystem.read",
  "scope": {
    "paths": ["/workspace/project-a"]
  },
  "approval": "once",
  "expiresIn": "24h"
}
```

---

## 6.4 Approval Modes

The system shall support approval modes:

| Mode | Meaning |
|---|---|
| denied | Operation is not allowed |
| per_invocation | Approval required every time |
| once | Approval required once for a defined scope |
| session | Approval valid for current session |
| timed | Approval valid until expiration |
| persistent | Approval remains until revoked |

---

## 6.5 Permission Evaluation Order

Permission evaluation shall consider:

1. Explicit denial
2. Resource-specific permission
3. Role-derived permission
4. Capability grant
5. Connector policy
6. Skill policy
7. Runtime default policy

Explicit denial shall take precedence over grants.

---

# 7. Skill System Design

## 7.1 Skill Tiers

The system shall support multiple tiers of skills.

| Tier | Skill Type | Trust Level |
|---|---|---|
| 0 | Built-in native Scala skill | Highest |
| 1 | Installed native Scala plugin skill | High, if trusted |
| 2 | Declarative JSON skill | Medium |
| 3 | Scripted sandbox skill | Low/medium |
| 4 | Imported MCP or external skill | Restricted |
| 5 | Agent-authored draft skill | Untrusted |

---

## 7.2 Native Scala Skills

Native Scala skills shall be implemented as Scala libraries.

They shall expose:

- Manifest metadata
- Input schema
- Output schema
- Capability requirements
- Handler implementation
- Version metadata
- Test metadata where available

---

## 7.3 Declarative JSON Skills

Declarative skills shall allow non-Scala users and agents to define functionality without writing Scala code.

Initial declarative skill types shall include:

- HTTP/API skill
- Prompt/template skill
- Workflow skill
- Query skill
- Command-template skill

Declarative skills shall be schema-validated before installation.

---

## 7.4 Agent-Authored Skills

Agents may propose new skills.

Agent-authored skills shall initially be created as draft skills.

Draft skills shall not execute with trusted privileges until they pass:

1. Schema validation
2. Static permission analysis
3. Sandbox validation where applicable
4. Test execution where applicable
5. Human approval

Agent-authored skills shall not silently acquire broad permissions.

The runtime, not the agent, shall grant authority.

---

## 7.5 Skill Lifecycle

```text
Draft
  -> Validated
  -> Permission Reviewed
  -> Tested
  -> Approved
  -> Active
  -> Deprecated / Revoked
```

---

## 7.6 External Skill Importing

The system shall support adapters for external skill ecosystems, including MCP-compatible tools and OpenClaw-like skill definitions.

Imported skills shall be translated into canonical internal manifests where feasible.

Imported skills shall default to restricted policies.

---

# 8. Connector Framework Design

## 8.1 Connector Responsibilities

Connectors shall handle:

- External communication
- Inbound message normalization
- Outbound message delivery
- Channel-specific identity resolution
- Connector-specific authentication
- Connector-specific rate limits
- Connector-specific event logging

---

## 8.2 Required Initial Connectors

The following connectors are required for the initial implementation:

1. Shell connector
2. GraphQL connector/API
3. Telegram connector

---

## 8.3 Planned Connectors

Planned connectors include:

- Slack
- PGP-protected email
- Gmail
- IMAP
- SMTP
- WhatsApp
- SMS
- Google Calendar
- Google Drive
- Google Contacts
- Lyrion music service
- Web search providers
- Market data providers

---

## 8.4 GraphQL-First Integration Model

The system shall expose a GraphQL API using Caliban.

GraphQL shall be the primary external programmatic API.

Connectors may communicate with the core application through:

- Direct in-process service interfaces
- GraphQL calls
- Local HTTP calls

For initial design, connector logic should target a common application service interface. GraphQL shall expose that same application service layer externally.

This avoids coupling internal connector execution exclusively to GraphQL while preserving GraphQL as the primary API surface.

---

# 9. Required Early Skills

## 9.1 Email Skill

The system shall support an email skill abstraction with provider implementations for:

- Gmail API
- IMAP
- SMTP
- Optional CLI-backed adapter such as Himalaya

The email skill shall support:

- List messages
- Search messages
- Read messages
- Draft messages
- Send messages
- Reply to messages
- Forward messages
- Archive/delete where supported
- Attachment metadata inspection

Sending, deleting, forwarding, and external effects shall require appropriate approvals.

Himalaya may be used as an adapter or bridge but shall not define the canonical internal email model.

---

## 9.2 Google Services Skill Group

The system shall support Google service integrations beginning with:

- Google Calendar
- Gmail
- Google Drive
- Google Contacts

Future integrations may include:

- Google Tasks
- Google Docs
- Google Sheets

External CLI tools such as gog may be used as adapters or bridges but shall not define the canonical internal Google service model.

---

## 9.3 Web Search Skill

The system shall support permissioned web search.

Capabilities shall be granular:

- web.search
- web.open_url
- web.download
- web.submit_form

The system shall not treat internet access as a single broad permission.

---

## 9.4 Lyrion Music Service Skill

The system shall support a Lyrion music service skill as an early personal/home automation example.

The skill may support:

- List players
- Query current playback
- Play
- Pause
- Stop
- Set volume
- Play playlist
- Schedule playback

Network access shall be scoped to configured local Lyrion endpoints.

---

## 9.5 Market Data Skill

The system shall support stock ticker and market data lookup.

Initial scope shall include:

- Read current quotes
- Read watchlists
- Create alerts
- Retrieve market news

Trading/execution actions are out of scope for the initial implementation or shall require per-invocation high-risk approval.

---

## 9.6 Scheduler Skill

The scheduler shall be exposed as a skill.

The scheduler skill shall support:

- Create task
- List tasks
- Cancel task
- Pause task
- Resume task
- Create trigger
- Modify trigger
- Run task manually

---

## 9.7 Filesystem and Workspace Skill

The system shall support scoped workspace access.

Capabilities shall include:

- workspace.read
- workspace.write
- workspace.search
- workspace.snapshot
- workspace.delete

Deletion shall require explicit approval by default.

---

## 9.8 Shell Skill

The system shall support controlled shell execution on Linux/macOS POSIX-compatible systems.

Shell access shall be capability-controlled and highly restricted by default.

---

## 9.9 Identity and Contacts Skill

The system shall support identity and contact resolution.

Capabilities shall include:

- identity.resolve_user
- identity.link_channel
- identity.verify_sender
- identity.list_aliases
- contacts.search
- contacts.read

---

## 9.10 Notification Skill

The system shall provide a connector-independent notification skill.

Capabilities shall include:

- notify.user
- notify.channel
- notify.group

Delivery providers may include Telegram, email, Slack, SMS, or other connectors.

---

## 9.11 Memory Skill

The system shall expose memory operations through a controlled skill.

Capabilities shall include:

- memory.remember
- memory.search
- memory.summarize_checkpoint
- memory.forget
- memory.mark_private
- memory.mark_shared

---

# 10. Shell Access Design

## 10.1 General Principle

Shell access is essential but dangerous.

The system shall treat shell execution as one of the highest-risk capabilities.

There shall be no global `shell: true` permission.

---

## 10.2 Supported Shell Environment

The initial implementation shall support POSIX-compatible execution on Linux and macOS.

The system shall not initially support:

- PowerShell
- cmd.exe
- Windows filesystem semantics
- Windows ACLs

---

## 10.3 Shell Capability Types

Shell capabilities shall include:

- shell.command.template.execute
- shell.binary.execute
- shell.script.execute
- shell.interactive.start
- shell.sudo.execute

The initial implementation should support non-interactive command-template and binary execution only.

Interactive shell and sudo execution shall be disabled by default.

---

## 10.4 Command Risk Classes

Shell commands shall be classified by risk.

| Class | Description | Examples |
|---|---|---|
| 0 | Read-only inspection | ls, cat, grep, find, git status, git diff |
| 1 | Workspace-local write | mkdir, touch, scalafmt, sbt test |
| 2 | Destructive workspace operation | rm, git clean, git reset, overwrite mv |
| 3 | External side effect | git push, curl POST, ssh, scp |
| 4 | Privileged/system operation | sudo, systemctl, chmod/chown outside workspace |
| 5 | Credential/payment/security-sensitive | gpg private keys, password managers, payment CLIs |

Higher-risk classes shall require stricter approval.

---

## 10.5 Structured Command Execution

The shell runtime shall prefer structured command execution:

```json
{
  "binary": "/usr/bin/sbt",
  "args": ["test"],
  "cwd": "/workspace/project",
  "timeoutSeconds": 600
}
```

Raw shell execution such as `bash -c` shall be disallowed by default or require per-invocation approval.

---

## 10.6 Shell Execution Trace

Every shell invocation shall record:

- User
- Agent
- Workspace
- Working directory
- Binary
- Arguments
- Environment policy
- Start time
- End time
- Exit code
- Stdout artifact reference
- Stderr artifact reference
- Approval ID
- Files changed if known

---

# 11. Scheduler Design

## 11.1 Scheduler Responsibilities

The scheduler shall support:

- One-shot tasks
- Recurring tasks
- Delayed tasks
- Retry policies
- Backoff policies
- Timeouts
- Missed-run handling
- Manual triggering
- Pause/resume/cancel

---

## 11.2 Trigger Types

The scheduler shall eventually support:

- Time-based triggers
- Cron-like triggers
- Event-based triggers
- Message-based triggers
- File-change triggers
- Webhook triggers
- Condition-based triggers

The initial implementation should focus on time-based, cron-like, and manual triggers.

---

## 11.3 Durable Scheduling

Scheduler state shall be stored in MariaDB.

Scheduled tasks shall survive runtime restarts.

Concurrent scheduler workers shall use database-backed locking or leasing to avoid duplicate execution.

---

# 12. Memory and Retrieval Design

## 12.1 Memory Types

The system shall distinguish:

- Ephemeral conversation context
- User memory
- Shared memory
- Workspace memory
- Skill memory
- Event log
- Semantic index

---

## 12.2 Canonical Memory Storage

Canonical memory records shall be stored relationally in MariaDB.

Semantic/vector indexes shall be derived from canonical records.

---

## 12.3 Vector Search

MariaDB vector capabilities may be used for semantic retrieval.

Vector search may support:

- Similar prior tasks
- Relevant user preferences
- Related shared memory
- Skill discovery
- Past error diagnosis
- Documentation retrieval

Embeddings shall be rebuildable from canonical records.

---

## 12.4 Shared Memory Risk

Because shared memory can incorporate information from multiple users, memory retrieval shall be governed by policy.

The system shall prevent unintended leakage of sensitive user-specific data into inappropriate contexts.

Open design issue: the exact boundary between shared memory, user memory, private memory, and workspace memory requires further refinement.

---

# 13. Model Gateway Design

## 13.1 Model Abstraction

The model gateway shall abstract over LLM providers.

The system shall support:

- Local models through Ollama
- Cloud-hosted models
- Multiple model configurations
- Model capability metadata

---

## 13.2 Model Capability Metadata

Model configurations shall describe:

- Provider
- Model name
- Context window
- Tool-calling support
- Structured-output reliability
- Cost characteristics where applicable
- Latency characteristics where known
- Recommended use cases

---

## 13.3 Deterministic Testing

The model gateway shall support fake or deterministic model providers for testing.

Recorded model-call fixtures may be used for integration and replay tests.

---

# 14. Persistence Design

## 14.1 Database Access

Quill shall be used for database access.

Flyway shall manage schema migrations.

---

## 14.2 Major Data Areas

The persistence layer shall include tables or equivalent relational structures for:

- Users
- Channel identities
- Roles
- Permissions
- Capability grants
- Agents
- Agent sessions
- Conversations
- Messages
- Memory records
- Memory embeddings
- Skills
- Skill versions
- Connector instances
- Scheduler jobs
- Scheduler triggers
- Event log
- Model calls
- Tool calls
- Shell executions
- Approval requests
- Approval decisions
- Artifacts
- Workspace metadata

---

## 14.3 Event Log

The event log shall be append-only from the perspective of normal runtime operations.

Events shall include:

- Event ID
- Timestamp
- Actor
- User
- Agent
- Session
- Correlation ID
- Event type
- Event payload
- Related resource IDs

---

## 14.4 Artifacts

Large outputs such as long logs, generated files, attachments, and model transcripts may be stored as artifacts.

Artifacts may be stored in the filesystem, object storage, or database-backed storage according to deployment configuration.

Artifact references shall be persisted in MariaDB.

---

# 15. API Design

## 15.1 GraphQL API

The system shall expose a GraphQL API using Caliban.

GraphQL shall support:

- User management
- Role management
- Permission management
- Agent sessions
- Message submission
- Execution inspection
- Event log queries
- Scheduler operations
- Skill registry operations
- Connector management
- Approval workflows

---

## 15.2 Shell Interface

The shell interface shall provide local interaction with the system.

The shell interface shall support:

- Sending messages to agents
- Inspecting sessions
- Managing skills
- Reviewing approvals
- Running local workflows
- Querying logs

The shell interface shall use the same application service layer as GraphQL.

---

## 15.3 Connector API

Connectors shall normalize inbound messages to a shared internal message format.

A normalized message shall include:

- Connector ID
- Channel identity
- Message body
- Attachments
- Timestamp
- Conversation/thread reference
- Signature/verification metadata where applicable

---

# 16. Security Design

## 16.1 Deny-by-Default

All permissions shall be denied unless explicitly granted.

---

## 16.2 Secrets Management

Secrets shall not be exposed directly to agents.

Skills and connectors shall request secret-backed operations through controlled interfaces.

---

## 16.3 PGP-Protected Email

The system shall support user-configurable PGP email policies.

At minimum, inbound email commands shall require sender verification sufficient to prevent unknown rogue actors from controlling the system.

Policies may include:

- Require signed inbound messages
- Verify sender public keys
- Sign outbound messages
- Encrypt outbound messages
- Store encrypted original messages
- Store decrypted working copies according to policy

---

## 16.4 External Effects

External effects shall require checkpointing and permission evaluation.

External effects include:

- Sending email
- Modifying files
- Running shell commands
- Posting to external systems
- Modifying calendar events
- Triggering Lyrion playback
- Creating scheduled tasks

---

# 17. Testing Design

## 17.1 Unit Testing

The system shall include extensive unit tests using zio-test.

Unit tests shall focus on:

- Domain logic
- Permission evaluation
- Capability scope matching
- Skill manifest validation
- Scheduler calculations
- Memory checkpoint classification
- Identity resolution
- Shell risk classification

---

## 17.2 Integration Testing

Integration tests shall cover:

- MariaDB persistence using Testcontainers
- Flyway migrations
- Quill repositories
- GraphQL API
- Telegram connector
- Email connectors where feasible
- Google service adapters where feasible
- Scheduler persistence and recovery
- External skill adapters

---

## 17.3 End-to-End Flow Testing

End-to-end tests shall cover complex flows such as:

- Telegram message -> identity resolution -> agent execution -> approval -> external effect -> memory checkpoint
- Shell user -> agent coding task -> restricted shell command -> event log -> artifact capture
- Scheduled task -> model call -> email draft -> approval -> send
- Calendar query -> summarization -> notification

---

## 17.4 Fake Providers

The system shall provide fake or deterministic implementations for:

- LLM providers
- Email providers
- Calendar providers
- Web search providers
- Market data providers
- Lyrion connector

These fakes shall support deterministic automated testing.

---

# 18. Deployment View

## 18.1 Initial Deployment

Initial deployment shall support a single-node runtime with MariaDB.

```text
Runtime JVM Process
  - Core runtime
  - GraphQL API
  - Shell interface
  - Telegram connector
  - Scheduler worker
  - Skill runtime

MariaDB
  - State
  - Event log
  - Scheduler
  - Memory metadata
  - Vector indexes
```

---

## 18.2 Future Deployment Options

Future deployment may support:

- Separate connector workers
- Separate scheduler workers
- Separate model gateway service
- Distributed execution
- Containerized skill execution
- Multi-node scaling

These are not required for the initial implementation.

---

# 19. Open Design Issues

The following issues require future clarification:

1. Exact shared-memory privacy boundaries
2. Exact GraphQL schema shape
3. Exact connector authentication model per channel
4. Exact MariaDB vector schema and embedding model strategy
5. Exact Quill repository structure
6. Whether some connectors should run out-of-process
7. Whether the shell connector should be local-only or remotely accessible by policy
8. How to promote agent-authored skills after repeated successful use
9. How imported MCP/OpenClaw skills should be sandboxed at runtime
10. Whether GraphQL is used internally by connectors or only exposed externally

---

# 20. Implementation Order Recommendation

Recommended initial implementation order:

1. Domain model skeleton
2. MariaDB schema and Flyway setup
3. Event log
4. User, identity, role, and permission model
5. Capability evaluation kernel
6. GraphQL API skeleton
7. Shell interface
8. Skill registry
9. Minimal built-in skills
10. Agent session runtime
11. Model gateway with fake provider and Ollama provider
12. Memory checkpointing
13. Scheduler
14. Telegram connector
15. Email and calendar adapters
16. Shell execution skill
17. Vector-backed memory retrieval
18. External skill import adapters

---

# 21. Validation Against Discussion

This design has been checked against the major decisions discussed so far.

| Discussion Point | Reflected in Design? | Location |
|---|---:|---|
| Written in Scala | Yes | Sections 3.1, 3.3 |
| JVM first, not Scala Native | Yes | Sections 3.2, 18 |
| MariaDB primary database | Yes | Sections 3.4, 14 |
| MariaDB vector support as derived index | Yes | Sections 3.4, 12 |
| ZIO ecosystem libraries | Yes | Section 3.3 |
| Caliban GraphQL | Yes | Sections 3.3, 15 |
| Flyway | Yes | Sections 3.3, 14 |
| Quill | Yes | Sections 3.3, 14 |
| LangChain4j | Yes | Section 3.3 |
| Multi-user from day one | Yes | Sections 5, 6, 8 |
| Canonical user with many channel identities | Yes | Section 5 |
| Shared memory across users | Yes, with privacy caveat | Sections 5.4, 12.4 |
| Conversation checkpointing into memory | Yes | Section 5.5 |
| Role system | Yes | Section 6 |
| Resource-specific 1x1 permissions | Yes | Section 6.2 |
| GraphQL, shell, Telegram required initially | Yes | Section 8.2 |
| Connector abstraction | Yes | Sections 8, 15.3 |
| GraphQL-first but not over-coupled internally | Yes | Section 8.4 |
| Email skill | Yes | Section 9.1 |
| Himalaya as adapter, not core | Yes | Section 9.1 |
| Google Calendar/Drive/etc. | Yes | Section 9.2 |
| gog as adapter, not core | Yes | Section 9.2 |
| Web search | Yes | Section 9.3 |
| Lyrion music service | Yes | Section 9.4 |
| Stock tickers | Yes | Section 9.5 |
| Scheduler and triggers | Yes | Sections 11, 9.6 |
| Agent-created skills | Yes | Sections 7.4, 7.5 |
| Generic declarative skills | Yes | Section 7.3 |
| Shell access with strict controls | Yes | Section 10 |
| Linux/macOS only, not Microsoft | Yes | Sections 1.2, 10.2 |
| PGP-protected email | Yes | Section 16.3 |
| Extensive unit testing | Yes | Section 17.1 |
| Integration testing with Testcontainers | Yes | Section 17.2 |
| Complex flow testing | Yes | Section 17.3 |
| Traceability and logging | Yes | Sections 14.3, 16, 17 |

## 21.1 Validation Summary

The design is a good match for the current discussion.

The largest unresolved risk is the shared-memory model. A fully shared memory system is powerful, but it needs careful policy boundaries to avoid cross-user leakage or inappropriate personalization. This is marked as an open design issue and should be refined before implementation of memory retrieval.

The second-largest unresolved issue is connector topology. The design currently keeps GraphQL as the primary external API while recommending a shared application service layer for internal connectors. This avoids over-coupling connectors to GraphQL but preserves the GraphQL-first direction.

The third-largest unresolved issue is shell execution. The design includes strong guardrails, but shell access remains inherently dangerous and should be implemented later than the permission kernel and event log.

Overall, the document reflects the project direction: a secure, multi-user, Scala-based, MariaDB-backed, observable agent runtime with typed skills, controlled autonomy, rich scheduling, memory checkpointing, and practical connectors.
