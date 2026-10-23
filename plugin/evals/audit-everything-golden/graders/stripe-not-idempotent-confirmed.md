---
type: regex
pattern: '"placeId":"PL-a39aeb08"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
