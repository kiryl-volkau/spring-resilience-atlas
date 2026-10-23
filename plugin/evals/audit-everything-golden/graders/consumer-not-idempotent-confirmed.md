---
type: regex
pattern: '"placeId":"PL-77298363"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
