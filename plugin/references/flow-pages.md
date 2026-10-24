# Flow pages

A traced flow is stored as data and drawn as a page. Nothing on the page exists only there.

| File | Written by | Holds |
|---|---|---|
| `.reliability/flows/drafts/<FLOW-id>.json` | the flow tracer | the draft, with the tracer's own ids |
| `.reliability/flows/<FLOW-id>.json` | `audit record-flow` | the checked flow: stable ids, numbered steps, commit, fingerprint |
| `.reliability/flows/index.json` | every flow and report command | one row per flow: status, entry points, the places it crosses |
| `.reliability/flows/<FLOW-id>.html` | `record-flow`, `flow page`, `report` | the page |
| `.reliability/audit/scenarios/<FLOW-id>.jsonl` | `audit record-step` of a deep audit | the failure scenarios, `doc scenario.schema.json` |
| `.reliability/audit/findings.jsonl` | `audit record-step` | one finding per rule or pattern, flow and broken step |

## Ids

- Participants: `P-` and 8 hex of `kind|name`. The same table, queue or system has the same id
  in every flow.
- Steps: `S-` and 8 hex of segment, kind, both participants, the evidence file and the method
  around the evidence line. A shifted line keeps the id; a moved call changes it.
- Links: `LK-` and 8 hex of kind, both steps and the key.

Scenarios and findings point at step ids, so they keep their place on the diagram across
re-traces as long as the code they describe did not move.

## Staleness

`fingerprint.core` hashes the methods around every step's evidence. When it no longer matches
the tree, the flow is `STALE`: the index, the report and the page say so, and a re-trace
refreshes it.

## Link confidence

`record-flow` sets it; a draft's value is replaced.

| How the two sides are joined | Accepted link |
|---|---|
| a `STATIC` candidate of `atlas/links.jsonl` with the same kind and key | HIGH |
| an `INFERRED` candidate plus a correlation field written on one side and read on the other | HIGH |
| an `INFERRED` candidate, or a key the code names on both sides, cited on both sides | MEDIUM |
| a correlation field without a candidate | MEDIUM |
| only the business meaning (`basis: AGENT`) | LOW, drawn dashed as an assumption |

A rejected candidate stays in the file with its reason, so a later trace does not ask again.
Candidates out of the flow's entry points that no link decides are kept as
`candidatesUndecided` and listed on the page; an undecided `STATIC` one that leaves the flow
makes `record-flow` refuse the draft.

## Reading a page

- **Diagram.** One arrow per step, numbered. A flow with more than 12 participants folds each
  segment's beans into its entry point's column ("entry +3 beans"); calls between them become
  loops labelled with the bean. When it is still too wide, the tables share one "database"
  column and each arrow names its table. Queues, external systems and thread pools always keep
  their own column. "Show every bean" under the diagram unfolds it, and the page remembers the
  choice. Solid: a call or a write. Dashed: an answer.
  Dotted amber: another thread or a message. The purple band on a lifeline is a transaction.
  A hollow amber dot marks a step whose result is not durable yet. Grey bands start each entry
  point and name the link that leads to it. Click an arrow for its line of code.
- **Systems strip.** The external systems, entry points, tables and queues; clicking one
  scrolls the diagram to it.
- **Business parts**, **hints on this flow** with their quick-check verdicts and links into the
  report, and **how it was traced**: acknowledgements, links with their reasons, the file
  budget and open questions.

## Failure scenarios

A deep audit (`/resilience-audit:audit --deep`) adds stories to the page. Each is a chip above
the diagram; picking one greys the flow, highlights the steps of the story with their state
(lost, duplicated, broken, skipped), breaks the arrow where the work goes wrong and, when one
step cannot close it, brackets the steps a design change spans. The panel under the diagram
tells the story in numbered lines, then why it happens, the mitigation found or missing, the
changes that close it, and the finding with its fix and its failure-injection test.

- **Severity and confidence** are separate: how bad if true, and how sure.
- **Verified** (`✓` on the chip): a second model tried to disprove the story and could not.
  **Suspected**: written, not yet verified. **Disproved**: struck through, with the line that
  rules it out. **Harmless**: a matched pattern the author checked and found not to bite here.
- **Bites once this is fixed**: the verifier found the story's mechanism real, but another
  defect stops the flow before it today, a missing column for example. It counts as verified;
  fixing that other defect makes it happen.
- Patterns the flow matched that no accepted scenario covers are listed under the chips; the
  next deep audit of the flow tries them again.
- **Design-level** means no single change of a method, key or setting closes the story; the
  fix is a new component or state (an outbox, an intent table, a reconciliation job), with the
  step changes as interim measures. **Step-level** means one such change closes it.
- A scenario written against an earlier trace of the flow says so; re-run the deep audit.

## History

`flow snapshot` and every finished deep plan write `.reliability/history/<time>-<commit>.json`:
ids, statuses and code fingerprints, not the documents. `flow diff` compares the newest one with
the code as it is now. A finding is **fixed** only when a later deep audit of its flow no longer
finds it; a deep audit keeps the findings it did not judge again while their code is unchanged,
so a finding cannot disappear just because a run looked elsewhere. A finding whose code changed
without a new audit is **changed, not audited again**. The report shows the comparison under
its headline, and the backlog tags new and changed findings and lists the fixed ones.
