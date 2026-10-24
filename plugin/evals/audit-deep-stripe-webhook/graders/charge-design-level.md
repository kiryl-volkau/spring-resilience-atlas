---
type: regex
pattern: '"ruleId":"(remote-in-tx|external-effect-inside-local-tx|consumer-not-idempotent)"[^\n]*"scope":"CONCEPT"'
target:
  source: file
  path: .reliability/audit/findings.jsonl
arm: with-only
---
