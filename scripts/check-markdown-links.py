#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit


ROOT = pathlib.Path(__file__).resolve().parent.parent
LINK = re.compile(r"\]\(([^)]+)\)")
REMOTE_SCHEMES = {"http", "https", "mailto", "app"}


def public_markdown() -> list[pathlib.Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "*.md"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return [ROOT / line for line in result.stdout.splitlines() if line]


def local_target(raw: str) -> str | None:
    target = raw.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1]
    target = target.split(maxsplit=1)[0]
    if target.startswith("#"):
        return None
    parsed = urlsplit(target)
    if parsed.scheme.lower() in REMOTE_SCHEMES or parsed.netloc:
        return None
    return unquote(parsed.path) or None


def main() -> int:
    missing: list[str] = []
    for document in public_markdown():
        text = document.read_text(encoding="utf-8")
        for line_number, line in enumerate(text.splitlines(), start=1):
            for match in LINK.finditer(line):
                target = local_target(match.group(1))
                if target is None:
                    continue
                resolved = (document.parent / target).resolve()
                try:
                    resolved.relative_to(ROOT)
                except ValueError:
                    missing.append(f"{document.relative_to(ROOT)}:{line_number}: путь вне репозитория: {target}")
                    continue
                if not resolved.exists():
                    missing.append(f"{document.relative_to(ROOT)}:{line_number}: не найдено: {target}")
    if missing:
        print("Найдены битые локальные Markdown-ссылки:", file=sys.stderr)
        print("\n".join(f"  {item}" for item in missing), file=sys.stderr)
        return 1
    print("Локальные Markdown-ссылки корректны")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
