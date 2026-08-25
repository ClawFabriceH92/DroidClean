#!/usr/bin/env python3
"""Extrait la section du CHANGELOG correspondant à une version.

Sert à la CI : les notes de release GitHub sont ainsi le CHANGELOG lui-même,
qui est aussi ce que l'application affiche avant de proposer une mise à jour.

Usage : tools/changelog-section.py 1.2.0 [CHANGELOG.md]
"""
from __future__ import annotations

import sys
from pathlib import Path


def section(text: str, version: str) -> str:
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        if line.startswith("## ") and version in line:
            start = index + 1
            break
    if start is None:
        return ""
    end = len(lines)
    for index in range(start, len(lines)):
        if lines[index].startswith("## "):
            end = index
            break
    return "\n".join(lines[start:end]).strip()


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: changelog-section.py <version> [fichier]", file=sys.stderr)
        return 2
    version = sys.argv[1].lstrip("v")
    path = Path(sys.argv[2] if len(sys.argv) > 2 else "CHANGELOG.md")
    if not path.is_file():
        return 1
    body = section(path.read_text(encoding="utf-8"), version)
    if not body:
        return 1
    print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
