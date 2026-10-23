---
type: regex
pattern: '"placeId":"PL-d0e0c22d"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
