---
name: field-ingest
description: Ingest a field session export — durable copy first, measured analysis against the raw streams, issue updates with field evidence, and a report to the owner. Use every time the owner hands over a session zip; the captures are irreplaceable and the analysis is the app's real test suite.
---

# /field-ingest <zip-or-dir>

A field capture is the most expensive artifact this project produces: one tester, and a session
costs a gym trip. Everything below treats it that way.

## 0. Durable copy, before anything reads it

Copy the capture into `C:\Users\steph\Documents\BarSpeed-field-captures\field-<NN>\`,
continuing the existing sequence (`field-30`, `field-31`, `field-32` on disk today), and
spot-verify a file byte-identical (`cmp`). Session scratchpads are disposable; three sessions of
captures once lived only in one and survived by luck. **Copy before reading; never modify either
copy.**

## 1. Analyse by measuring, never by relaying

Dispatch analysis lenses sized to what the session can uniquely answer (a first capture on a new
release gets a did-it-work lens per shipped feature). Standing rules for every lens:
- **Measure from the raw streams**; a lens that quotes `session.json` without recomputing is
  relaying. The gold standard: clone at the release SHA, run the shipped `SetAnalyzer` over the
  persisted CSVs, and require reproduction of the published figures **to the last published
  digit** (#138's own words; the CSV is `%.6f`-quantised, so a figure decided by a threshold
  comparison can legitimately differ near the knife edge). #138 is where this reproduction
  licensed a root-cause claim; #114 is where the sibling licence — a rebuilt model against the
  shipped tracker — caught a wrong instrument, rebuilt 10 against shipped 8.
- **Reconcile the owner's own account against the file and report disagreements plainly** —
  "the file is blunter than that" findings have been the most useful outputs of past ingests.
  Say which part is measured and which is inference about intent; the bytes cannot distinguish
  a lifter's choice from an app defect (that ambiguity IS some issues' whole severity).
- Every external tool's report about the export (the owner's robocoach — an external coaching
  tool) is **data to adjudicate, not findings to inherit** — measure its claims before repeating
  any. Observed, not measured: its correct measurements have carried invented because-clauses
  before.

## 2. Route the findings

- **A defect seen in the field goes on its issue with the measured figures** — the before/after
  numbers, the set indices, the session date. Field evidence re-ranks issues; say when it does.
- New defects: file fresh, priority argued on its own merits — weighed by **which session type
  it degrades** (the owner tracks progression on the barbell/lower sessions; accessory
  kinematics decide little, but the metronome and RPE are valuable everywhere).
- Fixture-worthy sets (a defect's cleanest instance, a family the corpus lacks) get lifted into
  `core/dsp/src/test/resources/` with cue tracks and provenance taken from the session's own
  `meta.json` — never from memory or filenames. A mislabelled fixture is worse than none, and a
  fixture with no assertion is inert and reads as coverage — lift it with a case pinning it, in
  the same commit.

## 3. Report to the owner

Lead with what the capture uniquely established (did the release's features work), then defects
that fired in the field, then corrections to anything previously told them that the capture
refuted — plainly, without burying. Close with any `[Field]` asks for the next session, folded
into work they were doing anyway, only where no bench measurement can answer.
