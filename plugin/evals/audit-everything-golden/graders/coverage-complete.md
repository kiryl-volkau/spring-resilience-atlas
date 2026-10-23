---
type: regex
pattern: '"status":"COMPLETE"'
target:
  source: file
  path: .reliability/audit/coverage.json
arm: with-only
---
