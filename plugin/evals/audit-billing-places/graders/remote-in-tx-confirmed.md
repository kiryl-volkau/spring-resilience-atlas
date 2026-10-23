---
type: regex
pattern: '"placeId":"PL-d03c7988"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
