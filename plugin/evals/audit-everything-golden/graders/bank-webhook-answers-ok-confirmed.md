---
type: regex
pattern: '"placeId":"PL-dced4ba1"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
