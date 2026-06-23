# Scheduler Skill

Create and manage cron-based scheduled jobs that trigger agent tasks automatically.

**Skill name:** `scheduler`  
**Tier:** Built-in (always enabled)  
**Source:** `server/src/main/scala/jorlan/service/skills/SchedulerSkill.scala`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Lets agents create scheduled jobs using cron expressions. Jobs run on a durable schedule — they survive server restarts and track success/failure history.

## Tools

| Tool | Description |
|------|-------------|
| `scheduler.create_job` | Create a scheduled job with a cron expression |
| `scheduler.list_jobs` | List all scheduled jobs for the current agent |
| `scheduler.pause_job` | Pause a job so it stops firing |
| `scheduler.resume_job` | Resume a paused job |
| `scheduler.cancel_job` | Cancel and remove a job permanently |
| `scheduler.update_job` | Update a job's cron expression or prompt |

### `scheduler.create_job`

**Input:**

```json
{
  "name": "daily-report",
  "cronExpression": "0 9 * * 1-5",
  "prompt": "Generate and send the daily status report",
  "timezone": "America/New_York"
}
```

**Output:** `{ "jobId": "<id>", "name": "...", "nextRun": "..." }`

### Cron expression format

Uses standard 5-field cron: `minute hour day-of-month month day-of-week`

| Expression | Meaning |
|-----------|---------|
| `0 9 * * 1-5` | 9 AM weekdays |
| `0 */4 * * *` | Every 4 hours |
| `30 18 * * 5` | 6:30 PM every Friday |
| `0 0 1 * *` | Midnight on the 1st of each month |

### Job status values

| Status | Meaning |
|--------|---------|
| `Pending` | Waiting for its next scheduled run |
| `Running` | Currently executing |
| `Success` | Last run completed successfully |
| `Failed` | Last run encountered an error (job still exists) |
| `Paused` | Manually paused, will not fire until resumed |
| `Cancelled` | Removed permanently |

---

## Capabilities required

| Capability | Tools |
|-----------|-------|
| `scheduler.manage` | All tools |

---

## Example prompts

- "Remind me every morning at 8 AM to check my email"
- "Schedule a backup every Sunday at 2 AM"
- "What jobs do I have scheduled?"
- "Pause the daily report job"
- "Cancel the weekly newsletter job"

---

## Configuration

No external configuration required. The scheduler runs within the Jorlan server.
