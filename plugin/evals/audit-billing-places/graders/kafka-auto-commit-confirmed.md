---
type: regex
pattern: '"placeId":"PL-009b7afd"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
