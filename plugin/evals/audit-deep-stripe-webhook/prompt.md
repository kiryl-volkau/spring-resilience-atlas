---
description: One message asks for the deep audit of the recorded billing webhook flow under a limit; the scenario author writes failure scenarios, the verifier checks the severe ones, and the scanner records verified findings and draws them on the flow page.
max_turns: 40
timeout_seconds: 1800
allowed_tools: [Skill, Read, Write, Agent]
---

Run the deep audit of the flow FLOW-billing-stripe-webhook. If the estimate is under $6, run it with the plan id it prints, then tell me what can break and how to fix it.
