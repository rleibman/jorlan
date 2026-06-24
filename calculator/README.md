# Calculator Skill

Evaluates mathematical expressions using [mXparser](https://mathparser.org/), a full-featured math expression engine.

**Skill name:** `calculator`  
**Tier:** Built-in  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Accepts a free-form mathematical expression as a string and returns the computed numeric result. Supports arithmetic,
algebra, trigonometry, logarithms, factorials, percentages, and most standard math functions.

## Tools

| Tool                  | Description                                                      |
|-----------------------|------------------------------------------------------------------|
| `calculator.evaluate` | Evaluate a mathematical expression and return the numeric result |

### `calculator.evaluate`

**Input:** `{ "expression": "<string>" }`

**Example expressions:**

- `2 + 2 * sqrt(9)` → `8`
- `sin(pi/2)` → `1`
- `15% * 200` → `30`
- `log(1000)` → `3`
- `2^10` → `1024`
- `factorial(6)` → `720`

**Output on success:** `{ "result": <number>, "expression": "<string>" }`  
**Output on error:** `{ "error": "<message>", "expression": "<string>" }`

---

## Capabilities required

None — this skill requires no capability grants.

---

## Example prompts

- "What is 2 + 2?"
- "Calculate the square root of 144"
- "What is 15% of 200?"
- "Evaluate sin(pi/2)"
- "What is 2 to the power of 10?"

---

## Configuration

No configuration required. The calculator skill is always enabled.
