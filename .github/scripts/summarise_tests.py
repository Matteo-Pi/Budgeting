"""Prints a short, readable summary of JUnit XML results: totals, then each failure with the
first lines of its stack trace. Usage: summarise_tests.py <dir containing TEST-*.xml>."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1] if len(sys.argv) > 1 else "."
total = failed = skipped = 0
failures = []
for path in sorted(glob.glob(os.path.join(root, "**", "*.xml"), recursive=True)):
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        continue
    for case in tree.iter("testcase"):
        total += 1
        name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        if case.find("skipped") is not None:
            skipped += 1
        elif problem is not None:
            failed += 1
            text = (problem.get("message") or "") + "\n" + (problem.text or "")
            lines = [l for l in text.strip().splitlines() if l.strip()][:25]
            failures.append((name, lines))

print(f"Tests: {total}  failed: {failed}  skipped: {skipped}  passed: {total - failed - skipped}")
for name, lines in failures:
    print(f"\nFAILED {name}")
    for line in lines:
        print("    " + line)
