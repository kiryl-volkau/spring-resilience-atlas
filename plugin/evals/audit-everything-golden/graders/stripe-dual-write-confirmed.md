---
type: regex
pattern: '"placeId":"PL-99da8683"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
