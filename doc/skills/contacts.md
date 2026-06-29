# User Management Skill

Look up Jorlan users by name, find users by their channel identity (e.g. Telegram ID), manage channel identity links, and administer users, roles, and capability grants.

**Skill name:** `user_mgmt`  
**Tier:** Built-in (always enabled)  
**Source:** `server/src/main/scala/jorlan/service/skills/UserManagementSkill.scala`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Provides agents with access to the Jorlan user directory, channel identity mappings, and (for admins) user/role/capability management.

## Tools

| Tool | Description | Capability |
|------|-------------|------------|
| `user_mgmt.find` | Fuzzy search users by display name | `users.read` |
| `user_mgmt.resolve_identity` | Find user by channel type + ID | `users.read` |
| `user_mgmt.link_identity` | Create/update a channel identity | `identity.manage` |
| `user_mgmt.list_identities` | List all channel identities for a user | `users.read` |
| `user_mgmt.list_users` | List/search users (admin) | `admin.user.list` |
| `user_mgmt.get_user` | Fetch user by ID (admin) | `admin.user.list` |
| `user_mgmt.create_user` | Create a new user | `user.create` |
| `user_mgmt.update_user` | Update display name or email | `user.update` |
| `user_mgmt.deactivate_user` | Soft-delete a user | `user.update` |
| `user_mgmt.list_roles` | List roles | `admin.user.list` |
| `user_mgmt.create_role` | Create a role | `role.create` |
| `user_mgmt.assign_role` | Assign role to a user | `role.assign` |
| `user_mgmt.revoke_role` | Remove role from a user | `role.revoke` |
| `user_mgmt.list_grants` | List capability grants for a user | `admin.user.list` |
| `user_mgmt.grant_capability` | Grant a capability to a user | `permission.grant` |
| `user_mgmt.revoke_grant` | Revoke a capability grant | `permission.revoke` |

### `user_mgmt.find`

**Input:** `{ "name": "Roberto" }`

Fuzzy matching: `"Rob"` matches `"Roberto"`, `"Sara"` matches `"Sarah"`. Omit `name` to list all users. Returns channel identities too.

### `user_mgmt.resolve_identity`

**Input:** `{ "channelType": "Telegram", "channelUserId": "123456789" }`

**Output:** Jorlan user record if found.

### `user_mgmt.link_identity`

**Input:** `{ "channelType": "Telegram", "channelUserId": "123456789", "userId": "42" }`

Omit `userId` to link to the currently authenticated user.

---

## Configuration

No configuration required. Data is stored in the Jorlan database.
