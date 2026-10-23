---
type: regex
pattern: '"placeId":"PL-6a66916e"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
