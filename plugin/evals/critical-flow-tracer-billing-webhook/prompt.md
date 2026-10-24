---
description: The billing webhook flow is traced across the webhook and its SQS consumer into a recorded flow and its page, with the transactions, the queue send, the executor hand-off and when each caller is answered.
max_turns: 30
timeout_seconds: 900
allowed_tools: [Skill, Agent, Read, Glob, Grep]
---

Trace what happens when the payment provider calls our billing webhook, end to end, so we can audit it for lost or duplicated payments later.
