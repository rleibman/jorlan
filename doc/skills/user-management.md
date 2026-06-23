# User Management Skill

Admin-level tools for listing, creating, updating, and deactivating Jorlan user accounts.

**Skill name:** `user_mgmt`  
**Tier:** Built-in (always enabled)  
**Source:** `server/src/main/scala/jorlan/service/skills/UserManagementSkill.scala`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Provides full CRUD access to Jorlan user accounts. All tools require elevated privileges.

## Tools

| Tool | Description |
|------|-------------|
| `user_mgmt.list_users` | List or search users with optional filters |
| `user_mgmt.get_user` | Fetch a single user by numeric ID |
| `user_mgmt.create_user` | Create a new user account |
| `user_mgmt.update_user` | Update a user's display name or email |
| `user_mgmt.deactivate_user` | Deactivate (soft-delete) a user account |

### `user_mgmt.list_users`

**Input:** `{ "nameContains": "rob", "active": true, "page": 0, "pageSize": 20 }`

### `user_mgmt.create_user`

**Input:** `{ "name": "Alice Smith", "email": "alice@example.com", "password": "tempPass123!" }`

### `user_mgmt.update_user`

**Input:** `{ "userId": 42, "name": "Alice Johnson", "email": "alice.j@example.com" }`

### `user_mgmt.deactivate_user`

**Input:** `{ "userId": 42 }`

---

## Capabilities required

| Capability | Tools |
|-----------|-------|
| `admin.user.list` | `list_users`, `get_user` |
| `user.create` | `create_user` |
| `user.update` | `update_user` |
| `user.deactivate` | `deactivate_user` |

These capabilities should only be granted to administrative agents.

---

## Example prompts

- "List all active users"
- "Create a new account for Bob (bob@example.com)"
- "Update Alice's email address"
- "Deactivate the account for user ID 99"
- "How many users do we have?"

---

## Configuration

No external configuration required.
