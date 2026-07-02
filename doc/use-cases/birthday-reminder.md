# birthday-reminder

## Description

This use case is about helping Roberto maintain relationships with family members by ensuring that birthdays are never
forgotten. The assistant will use the family genealogy database maintained in Gramps as the authoritative source of
family information.

The assistant will periodically examine the family tree, determine which individuals are still living, identify upcoming
birthdays, and send reminders before and on the day of each birthday.

The assistant should also help maintain data quality by identifying missing birth dates, questionable dates, duplicate
individuals, and people whose living/deceased status is unclear.

## Prompts

You are a family relationship assistant and genealogical researcher. You have access to the family tree stored in Gramps
and may use additional information from email, contacts, calendars, and prior conversations when appropriate.

Your primary responsibility is helping Roberto maintain relationships with living family members by ensuring that
birthdays are remembered and celebrated.

Every day you should:

* Review the Gramps family database.
* Determine which people are currently living.
* Identify birthdays occurring tomorrow.
* Identify birthdays occurring today.
* Send birthday reminders through Telegram.

The reminder sent the day before a birthday should include:

* Full name
* Relationship to Roberto
* Age they will be turning
* Birthday date
* Relevant notes that may help Roberto personalize a message

The reminder sent on the day of the birthday should include:

* Full name
* Relationship to Roberto
* Current age
* Contact information if available
* Any relevant family notes

You should consider the following:

* Gramps is the authoritative source of family relationships and birth dates.
* Do not send reminders for individuals known to be deceased.
* If an individual's living/deceased status is uncertain, notify Roberto separately and request clarification.
* If a birth date is incomplete (for example only a year is known), do not generate birthday reminders but report the
  missing information.
* If duplicate individuals appear to exist, notify Roberto.
* If a birthday falls on a major holiday, mention that in the reminder.
* Prefer to send reminders in the morning.
* Group multiple birthdays occurring on the same day into a single notification.
* If contact information is available from contacts, email, or other sources, include it in the reminder.
* If recent communication history exists, mention the last known interaction.
* If Roberto is on vacation, continue sending reminders unless explicitly configured otherwise.
* Maintain a log of birthday reminders sent so that duplicate notifications are not generated.
* Periodically review the family tree and generate a data-quality report identifying:

    * Missing birth dates
    * Missing death dates
    * Possible duplicates
    * Individuals with unknown living status
    * Missing relationships
    * Conflicting dates

## Skills likely involved

* Gramps
* Telegram API
* Google Contacts API
* Email API
* Google Calendar API
* Memory System
* Scheduler
* Identity Resolution
* Natural Language Processing (NLP)
* Data Quality Analysis

## Implementation in Jorlan

### Existing skills that satisfy this use case

| Requirement | Jorlan skill / tool |
|---|---|
| Daily trigger | `scheduler.create_job` (cron: `0 8 * * *`) |
| Send Telegram reminders | `TelegramConnectorSkill` — `telegram.send_message` |
| Look up contact info | `GoogleContactsSkill` — `google_contacts.search_contacts` |
| Check if Roberto is traveling | `GoogleCalendarSkill` — `calendar.listEvents` |
| Store reminder log (avoid duplicates) | `memory.remember` / `memory.search` |
| Store data quality findings | `memory.remember`, `workspace.write` |
| Send data quality report by email | `email.send` |

### MCPs to add via Jorlan's MCP Manager

#### Gramps Integration (required for birthday data)

Gramps is a genealogy database application. There is no official MCP server, but two approaches work:

**Option A (recommended): Shell-based XML export**
1. Export the Gramps database to XML using the `gramps` CLI: `gramps -O <family-tree> -e /path/to/export.gramps`
2. Schedule this export to run daily via Jorlan's `shell.run` tool.
3. The agent uses `workspace.read` or `http_fetch.get` on the exported XML file to extract birthdays.
4. Write a simple XML-parsing shell script to emit JSON: `gramps-birthdays.sh | jq ...`

**Option B: Gramps Web API**
If Gramps Web is installed, it exposes a REST API. Register it via `http_fetch` as a declarative skill or
configure it as an HTTP MCP server in Jorlan's MCP manager.

### New native skill needed

A `gramps` skill would provide cleaner integration — parsing `.gramps` XML exports and exposing tools like:
- `gramps.list_upcoming_birthdays(days=2)` — returns people with birthdays in the next N days
- `gramps.get_person(id)` — returns full person details including relationships and notes
- `gramps.data_quality_report()` — finds missing dates, duplicates, unknown living status

Until this skill is built, the shell-based XML approach in Option A is the practical workaround.

### Step-by-step agent workflow

```
1. scheduler triggers agent every morning at 08:00
2. agent calls shell.run("gramps -O <tree> -e /tmp/family.gramps && gramps-birthdays.sh")
   OR calls http_fetch.get(<gramps-web-api>/people?birthday_range=0,2)
3. agent parses results: finds people with birthday today or tomorrow
4. agent filters: skip deceased individuals, skip if status unknown (flag separately)
5. for each birthday person:
   a. agent calls google_contacts.search_contacts(name) to find contact info
   b. agent calls memory.search("<name> last interaction") for recent communication notes
   c. agent checks memory.search("birthday reminder sent <name> <date>") to avoid duplicates
   d. agent calls telegram.send_message with formatted reminder
   e. agent calls memory.remember("Birthday reminder sent for <name> on <date>")
6. for unknown living status: agent calls telegram.send_message to alert Roberto separately
7. weekly: agent runs data quality analysis and calls email.send or telegram.send_message with report
```

### What is not yet feasible

- Automatic live sync with the Gramps database without the shell export workaround
- Fetching "recent interaction" notes without integrating email search (possible with `email.search`)
