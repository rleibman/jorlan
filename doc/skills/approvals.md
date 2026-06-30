# Approvals

The Approvals system provides human-in-the-loop oversight for agent tool calls that require explicit authorization before execution.

---

## How it works

When an agent invokes a tool that requires approval (based on the capability grant's `approvalMode`), the request is paused and sent to the approvals queue. A human operator must approve or deny the request before the agent can continue.

**Approval modes** (set per capability grant):

| Mode | Behaviour |
|------|-----------|
| `Always` | Every invocation requires a new approval |
| `Once` | First approval grants permanent access for this capability |
| `Session` | Approval is valid for the duration of the current agent session |
| `Persistent` | Admin-seeded grant; never requires human approval |

---

## Triggering an approval

Approvals are triggered automatically when:
1. An agent invokes a tool requiring a capability
2. The capability's grant has `approvalMode` set to `Always`, `Once`, or `Session`
3. No prior approval covers the request

The agent fiber blocks until a decision is received or the approval times out.

---

## Responding to approvals

### Web UI (`/approvals` page)

The Approvals page lists all pending requests in real-time (via WebSocket subscription). Each row shows:
- Capability name
- Risk level (ReadOnly / Mutating / Destructive / Critical)
- Requesting agent and session
- Expiry time

Click **Approve** or **Deny** to respond.

### GraphQL API

```graphql
mutation {
  recordApprovalDecision(input: {
    approvalRequestId: "<id>",
    decision: Approved,
    decidedBy: <userId>
  }) {
    id
    decision
  }
}
```

---

## Timeout

Pending approvals expire after a configurable timeout (default: 5 minutes). The `expireStaleRequests` scheduler job runs periodically to mark stale requests as `Expired`.

---

## Skill lifecycle vs tool approval

These are two separate systems:

| | Tool Approval | Skill Lifecycle Approval |
|-|--------------|------------------------|
| **What** | Approves a specific agent tool call | Approves a new declarative skill for production use |
| **Who** | Any authorized admin | Admin with `admin.skills.approve` capability |
| **Where** | `/approvals` page | `/custom-skills` page |
| **API** | `recordApprovalDecision` mutation | `approveSkillVersion` mutation |

---

## Audit trail

Every approval event is written to the event log:
- `ApprovalRequested` — when the agent first needs approval
- `ApprovalGranted` — when a human approves
- `ApprovalDenied` — when denied, expired, or cancelled

The event includes `agentId` and `sessionId` for full traceability.
