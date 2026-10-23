---
type: regex
pattern: '"placeId":"PL-828ebd1f"[^\n]*"verdict":"CONFIRMED"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
