---
description: The billing webhook flow is traced into a verified flow file that records the transaction, the queue send, the executor hand-off and when the caller gets its 200.
max_turns: 30
timeout_seconds: 900
allowed_tools: [Skill, Agent, Read, Glob, Grep]
---

Trace what happens when the payment provider calls our billing webhook, end to end, so we can audit it for lost or duplicated payments later.
