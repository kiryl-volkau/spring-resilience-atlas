---
type: regex
pattern: '"placeId":"PL-916dd6f0"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
