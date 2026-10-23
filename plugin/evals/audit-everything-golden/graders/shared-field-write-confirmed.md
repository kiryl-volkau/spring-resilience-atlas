---
type: regex
pattern: '"placeId":"PL-711bd529"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
