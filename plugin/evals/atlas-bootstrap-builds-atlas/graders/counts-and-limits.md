---
type: llm
---

PASS if the reply states that the application has 82 HTTP endpoints and 27 messaging entry points (message consumers), with exactly those numbers, AND names at least one limitation the scan reported: a Java file that failed to parse and was scanned by regex, a Kotlin or Groovy file scanned by regex only, or HTTP endpoints hidden behind a missing (generated) interface.

FAIL if either number is missing or different, or if no scan limitation is mentioned.
