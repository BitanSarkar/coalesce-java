#!/usr/bin/env python3
"""Build a changelog from commits between two refs.

GitHub's own --generate-notes lists merged pull requests. Work here lands directly on main,
so it finds none and emits nothing but a compare link -- which is exactly what the 0.1.2
release ended up with. Reading the commits instead produces notes with actual content.

Commit subjects in this repository are written as full statements, so they carry the
changelog on their own; the first paragraph of the body is included as context underneath.

Usage: changelog.py --to v0.1.2 [--from v0.1.0]
"""

import argparse
import re
import subprocess
import sys

RECORD, FIELD = "\x1e", "\x1f"

# The workflow's own bookkeeping commits are noise in a changelog.
NOISE = re.compile(r"^(Open \S+ for development|Merge (branch|pull request|remote))", re.I)

# CI markers are instructions to the runner, not part of what changed.
MARKER = re.compile(r"\s*\[(skip ci|ci skip|skip release|no ci|skip actions)\]", re.I)


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def commits(frm, to):
    rng = f"{frm}..{to}" if frm else to
    raw = git("log", "--no-merges", f"--pretty=format:%h{FIELD}%s{FIELD}%b{RECORD}", rng)
    for entry in raw.split(RECORD):
        entry = entry.strip("\n")
        if not entry:
            continue
        sha, subject, body = (entry.split(FIELD) + ["", ""])[:3]
        subject = MARKER.sub("", subject).strip()
        if NOISE.match(subject):
            continue
        yield sha, subject, body.strip()


def first_paragraph(body, limit=280):
    para = body.split("\n\n")[0].replace("\n", " ").strip()
    if len(para) > limit:
        para = para[:limit].rsplit(" ", 1)[0] + "…"
    return para


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--to", required=True)
    ap.add_argument("--from", dest="frm", default=None)
    ap.add_argument("--no-detail", action="store_true", help="subjects only")
    args = ap.parse_args()

    entries = list(commits(args.frm, args.to))
    if not entries:
        print("_No code changes in this release._")
        return 0

    print("### What changed\n")
    for sha, subject, body in entries:
        print(f"- **{subject}** ({sha})")
        if not args.no_detail:
            detail = first_paragraph(body)
            if detail:
                print(f"  {detail}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
