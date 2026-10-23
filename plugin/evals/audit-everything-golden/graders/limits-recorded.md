---
type: regex
pattern: 'were not swept \(--level NONE adds them\)[\s\S]*operations not analysed'
target:
  source: file
  path: .reliability/audit/coverage.json
arm: with-only
---
