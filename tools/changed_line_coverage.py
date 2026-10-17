"""Measure line coverage restricted to a lane's changed Java lines.

Usage (after `mvn clean test -DargLine=-javaagent:...jacoco.agent...` and
`org.jacoco.cli ... report ... --xml target/jacoco.xml`):

    python tools/changed_line_coverage.py

The scope is explicit: a line counts only when it is an executable line in the
JaCoCo XML *and* it falls inside a changed line range from `git diff` (or inside
a new file listed below). This avoids reporting whole-repository coverage for a
change that only touches a few methods.
"""

import json
import subprocess
import xml.etree.ElementTree as ET

# Lane-touched Java files, relative to the repository root.
NEW_FILES = [
    "src/main/java/com/linklike/server/config/MasterItemsSeeder.java",
]
MODIFIED_FILES = [
    "src/main/java/com/linklike/server/wire/WireGrants.java",
]


def changed_lines(path):
    with open(path, encoding="utf-8") as handle:
        return set(range(1, len(handle.read().splitlines()) + 1))


def diff_lines(path):
    lines = set()
    diff = subprocess.run(
        ["git", "diff", "-U0", "--", path],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=True,
    ).stdout
    for line in diff.splitlines():
        if not line.startswith("@@"):
            continue
        added = line.split("+", 1)[1].split("@@", 1)[0].strip()
        start, _, count = added.partition(",")
        start = int(start)
        count = int(count) if count else 1
        lines.update(range(start, start + count))
    return lines


def main():
    changed = {path: changed_lines(path) for path in NEW_FILES}
    changed.update({path: diff_lines(path) for path in MODIFIED_FILES})

    coverage = {}
    for package in ET.parse("target/jacoco.xml").getroot().findall("package"):
        package_name = package.get("name")
        for source_file in package.findall("sourcefile"):
            path = "src/main/java/%s/%s" % (package_name, source_file.get("name"))
            for line in source_file.findall("line"):
                coverage[(path, int(line.get("nr")))] = int(line.get("ci"))

    covered = 0
    total = 0
    uncovered = {}
    per_file = {}
    for path, lines in changed.items():
        file_total = 0
        file_covered = 0
        for number in sorted(lines):
            if (path, number) not in coverage:
                continue
            file_total += 1
            if coverage[(path, number)] > 0:
                file_covered += 1
            else:
                uncovered.setdefault(path, []).append(number)
        total += file_total
        covered += file_covered
        per_file[path] = {"covered": file_covered, "executable": file_total}

    print(json.dumps({
        "scope": "changed executable lines in the lane's Java sources",
        "covered": covered,
        "executable": total,
        "percent": round(100.0 * covered / total, 2) if total else 0.0,
        "files": per_file,
        "uncoveredLines": uncovered,
    }, indent=2))


if __name__ == "__main__":
    main()
