---
type: regex
pattern: '"placeId":"PL-6ef7a4c4"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
