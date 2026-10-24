---
type: regex
pattern: '"ruleId":"handoff-after-ack"[^\n]*"status":"VERIFIED"'
target:
  source: file
  path: .reliability/audit/findings.jsonl
arm: with-only
---
