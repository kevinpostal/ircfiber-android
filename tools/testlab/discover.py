"""Test discovery from source and tier selection.

Ids are canonical: Android `Class#method`.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

from config import UsageError

KOTLIN_PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.M)
KOTLIN_CLASS = re.compile(r"^\s*(?:(?:public|internal|open|abstract|final|private)\s+)*class\s+(\w+)", re.M)
# @Test, then any other annotations (with simple arguments), then the function.
KOTLIN_TEST = re.compile(r"@Test\b(?:\([^)]*\))?(?:\s*@[\w.:]+(?:\([^)]*\))?)*\s*"
                         r"(?:(?:public|internal|open|override|suspend)\s+)*fun\s+(\w+)\s*\(")
LIVE_E2E = "@LiveE2e("


class SelectionError(UsageError):
    pass


def kotlin_tests(files) -> dict[str, str]:
    """`Class#method` → `pkg.Class#method` for every `@Test fun`. Files with `@LiveE2e(` classes are skipped: those run
    only with their runner argument (LiveE2eFilter)."""
    tests: dict[str, str] = {}
    for path in files:
        text = Path(path).read_text()
        if LIVE_E2E in text:
            continue
        package = KOTLIN_PACKAGE.search(text)
        cls = KOTLIN_CLASS.search(text)
        if not cls:
            continue
        prefix = f"{package.group(1)}." if package else ""
        for found in KOTLIN_TEST.finditer(text):
            tests[f"{cls.group(1)}#{found.group(1)}"] = f"{prefix}{cls.group(1)}#{found.group(1)}"
    return tests


def select(all_ids, tier: str | None, only, smoke, failed_file) -> tuple[list[str], str]:
    """(ids, label). `only` regexes win (label "only"); otherwise the tier: smoke list, every test, or last failures."""
    all_ids = list(all_ids)
    if only:
        try:
            patterns = [re.compile(p) for p in only]
        except re.error as e:
            raise SelectionError(f"bad --only regex: {e}") from None
        ids = [i for i in all_ids if any(p.search(i) for p in patterns)]
        if not ids:
            raise SelectionError(f"no tests match {' or '.join(repr(p) for p in only)}")
        return ids, "only"
    known = set(all_ids)
    if tier == "smoke":
        missing = [i for i in smoke if i not in known]
        if missing:
            raise SelectionError("smoke tests not found in the sources (edit tools/testlab/suites.json): "
                                 + ", ".join(missing))
        return list(smoke), "smoke"
    if tier in (None, "full"):
        return all_ids, "full"
    if tier == "failed":
        try:
            listed = [line.strip() for line in Path(failed_file).read_text().splitlines() if line.strip()]
        except FileNotFoundError:
            return [], "failed"
        listed = list(dict.fromkeys(listed))
        dropped = [i for i in listed if i not in known]
        if dropped:
            print(f"warning: no longer in the sources, not rerun: {', '.join(dropped)}", file=sys.stderr)
        return [i for i in listed if i in known], "failed"
    raise SelectionError(f"unknown tier {tier!r}")
