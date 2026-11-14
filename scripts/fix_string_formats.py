#!/usr/bin/env python3
"""
Make the Android string resources pass the resource compiler (aapt2).

aapt2 stops the build with
    "Multiple substitutions specified in non-positional format of string resource string/NAME.
     Did you mean to add the formatted="false" attribute?"
when a <string> has two or more non-positional format specifiers (%s %d %.2f ...) and is not marked
formatted="false". That attribute only switches the check off: the text is not changed, and the string
can still be used with String.format / getString(id, args) in code.

This script adds formatted="false" to those strings in every values*/ folder. It is safe to run many
times (strings that are already fine are not touched) and it never changes the text of a string.

Usage:  python3 scripts/fix_string_formats.py [project_root]
"""
import re
import sys
from pathlib import Path

# Java / Android format specifier: %[index$][flags][width][.precision]conversion
SPEC = re.compile(r"%(\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([a-zA-Z%])")
STRING = re.compile(r"(<string\b)([^>]*)(>)(.*?)(</string>)", re.DOTALL)
FORMATTED_OFF = re.compile(r"""\bformatted\s*=\s*["']false["']""")
UNTRANSLATABLE = re.compile(r"""\btranslatable\s*=\s*["']false["']""")


def non_positional_count(text: str) -> int:
    """How many substitutions in `text` have no explicit argument index (%1$s style)."""
    count = 0
    for match in SPEC.finditer(text):
        if match.group(2) == "%":      # a literal percent sign: %%
            continue
        if match.group(1) is None:      # %s / %d / %.2f ...
            count += 1
    return count


def fix_text(xml: str):
    """Returns (new_xml, names_of_fixed_strings)."""
    fixed = []

    def repl(match):
        start, attributes, close, body, end = match.groups()
        if FORMATTED_OFF.search(attributes) or UNTRANSLATABLE.search(attributes):
            return match.group(0)
        if non_positional_count(body) < 2:
            return match.group(0)
        name = re.search(r"""\bname\s*=\s*["']([^"']+)["']""", attributes)
        fixed.append(name.group(1) if name else "?")
        return start + attributes + ' formatted="false"' + close + body + end

    return STRING.sub(repl, xml), fixed


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    changed_files = 0
    changed_strings = 0
    # <module>/src/<variant>/res/values*  (never the build output, never the bundled Java runtimes)
    for folder in sorted(root.glob("*/src/*/res/values*")):
        if not folder.is_dir():
            continue
        for xml_file in sorted(folder.glob("*.xml")):
            original = xml_file.read_text(encoding="utf-8")
            updated, fixed = fix_text(original)
            if fixed:
                xml_file.write_text(updated, encoding="utf-8")
                changed_files += 1
                changed_strings += len(fixed)
                print(f"fixed {len(fixed):3d} string(s) in {xml_file.relative_to(root)}")
    print(f"Done: {changed_strings} string(s) in {changed_files} file(s) marked formatted=\"false\"")
    return 0


if __name__ == "__main__":
    sys.exit(main())
