# Phase 14.9 — User Management Skill

## Overview

A built-in skill that exposes user, role, and capability-grant CRUD operations to agents via the tool-calling loop.
Lives in the **`server` module** alongside the other built-in skills (no new SBT module) because it depends on
`ZIORepositories` which is already a `server`-module dependency.

## Skill namespace

`user_mgmt`

The namespace is used by `SkillRegistry` routing: `toolName.takeWhile(_ != '.')` must equal the skill name.
All tool names therefore start with `user_mgmt.`.

## Module

`server` — no new SBT module needed.  
Primary file: `server/src/main/scala/jorlan/service/skills/UserManagementSkill.scala`

## Dependencies

- `ZIORepositories` — provides `repos.user`, `repos.permission`
- No external HTTP calls, no extra services

## Tools

### User tools

| Tool | Required capability | Notes |
|------|-------------------|-------|
| `user_mgmt.list_users` | `admin.user.list` | Optional filters: `page`, `pageSize`, `nameContains`, `active` |
| `user_mgmt.get_user` | `admin.user.list` | Required: `userId` (Long) |
| `user_mgmt.create_user` | `user.create` | Required: `displayName`, `email`, `password` |
| `user_mgmt.update_user` | `user.update` | Required: `userId`; optional: `displayName`, `email` |
| `user_mgmt.deactivate_user` | `user.update` | Required: `userId` |

### Role tools

| Tool | Required capability | Notes |
|------|-------------------|-------|
| `user_mgmt.list_roles` | `admin.user.list` | Optional: `page`, `pageSize` |
| `user_mgmt.create_role` | `role.create` | Required: `name`; optional: `description` |
| `user_mgmt.assign_role` | `role.assign` | Required: `userId`, `roleId` |
| `user_mgmt.revoke_role` | `role.revoke` | Required: `userId`, `roleId` |

### Capability grant tools

| Tool | Required capability | Notes |
|------|-------------------|-------|
| `user_mgmt.list_grants` | `admin.user.list` | Required: `userId` |
| `user_mgmt.grant_capability` | `permission.grant` | Required: `userId`, `capability`; optional: `approvalMode` |
| `user_mgmt.revoke_grant` | `permission.revoke` | Required: `grantId` |

## Capabilities

All required capabilities already exist in `InitService.systemCapabilities` — no new capabilities need to be seeded.

## Wire-up

`Jorlan.registerBuiltInSkills` — add after the existing skill registrations:
```scala
_ <- registry.register(new UserManagementSkill(repos))
```

No new ZLayer entry needed since `ZIORepositories` is already in `JorlanEnvironment`.

## Error handling

All `RepositoryTask` failures are mapped to `JorlanError` via `.mapError(JorlanError(_))`.  
Missing required arguments produce a `JorlanError("user_mgmt.<tool>: <field> is required")`.
