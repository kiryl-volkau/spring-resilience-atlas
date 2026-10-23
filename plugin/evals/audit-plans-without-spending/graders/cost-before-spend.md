---
type: llm
---

PASS if the reply gives the planned number of places, an estimated cost in USD (and optionally tokens) with a cap, states that nothing has been spent yet, and gives the exact command with `--yes` and a plan id to run the check.

FAIL if the reply presents any verdict (confirmed, suspected, safe, broken) as a result of this turn, if it says the check already ran, or if the cost or the run command is missing.
