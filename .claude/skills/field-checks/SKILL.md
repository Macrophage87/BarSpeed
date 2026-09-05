---
name: field-checks
description: Build the owner's phone-sized field-check PDF — every open [Field] item, one per page, a fillable checkbox per check — and send it with each release that carries field-testable work. The owner is the only tester; this is the list they take to the gym.
---

# /field-checks <version>

The owner asked for this on 2026-09-05: "a pdf of all the field test items, one per page,
readable on a smartphone", then "a checkbox field on each page or subissue within the page",
then "give me something like this with each release". Field testing is the binding constraint on
this project — one tester, one gym trip per session — so the list has to be complete, current,
and readable from a phone between sets.

## When

- At every `/release-cut` that ships anything carrying a `[Field]` item (step 5, Close out),
  sent with the install notice.
- After a `/field-ingest`, when the report's "[Field] asks for the next session" changed.
- On request.

Skip it only when nothing shipped needs a lifter to confirm it — and say so in the release
summary rather than silently omitting the file.

## 1. Gather the sources — all three, every time

The items live in three places and none of them is complete alone:

1. **Landing comments of the issues shipping in this release.** Every landed issue's close
   comment carries a `[Field]` paragraph (the `/land` checklist puts it there). Read them with
   `gh issue view <n> --json body,comments` and keep every paragraph containing `[Field]`;
   the body's own `[Field]` line is usually an earlier, vaguer draft of the same ask — the
   comment's version is the one with pass criteria. The release brief's issue list says which
   issues to walk.
2. **Open issues labelled `Field`**: `gh issue list --state open --label Field --limit 100`.
   Also grep open titles for `[Field]` — some carry the tag in the title without the label
   (#63, #54, #55 did).
3. **The latest field report's "[Field] asks for the next session"** section
   (`BarSpeed-field-captures/field-<NN>/analysis/field-<NN>-report.md`). Most of these are
   questions the owner answers by message, not gym checks. **Questions do not go in the
   PDF** — owner, 2026-09-05, after the first build put six of them on their own pages: "keep
   the 'asking questions' separate from the testing questions. You can just ask me here or
   provide a single sheet to do so." Ask them in the chat message that delivers the report or
   the PDF, numbered, one line each; if there are more than a handful, one separate
   "Questions" sheet. A report ask that is really a gym check ("both units on one stack once")
   goes in the PDF as a check.

Drop an item only when its issue is closed AND the field-38-style ingest has discharged it in a
report. An item nobody can act on (needs another phone, another API level) stays in, marked
`Deferred`, so the list is visibly complete.

## 2. Write the items

Each page is one item: a source tag, the issue refs, a title, an optional one-sentence context
line, and a list of **checks** — each check is one thing the owner can tick. Split a
paragraph into its checks; a check that bundles two observations is two checks.

Rules that came from the first build:
- Plain language, the owner's words where the issue quotes them. No file paths, no SHAs, no
  test names — the owner is holding a phone in a gym.
- State the **pass** as what the screen or voice does, not what the code does.
- Where the check is really *my* offline verification (a key in the export), the owner's check
  is "export and send the zip"; say the rest is mine.
- Tier every page: `Tier 1` (inside a normal set, no gym cost), `Tier 2` (an extra set or an
  unusual mount — the owner takes these only when convenient), `Deferred` (needs hardware the
  owner does not have). Order: the release's Tier 1, then Tier 2, then Deferred. An item tiered
  `Answer` is a question: the builder leaves it out of the PDF and prints it, so it can be
  pasted into the chat message instead.
- Put the quantities the owner needs *in* the check ("+5 lb at rung 6"), never "see the issue".

Items are a JSON list next to the build script — one object per page:
```json
{"tag": "v0.1.51", "ref": "#244", "title": "Headroom tiles worded by progression",
 "intro": "Rate a reps exercise and a none exercise in the same session.",
 "checks": ["Reps tiles read \"About 3-4 reps left\" / ...", "..."], "tier": "Tier 1"}
```
`tag` picks the colour band: the release version (blue), `Field-38 question` style report
asks (brown), `Open issue` (green). Keep the previous release's JSON in the field-checks
folder; the next one starts from it, dropping what the ingest discharged.

## 3. Build

```
python .claude/skills/field-checks/build_field_checks.py <items.json> <out.pdf>
```
100 x 178 mm pages (a phone's aspect, so "fit width" reads at 12 pt), a colour header with the
tag, refs and tier, the title, the context line in grey italic, then one **AcroForm checkbox**
per check with the check text beside it. Body text shrinks per page only if a page overflows
(the script prints the font size it settled on — anything under 10 pt means the item should be
split into two pages). Page 1 is an index of every item with its refs and tier.

Write the JSON with the Write tool, not a bash heredoc: the item text carries `\'` and quotes
and the heredoc route broke on them on the first build.

## 4. Verify before sending

Render three pages (`pypdfium2` is installed; `may_draw_forms=True` draws the boxes) and look
at them: the index fits one page, a dense page's boxes align with their first line, nothing is
cut. `pypdf`'s `get_fields()` count equals the number of checks. The Read tool refuses this PDF
("password-protected") — it is not; the AcroForm confuses its parser. Render instead.

## 5. Deliver and keep

- `SendUserFile` the PDF as an attachment with a one-line caption (pages, checkboxes).
- Copy the PDF and its JSON to `C:\Users\steph\Documents\BarSpeed-field-captures\field-checks\`
  — the scratchpad is disposable and the next build starts from this JSON.
- Name it by the date and the release it goes with, for example
  `BarSpeed-field-checks-v0.1.51-2026-09-05.pdf`.
- In the release summary, say the list is attached and how many pages; if a lane landed after
  the build (a late fix with its own `[Field]` item), rebuild rather than mention it in prose.
