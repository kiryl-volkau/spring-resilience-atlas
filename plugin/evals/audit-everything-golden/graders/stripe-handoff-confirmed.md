---
type: regex
pattern: '"placeId":"PL-2ba53763"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
