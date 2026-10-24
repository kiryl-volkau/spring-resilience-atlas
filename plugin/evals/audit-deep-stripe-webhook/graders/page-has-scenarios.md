---
type: regex
pattern: '"id":"SC-[0-9a-f]{8}"'
target:
  source: file
  path: .reliability/flows/FLOW-billing-stripe-webhook.html
arm: with-only
---
