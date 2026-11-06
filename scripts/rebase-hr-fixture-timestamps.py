#!/usr/bin/env python3
"""Shift the timestamps of heart-rate fixture CSVs to a fake epoch.

A heart-rate capture's timestamp_ms column is wall-clock epoch milliseconds,
so a fixture committed as captured publishes the day and time of day the
lifter trained. This rewrites that one column so the earliest sample of the
batch lands on a fixed fake epoch (default 2000-01-01T00:00:00Z), and leaves
every other byte alone: bpm, the R-R intervals, line endings.

Pass every file of ONE capture session in one call. The batch gets a single
offset, so intervals between files (a set and the rest before it) survive.
Do not split a session across calls, and do not pass two sessions together
unless the gap between them is meant to be kept.

--epoch-ms may move the target, but only to a value before 2001-01-01, so
that --check still recognises the result as shifted. A file with a header
and no samples is refused.

Only files whose header is exactly `timestamp_ms,hr_bpm,rr_ms` are touched;
anything else (an IMU capture, a cue track) is refused, and nothing is
written unless every file passes. A batch whose earliest sample is already
before 2001-01-01 is refused as already shifted.

Record the printed offset in the fixture's provenance note.

Usage:
  scripts/rebase-hr-fixture-timestamps.py FILE...            # shift in place
  scripts/rebase-hr-fixture-timestamps.py --dry-run FILE...  # print, write nothing
  scripts/rebase-hr-fixture-timestamps.py --check FILE...    # exit 1 if any file is unshifted
"""

import argparse
import sys
from datetime import datetime, timezone

HEADER = "timestamp_ms,hr_bpm,rr_ms"
FAKE_EPOCH_MS = 946_684_800_000  # 2000-01-01T00:00:00Z
ALREADY_SHIFTED_BEFORE_MS = 978_307_200_000  # 2001-01-01T00:00:00Z


def iso(ms):
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).isoformat()


def read(path):
    with open(path, "rb") as f:
        raw = f.read()
    lines = raw.decode("utf-8").splitlines(keepends=True)
    if not lines or lines[0].rstrip("\r\n") != HEADER:
        sys.exit(f"{path}: header is not {HEADER!r}; refusing (not a heart-rate fixture)")
    rows = []
    for n, line in enumerate(lines[1:], start=2):
        body = line.rstrip("\r\n")
        if not body:
            rows.append((None, line))
            continue
        head, sep, rest = body.partition(",")
        if not sep or not head.isdigit():
            sys.exit(f"{path}:{n}: first field {head!r} is not an epoch-ms integer")
        rows.append((int(head), line[len(head):]))
    if not any(ts is not None for ts, _ in rows):
        sys.exit(f"{path}: no samples after the header; refusing (nothing to shift)")
    return lines[0], rows


def first_ts(rows):
    return min(ts for ts, _ in rows if ts is not None)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("files", nargs="+")
    p.add_argument("--epoch-ms", type=int, default=FAKE_EPOCH_MS)
    mode = p.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--check", action="store_true")
    a = p.parse_args()
    if not 0 <= a.epoch_ms < ALREADY_SHIFTED_BEFORE_MS:
        sys.exit(
            f"--epoch-ms {a.epoch_ms} is not in [0, {ALREADY_SHIFTED_BEFORE_MS}) "
            f"(before {iso(ALREADY_SHIFTED_BEFORE_MS)}); --check would report the result as unshifted"
        )

    parsed = {f: read(f) for f in a.files}
    starts = {f: first_ts(rows) for f, (_, rows) in parsed.items()}

    if a.check:
        bad = [f for f, t in starts.items() if t >= ALREADY_SHIFTED_BEFORE_MS]
        for f in bad:
            print(f"UNSHIFTED {f}: first sample {iso(starts[f])}")
        sys.exit(1 if bad else 0)

    batch_start = min(starts.values())
    if batch_start < ALREADY_SHIFTED_BEFORE_MS:
        sys.exit(f"earliest sample is {iso(batch_start)}: batch looks already shifted; refusing")
    offset = a.epoch_ms - batch_start

    for f, (header, rows) in parsed.items():
        out = [header] + [line if ts is None else f"{ts + offset}{tail}" for ts, tail in rows]
        if not a.dry_run:
            with open(f, "wb") as fh:
                fh.write("".join(out).encode("utf-8"))
        print(f"{'would shift' if a.dry_run else 'shifted'} {f}: first sample {starts[f]} -> {starts[f] + offset}")
    print(f"offset_ms={offset} (batch earliest {iso(batch_start)} -> {iso(a.epoch_ms)})")


if __name__ == "__main__":
    main()
