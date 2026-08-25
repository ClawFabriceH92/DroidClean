#!/usr/bin/env python3
"""Vérifie que chaque traduction couvre les mêmes chaînes, avec les mêmes
arguments de format, que la langue par défaut.

Lint attrape `MissingTranslation`, mais seulement quand il tourne — et il ne dit
rien d'un `%1$s` devenu `%1$d` dans une traduction, qui provoque un plantage à
l'exécution (`IllegalFormatConversionException`) uniquement dans cette langue.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
FORMAT = re.compile(r"%(\d+\$)?[a-zA-Z]")


def load(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    return {
        el.get("name"): "".join(el.itertext())
        for el in root.findall("string")
        if el.get("name") and el.get("translatable", "true") != "false"
    }


def specs(text: str) -> list[str]:
    # `%%` est un pourcentage littéral, pas un argument.
    return sorted(FORMAT.findall(text.replace("%%", "")))


def main() -> int:
    base_path = RES / "values/strings.xml"
    base = load(base_path)
    problems: list[str] = []

    translations = sorted(RES.glob("values-*/strings.xml"))
    if not translations:
        print("Aucune traduction trouvée.")
        return 0

    for path in translations:
        locale = path.parent.name
        other = load(path)
        for name in sorted(base.keys() - other.keys()):
            problems.append(f"{locale}: chaîne manquante « {name} »")
        for name in sorted(other.keys() - base.keys()):
            problems.append(f"{locale}: chaîne en trop « {name} » (absente de values/)")
        for name in sorted(base.keys() & other.keys()):
            if specs(base[name]) != specs(other[name]):
                problems.append(
                    f"{locale}: « {name} » n'a pas les mêmes arguments de format "
                    f"({specs(base[name])} vs {specs(other[name])})"
                )
        print(f"{locale}: {len(other)} chaînes")

    print(f"values (référence) : {len(base)} chaînes")
    if problems:
        print("\nProblèmes détectés :")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print("Traductions cohérentes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
