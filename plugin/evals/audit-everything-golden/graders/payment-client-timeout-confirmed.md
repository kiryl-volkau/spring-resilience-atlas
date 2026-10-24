---
type: regex
pattern: '"placeId":"PL-83feae88"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
