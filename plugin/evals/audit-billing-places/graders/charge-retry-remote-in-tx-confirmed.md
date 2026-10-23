---
type: regex
pattern: '"placeId":"PL-e4f53a00"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
