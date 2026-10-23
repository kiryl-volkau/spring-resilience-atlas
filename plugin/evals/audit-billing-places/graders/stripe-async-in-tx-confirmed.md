---
type: regex
pattern: '"placeId":"PL-55683365"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
