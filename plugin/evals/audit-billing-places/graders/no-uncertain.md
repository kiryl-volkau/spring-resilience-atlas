---
type: regex
pattern: '"verdict":"UNCERTAIN"'
match: not_contains
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
