#!/usr/bin/env python3
"""Renders JUnit XML into a GitHub job summary table.

Gradle's console output says only "BUILD SUCCESSFUL", which cannot distinguish a run where
the Redis-gated integration tests executed from one where they all skipped. This makes the
per-class counts visible on the run page.
"""

import glob
import sys
import xml.etree.ElementTree as ET

rows, totals = [], [0, 0, 0, 0.0]

for path in sorted(glob.glob("*/build/test-results/test/*.xml")):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    if root.tag != "testsuite":
        continue
    tests = int(root.get("tests", 0))
    failures = int(root.get("failures", 0)) + int(root.get("errors", 0))
    skipped = int(root.get("skipped", 0))
    time = float(root.get("time", 0))
    rows.append((root.get("name", path), tests, failures, skipped, time))
    totals[0] += tests
    totals[1] += failures
    totals[2] += skipped
    totals[3] += time

if not rows:
    print("## Tests\n\nNo test results were produced.")
    sys.exit(0)

status = "All green" if totals[1] == 0 else f"{totals[1]} failing"
print(f"## Tests — {status}\n")
print(f"**{totals[0]} tests**, {totals[1]} failed, {totals[2]} skipped, {totals[3]:.1f}s\n")
print("| Suite | Tests | Failed | Skipped | Time |")
print("|---|---:|---:|---:|---:|")
for name, tests, failures, skipped, time in rows:
    mark = "" if failures == 0 else " ⚠️"
    print(f"| `{name}`{mark} | {tests} | {failures} | {skipped} | {time:.1f}s |")

if totals[2]:
    print(
        "\n> Skipped tests are usually the Redis-gated integration tests. "
        "If those skipped, this run did not actually exercise coalescing."
    )
