# Food Calendar

## Description

This use case is about creating a food calendar that helps us plan our meals for the week. The calendar will take into
account our dietary preferences, schedule, and the weather to suggest meals that are suitable for the time of year. The
calendar will also be able to suggest recipes and generate a shopping list based on the meals we have planned.

## Prompts

You are an expert executive chef and meal planner. You have a deep understanding of various cuisines, dietary
restrictions, and meal planning strategies. You are also skilled in using technology to help with meal planning and
grocery shopping.
Every week on Wednesday, you will create a meal plan for the upcoming week. You will take into account our dietary
preferences, schedule, and the weather to suggest meals that are suitable for the time of year.

You should consider the following:

- Sarah and I are excellent cooks and enjoy cooking alone and together. We don't shy away from complex recipes and
  techniques, but we reserve the more complex ones for Friday's and weekends.
- We like variety in our meals, ethnically, and in the types of meals (soups, salads, casseroles, etc.).
- Sarah can't ean cruciferous vegetables (cauliflower, broccoli, cabbage, etc.).
- We are trying to reduce the amount of red meat we eat, our weekly menu is mostly veggies/fish/poultry, every once in a
  while it's ok.
- We go to the grocery store on Thursday after dinner, Thursday dinner should be simpler.
- When we have an evening activity on the calendar and no other dinner plans, we want to have a simple meal that can be
  made in 30 minutes or less.
- Don't do the food calendar on days we're on vacation
- Pay attention to the weather and time of year. In the summer we eat a lot of ceviches, and love gazpacho. In the
  winter, hearty meals, soups and stews.
- Pay attention to the websites we have been using for recipes and try to suggest meals from those sites.
- Add the recipes to the dinner google calendar.
- Generate a shopping list for the week and add it to our grocery list app (Ourgroceries).
- Add reminders that you should send on Telegram for recipes that have a long prep (e.g. overnight marinating, or slow
  cooking) to make sure we start the prep in time.

## Skills likely involved

- Google Calendar API
- Google Search
- Email API
- Natural Language Processing (NLP)
- Web Scraping
- Meal-o-rama
- Weather API
- Ourgroceries
- Telegram API

## Implementation in Jorlan

### Existing skills that satisfy this use case

| Requirement | Jorlan skill / tool |
|---|---|
| Check weekly calendar (event nights, vacations) | `GoogleCalendarSkill` — `calendar.listEvents` |
| Check weather and season | `weather` skill |
| Search for recipe ideas | `search.web` (search skill) |
| Fetch and read recipe pages | `http_fetch.get` |
| Add meals to Google Calendar | `GoogleCalendarSkill` — `calendar.createEvent` |
| Send Telegram reminders for long-prep meals | `TelegramConnectorSkill` — `telegram.send_message` |
| Remember past meal history and preferences | `memory.remember` / `memory.search` / `memory.search_semantic` |
| Schedule the Wednesday trigger | `scheduler.create_job` (cron: `0 9 * * 3`) |
| Day-before reminders for long-prep meals | `scheduler.create_job` (one-shot, created dynamically) |
| Meal-o-rama integration (if used) | `openclaw` MCP skill (available separately) |

### MCPs to add via Jorlan's MCP Manager

#### Ourgroceries (HIGH PRIORITY)

Ourgroceries has no official public REST API. The best path is a small stdio MCP server wrapping the
unofficial Ourgroceries API. The `ourgroceries` npm package (community-maintained) implements the
unofficial API.

**Steps to set up:**
1. Create a minimal Node.js MCP server (e.g. `ourgroceries-mcp`) that exposes:
   - `ourgroceries.add_items(listName, items[])` — add items to a shopping list
   - `ourgroceries.get_lists()` — list available shopping lists
   - `ourgroceries.get_items(listName)` — read items from a list
   - `ourgroceries.remove_item(listName, itemId)` — remove a checked-off item
2. Configure credentials (email + password) as environment variables in the MCP server config.
3. Register the MCP server in Jorlan's Settings → MCP Servers using `stdio` transport.

**Alternative workaround:** Output the shopping list as a Google Doc or Google Sheet via
`GoogleDriveSkill.createFile`, then manually copy to Ourgroceries. This avoids the MCP wrapper but
loses integration.

### Step-by-step agent workflow

The food calendar agent runs automatically every Wednesday at 09:00. The agent should be configured
with the system prompt from the `## Prompts` section above and the capabilities listed below.

```
1.  scheduler triggers the food-calendar agent every Wednesday at 09:00
2.  agent calls calendar.listEvents(timeMin=now, timeMax=now+7days) — identify event nights, vacation days, Thursday
3.  agent calls weather.forecast(location, days=7) — determine season/conditions
4.  agent calls memory.search("meal history last 4 weeks") — avoid repeating recent meals
5.  agent calls memory.search("dietary preferences Sarah Roberto") — retrieve cruciferous veg rule, red meat limit, etc.
6.  agent reasons: for each day of the week:
      - Is it a vacation day? → skip
      - Is there an evening calendar event? → plan ≤30 min meal
      - Is it Thursday? → plan a simpler meal (grocery shopping night)
      - Is it Friday or weekend? → complex/adventurous recipe is fine
      - What cuisines haven't appeared in recent meal history? → pick one
      - What does the weather suggest? → ceviche/gazpacho in summer, soups/stews in winter
7.  for each day that needs a meal:
      a. agent calls search.web("recipe <cuisine> <constraints> site:<preferred-site>")
      b. agent calls http_fetch.get(<recipe-url>) to extract ingredients and prep time
8.  agent calls calendar.createEvent for each meal:
      - title: "<Dish Name>"
      - description: "<Recipe URL>\n\n<Brief ingredients summary>"
      - calendar: dinner calendar
      - start/end: dinner time that evening
9.  agent aggregates all ingredients across the week into a consolidated shopping list
10. agent calls ourgroceries.add_items("Groceries", shopping_list) — or creates a Google Doc fallback
11. agent calls memory.remember("Meal plan week of <date>: <summary of planned meals>")
12. agent identifies meals with long prep (overnight marinades, slow cooker, >1hr active prep):
      for each long-prep meal:
        - agent calls scheduler.create_job(runAt=<day_before 17:00>, prompt="Send Telegram reminder for <dish>")
        - the triggered job calls telegram.send_message("Heads up: tomorrow's dinner is <dish>. Start prep today: <notes>")
13. agent calls telegram.send_message with a summary of the week's meal plan
```

### Agent configuration

Define this as a scheduled Jorlan agent with:

**System prompt:** The full prompt from `## Prompts` above, plus:
- Roberto and Sarah's specific dietary rules stored in `memory` (seed once manually)
- The preferred recipe websites list (stored in `memory` as well)

**Required capabilities:**
- `calendar.listCalendars`, `calendar.listEvents`, `calendar.createEvent`
- `weather.*`
- `search.web`
- `http_fetch.get`
- `telegram.send_message`
- `memory.remember`, `memory.search`, `memory.search_semantic`
- `scheduler.create_job`
- `ourgroceries.*` (once MCP is configured)

**Schedule:** `scheduler.create_job(cron="0 9 * * 3", agentId=<food-calendar-agent>)`

### Seeding memory

Before the first run, seed the following into the agent's memory:

```
memory.remember("Dietary rules: Sarah cannot eat cruciferous vegetables (cauliflower, broccoli, 
  cabbage, Brussels sprouts, kale, bok choy). We try to limit red meat to once a week maximum. 
  We enjoy variety across cuisines.")

memory.remember("Preferred recipe websites: seriouseats.com, cooking.nytimes.com, 
  bonappetit.com, thekitchn.com, allrecipes.com, food52.com")

memory.remember("Shopping schedule: We go grocery shopping Thursday evening. 
  Thursday dinner should be simple (30 minutes or less).")

memory.remember("Weekend cooking: Fridays and weekends are when we cook more complex recipes. 
  Weeknight dinners (Mon-Thu) should be 45 minutes or under unless it is a slow cooker or overnight prep.")
```

### What is not yet feasible

- Automatic detection of "preferred recipe websites" from browsing history (requires browser integration)
- Reading recipe ingredient lists from sites with anti-scraping measures (use `http_fetch` with user-agent headers as a workaround)
- Ourgroceries integration without the custom MCP server described above
