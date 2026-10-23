---
type: regex
pattern: '"placeId":"PL-05867ddd"[^\n]*"verdict":"(REFUTED|MITIGATED)"'
target:
  source: file
  path: .reliability/audit/places.jsonl
arm: with-only
---
