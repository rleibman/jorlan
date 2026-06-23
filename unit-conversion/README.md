# Unit Conversion Skill

Converts numeric values between units of measurement using [Squants](https://www.squants.com/), a strongly-typed Scala
units library.

**Skill name:** `units`  
**Tier:** Built-in  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Converts a numeric value from one unit to another across ten physical dimensions.

## Supported dimensions

| Dimension       | Example units                 |
|-----------------|-------------------------------|
| Length          | m, km, cm, mm, ft, in, yd, mi |
| Mass            | kg, g, mg, oz, lb, tonne      |
| Temperature     | °C, °F, K                     |
| Volume          | L, mL, gal, qt, pt            |
| Speed           | m/s, km/h, mph, knot          |
| Area            | m², km², ha, acre, ft², yd²   |
| Energy          | J, kJ, cal, kcal, kWh         |
| Power           | W, kW, MW, hp                 |
| Time            | s, min, h, d                  |
| Digital Storage | byte, KB, MB, GB, TB          |

## Tools

| Tool            | Description                              |
|-----------------|------------------------------------------|
| `units.convert` | Convert a value from one unit to another |

### `units.convert`

**Input:** `{ "value": <number>, "from": "<unit>", "to": "<unit>" }`

**Output on success:** `{ "result": <number>, "from": "<unit>", "to": "<unit>", "value": <number> }`  
**Output on error:** `{ "error": "<message>" }`

---

## Capabilities required

None — no capability grants needed.

---

## Example prompts

- "Convert 100 km to miles"
- "What is 32°F in Celsius?"
- "How many liters is 2 gallons?"
- "Convert 5 kg to pounds"
- "How many meters per second is 60 mph?"
- "Convert 1 GB to megabytes"

---

## Configuration

No configuration required. The unit conversion skill is always enabled.
