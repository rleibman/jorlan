# Phase 16 Mini-Design: User-Created Declarative Skills

## Goal

Merge two Phase 16 roadmap items into a single implementation:

1. **Declarative JSON skills (Tier 2)** — let humans define skills as JSON manifests with no Scala code required.
2. **Agent-authored skill lifecycle (Tier 5 → Active)** — full state machine from draft through human approval to active.

Both share the same manifest format and lifecycle; the difference is who creates the draft (human via wizard vs. agent via tool call) and which tier the skill starts at (`Declarative` vs. `AgentDraft`).

---

## Scope

**In scope:**
- Manifest format (case classes + JSON codecs)
- HTTP/API and Prompt/Template executors
- Full lifecycle state machine: Draft → Validated → PermissionReviewed → SandboxTested → AwaitingApproval → Active / Rejected → Draft
- `SkillLifecycleService` driving all transitions
- `SkillAuthoringSkill` — built-in Tier 0 skill agents call to propose new skills
- GraphQL mutations/queries for all lifecycle operations
- Web wizard (dialog + MUI Stepper) for human-created skills
- Admin approval section on a new Custom Skills page
- New capabilities: `skill.create`, `skill.propose`, `admin.skills.approve`

**Deferred to follow-up:**
- Workflow, Query, and Command-template executor types
- Real sandbox test for prompt_template (auto-passes for MVP)

---

## 1. Manifest Format

```json
{
  "name": "weather",
  "version": "1.0.0",
  "description": "Look up weather forecasts",
  "keywords": ["weather", "forecast", "temperature"],
  "tools": [
    {
      "name": "weather.get_forecast",
      "description": "Get the current weather for a city.",
      "requiredCapabilities": ["http_fetch.call"],
      "examplePrompts": ["What's the weather in New York?"],
      "inputSchema": {
        "type": "object",
        "properties": { "city": { "type": "string" } },
        "required": ["city"]
      },
      "outputSchema": { "type": "object" },
      "executor": {
        "type": "http_api",
        "method": "GET",
        "url": "https://wttr.in/{{city}}?format=j1",
        "headers": {},
        "responseJsonPath": "$.current_condition[0].temp_C"
      }
    }
  ]
}
```

For prompt/template executor:
```json
{
  "executor": {
    "type": "prompt_template",
    "systemPrompt": "You are a helpful summariser.",
    "userPromptTemplate": "Summarise the following in {{style}} style:\n\n{{text}}"
  }
}
```

`{{argName}}` placeholders are substituted from tool input args at invocation time.

---

## 2. DB Migration

Next Flyway migration file (check current max V-number in `server/src/main/resources/sql/`):

```sql
ALTER TABLE skillVersion
  ADD COLUMN createdBy BIGINT NULL,
  ADD COLUMN reviewNote VARCHAR(1000) NULL,
  ADD CONSTRAINT fk_sv_user FOREIGN KEY (createdBy) REFERENCES `user`(id);
```

No new tables required — existing `skill` + `skillVersion` already model everything needed.

---

## 3. Server-Side Components

### New directory: `server/src/main/scala/jorlan/service/skills/declarative/`

#### `DeclarativeSkillManifest.scala`
Case classes + `derives JsonCodec` for the manifest format shown above.
`ExecutorConfig` is a sealed trait with `HttpApiExecutorConfig` and `PromptTemplateExecutorConfig` variants.
Use `zio-json` discriminator annotation on `ExecutorConfig` to encode/decode the `type` field.

#### `ManifestValidator.scala`
Pure (no ZIO). Signature:
```scala
object ManifestValidator {
  def validate(json: Json): Either[List[String], DeclarativeSkillManifest]
}
```
Checks:
- JSON parses into `DeclarativeSkillManifest`
- `name` matches `[a-z][a-z0-9_]*`
- Each tool name matches `<skillName>.<identifier>`
- `inputSchema`/`outputSchema` are JSON objects with a `"type"` field
- Executor config has all required non-empty fields
- At least one tool defined

#### `HttpApiExecutor.scala`
Executes HTTP/API tools via `zio.http.Client` (already on the server classpath):
- Substitutes `{{arg}}` tokens in URL and `bodyTemplate` from `args: Json`
- Makes the configured HTTP method request with supplied headers
- Optionally applies a JSONPath expression (`responseJsonPath`) to the response body
- Returns result as `Json`

#### `PromptTemplateExecutor.scala`
Executes prompt/template tools via `ModelGateway`:
- Substitutes `{{arg}}` tokens in `systemPrompt` and `userPromptTemplate`
- Calls `ModelGateway` with the rendered prompts (non-streaming, single-turn)
- Returns the LLM response text as `Json.Str`

#### `DeclarativeSkill.scala`
Implements `Skill`. Constructed from `DeclarativeSkillManifest` + injected `Client` + `ModelGateway`:
- `descriptor` maps manifest fields to `SkillDescriptor` (`name`, `tier = Declarative`, tool list)
- `invoke` dispatches to `HttpApiExecutor` or `PromptTemplateExecutor` based on tool's executor type

#### `SkillLifecycleService.scala`
ZIO service managing state transitions:

```scala
trait SkillLifecycleService {
  def createDraft(manifest: Json, tier: SkillTier, createdBy: UserId): IO[JorlanError, SkillVersion]
  def validate(versionId: SkillVersionId): IO[JorlanError, LifecycleResult]
  def reviewPermissions(versionId: SkillVersionId): IO[JorlanError, LifecycleResult]
  def sandboxTest(versionId: SkillVersionId): IO[JorlanError, LifecycleResult]
  def submitForApproval(versionId: SkillVersionId): IO[JorlanError, LifecycleResult]
  def approve(versionId: SkillVersionId, approvedBy: UserId): IO[JorlanError, Unit]
  def reject(versionId: SkillVersionId, reason: String, rejectedBy: UserId): IO[JorlanError, Unit]
  def advance(versionId: SkillVersionId): IO[JorlanError, LifecycleResult]
}

case class LifecycleResult(
  versionId: SkillVersionId,
  newStatus: SkillStatus,
  errors:    List[String],
  info:      List[String],
)
```

State transitions:
- `validate`: runs `ManifestValidator`; on success writes `status = Validated`
- `reviewPermissions`: collects all `requiredCapabilities` from tools; writes `status = PermissionReviewed`, returns list in `info`
- `sandboxTest`: for `http_api` makes one real invocation with schema-generated sample args; for `prompt_template` auto-passes in MVP; writes `status = SandboxTested`
- `submitForApproval`: writes `status = AwaitingApproval`; creates an `ApprovalRequest` (uses `skill.promote` capability name) visible in the Approvals page
- `approve`: parses manifest, calls `SkillRegistry.register(new DeclarativeSkill(...))`, sets `skill.currentVersion`, writes `status = Active`
- `reject`: writes `status = Draft`, stores `reason` in `skillVersion.reviewNote`
- `advance`: convenience dispatcher — calls the correct step based on current status

### `SkillAuthoringSkill.scala` (Tier 0 built-in)
New built-in skill registered at startup alongside other built-ins:
- Tool: `skill_authoring.propose`
- Required capability: `skill.propose`
- Input: `{ "manifest": "<JSON string>" }`
- Behaviour:
  1. Calls `SkillLifecycleService.createDraft(manifest, tier = AgentDraft, createdBy = ctx.actorId)`
  2. Auto-advances through Validate → PermissionReview → SandboxTest → AwaitingApproval
  3. Returns JSON with `versionId`, `status`, `errors`, `info` (including detected capabilities)
- The approval request surfaces in the Approvals page for admin review

---

## 4. Repository Additions

In `model/shared/src/main/scala/jorlan/repository.scala`:

```scala
// Make skillId optional so we can search across all skills by status
case class SkillVersionSearch(
  skillId:  Option[SkillId] = None,
  status:   Option[SkillStatus] = None,
  tier:     Option[SkillTier] = None,
  page:     Int = 0,
  pageSize: Int = 20,
  sorts:    Option[Sort[SkillVersionOrder]] = None,
) extends Search[SkillVersionOrder]

// Add to SkillRepository[F[_]]:
def upsertStatus(
  versionId:  SkillVersionId,
  status:     SkillStatus,
  reviewNote: Option[String],
): F[Unit]

def getVersionWithSkillName(id: SkillVersionId): F[Option[(SkillVersion, String)]]
```

Implement in `QuillSkillRepository` and `InMemoryRepositories` (test fake).

---

## 5. GraphQL API Additions

### New types in JorlanAPI.scala
```scala
case class SkillVersionView(
  id:           SkillVersionId,
  skillId:      SkillId,
  skillName:    String,
  version:      String,
  manifestJson: String,
  status:       String,
  reviewNote:   Option[String],
  createdAt:    Instant,
) derives Schema.SemiAuto

case class LifecycleResult(
  versionId: SkillVersionId,
  newStatus: String,
  errors:    List[String],
  info:      List[String],
) derives Schema.SemiAuto
```

### New queries
- `skillVersions(skillId: SkillId!): [SkillVersionView!]` — requires `admin.settings`
- `pendingSkillVersions: [SkillVersionView!]` — AwaitingApproval only; requires `admin.skills.approve`
- `allCustomSkills: [SkillVersionView!]` — latest version per Declarative/AgentDraft skill; requires `admin.settings`

### New mutations
- `createSkillDraft(manifest: String!): SkillVersionView` — requires `skill.create`
- `advanceSkillLifecycle(versionId: SkillVersionId!): LifecycleResult` — requires `skill.create` or `admin.settings`
- `approveSkillVersion(versionId: SkillVersionId!): LifecycleResult` — requires `admin.skills.approve`
- `rejectSkillVersion(versionId: SkillVersionId!, reason: String!): LifecycleResult` — requires `admin.skills.approve`

### New capabilities (auto-granted to admin in `InitService`)
- `skill.create`
- `skill.propose`
- `admin.skills.approve`

After any JorlanAPI change: run `scripts/capture-schema.sh` then `scripts/gen-client.sh`.

---

## 6. Web UI

### New page: `web/src/main/scala/jorlan/web/pages/CustomSkillsPage.scala`
- Route: `#/admin/custom-skills` — add to `AppRouter.scala` and sidebar nav
- Sections:
  1. **"Create Custom Skill"** button → opens `CreateSkillWizard`
  2. **Pending Approval** (admin only) — table of `AwaitingApproval` versions with Approve/Reject actions
  3. **All Custom Skills** — table showing name, tier, version, status for all Declarative/AgentDraft skills

### New component: `web/src/main/scala/jorlan/web/components/CreateSkillWizard.scala`
Dialog containing an MUI `Stepper` (use via wildcard import `net.leibman.jorlan.muiMaterial.components.*`; props from `stepperStepperMod` / `stepStepMod` / `stepLabelStepLabelMod`).

**Step 0 — Choose executor type** (radio: HTTP/API or Prompt/Template)

**Step 1 — Skill info** (name, version, description, keywords)

**Step 2 — Tool definition**
- Tool name (suffix; prefixed with skill name automatically)
- Tool description
- Input parameters: dynamic list (name, type dropdown, description, required checkbox)
- Executor config:
  - HTTP/API: method dropdown, URL field with `{{param}}` hint, headers list, body template, JSONPath
  - Prompt/Template: system prompt textarea, user prompt template textarea with `{{param}}` hint

**Step 3 — Permissions**
- Required capabilities (pre-populated by executor type; editable)
- Example prompts (dynamic list)

**Step 4 — Review & submit**
- Read-only JSON manifest preview (`<pre>` block)
- "Create Draft" → `createSkillDraft` mutation
- After draft created: inline lifecycle stepper (Validate / Permission Review / Sandbox Test / Awaiting Approval)
  - "Advance" button per step calls `advanceSkillLifecycle`
  - Errors shown inline below the failed step
  - `info` list (detected capabilities) shown on PermissionReviewed step

**Wizard state** (follow `McpServersPage.scala` `ScalaFnComponent.withHooks` pattern):
```scala
case class WizardState(
  step:             Int,
  executorType:     String,
  basicForm:        BasicForm,
  toolForm:         ToolForm,
  permissionsForm:  PermissionsForm,
  createdVersionId: Option[SkillVersionId],
  lifecycleStatus:  Option[String],
  lifecycleErrors:  List[String],
  lifecycleInfo:    List[String],
  saving:           Boolean,
  advancing:        Boolean,
  toast:            Option[ToastMessage],
)
```

### Admin approval in `CustomSkillsPage.scala`
Table: Skill Name | Version | Status | Created At | Manifest (expandable row) | Actions
- [Approve] button → `approveSkillVersion`
- [Reject] button → opens small Dialog with reason TextField → `rejectSkillVersion`

---

## 7. File Map

| Action | Path |
|--------|------|
| New | `server/.../service/skills/declarative/DeclarativeSkillManifest.scala` |
| New | `server/.../service/skills/declarative/ManifestValidator.scala` |
| New | `server/.../service/skills/declarative/HttpApiExecutor.scala` |
| New | `server/.../service/skills/declarative/PromptTemplateExecutor.scala` |
| New | `server/.../service/skills/declarative/DeclarativeSkill.scala` |
| New | `server/.../service/skills/declarative/SkillLifecycleService.scala` |
| New | `server/.../service/skills/SkillAuthoringSkill.scala` |
| New | `server/src/main/resources/sql/V0XX__skill_version_author.sql` |
| New | `web/.../pages/CustomSkillsPage.scala` |
| New | `web/.../components/CreateSkillWizard.scala` |
| New | `server/src/test/.../declarative/ManifestValidatorSpec.scala` |
| New | `server/src/test/.../declarative/SkillLifecycleServiceSpec.scala` |
| Modify | `model/shared/.../repository.scala` — `SkillVersionSearch` + 2 new repo methods |
| Modify | `server/.../db/repository/QuillRepositories.scala` — implement new methods |
| Modify | `server/src/test/.../testing/InMemoryRepositories.scala` — implement new methods |
| Modify | `server/.../graphql/JorlanAPI.scala` — new types, queries, mutations |
| Modify | `server/.../init/InitService.scala` — grant new capabilities to admin |
| Modify | `server/.../Jorlan.scala` — wire lifecycle service + authoring skill |
| Modify | `web/.../AsyncCallbackRepositories.scala` — new skill API calls |
| Modify | `web/.../AppRouter.scala` — CustomSkills route + nav |
| Modify | `shell/.../ZIOClientRepositories.scala` — mirror new methods |

---

## 8. Verification

1. `sbtn --error "compile; test:compile"` — clean compile
2. `sbtn --error test` — all existing + new tests pass
3. Manual: human wizard flow → HTTP/API skill → full lifecycle → approve → agent can call it in chat
4. Manual: agent proposes skill in chat → approval request appears → admin approves → skill active
5. Manual: reject flow → status returns to Draft, review note visible in UI
