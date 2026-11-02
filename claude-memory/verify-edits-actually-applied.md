---
name: verify-edits-actually-applied
description: Never report a code change as done off a text replacement that can silently no-op — verify it landed
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a8dd7611-b8d2-4a24-8702-86d5bacbe29d
  modified: 2026-08-30T14:25:11.840Z
---

On 2026-08-31 I told the user the season screen's scroll focus was fixed. It was
not: the change was made with a Python `str.replace()` inside a Bash heredoc, the
anchor text did not match, the replacement silently did nothing, and the old code
stayed in place. He found it himself two builds later — "its not working that
way. still focuses on the table".

**Why:** `str.replace()` and `sed` return success when they match nothing. A
batch of five edits applied through them can be four-fifths applied and report as
wholly done, and the compiler will not catch it because the old code still
compiles perfectly.

**How to apply:**
- Prefer the **Edit tool** for source changes — it errors when the anchor is
  missing, so a failed edit cannot be mistaken for a successful one.
- When a Python patch script is genuinely the right tool (large or repetitive
  edits), `assert old in t` before every replacement.
- After any batched edit, **grep for the new text** before claiming it is done.
  A one-line check is cheaper than the user finding it.

This matters more than usual here because changes are verified by building and
installing to a phone: a silently-skipped edit still builds, still installs, and
still looks like a delivered fix.


**2026-09-05 — the other half of this: grep gives FALSE NEGATIVES too.**
Auditing memory coverage, a grep for "Luiz Felipe" reported it missing from
[[dream-xi-fifa-grid-faults]] when it was plainly there — the name wrapped
across a line break, so no single line contained it. The mirror of the earlier
false POSITIVE, where a search for "1.2%" matched "31.2%" in an unrelated
sentence and made an unsaved finding look saved.

So a line-based grep over wrapped prose proves nothing on its own, in either
direction. Confirm a hit by reading the surrounding lines, and confirm a miss
with a multi-line search or by opening the file.
