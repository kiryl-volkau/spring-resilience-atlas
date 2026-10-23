---
type: llm
---

PASS if the reply:
- gives the path or file:// url of the report page;
- names the Stripe webhook (`POST /api/billing/webhooks/stripe`) as the highest-ranked operation, or among the top ones, because work is handed to an in-memory thread;
- says that the levels are static hints from the code, not verdicts that something is broken.

FAIL if it presents a level as a verdict ("is broken", "is safe"), if it describes operations that were not analysed as having no risk, or if the Stripe webhook is not mentioned.
