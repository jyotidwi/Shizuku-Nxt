#!/usr/bin/env python3
r"""The apostrophe rule for string resources, in the one place it is decided.

Android's resource compiler refuses a bare apostrophe in a string value. The aapt2 that
ships with this project's build tools (36.0.0) says `unescaped apostrophe in string`, and it
says it wherever the apostrophe is not inside a quoted region - outside CDATA, inside CDATA,
and even where the apostrophe is quoting an HTML attribute in the markup a string carries.
What it does accept is an escaped one, or a bare one inside a region opened by `"`, which is
the other way Android has always allowed an apostrophe through: the quotes themselves are
stripped from the visible text, so the value reads as the translator wrote it without them.

Both were measured against aapt2 rather than recalled, which is why the rule below is
"escape an unescaped apostrophe that no quoted region covers" and not simply "escape every
apostrophe" - the second would rewrite translations that compile today:

    "say 'hi'"                  -> say 'hi'                        accepted
    "say \'hi\'"                -> say 'hi'                        accepted, same text
    say \'hi\'                  -> say 'hi'                        accepted
    say 'hi'                                                       refused
    <![CDATA[.... nell'app ...]]>                                  refused, markup changes nothing
    He said "don't" and left.   -> He said don't and left.         accepted
    don't "x"                                                      refused, the apostrophe is outside

Escaping is the repair, and not wrapping the value in quotes: the wrapper is stripped from
what a user reads, so quoting a value that does not already carry quotes would change the
text on screen, while an escaped apostrophe is not visible at all.

Translators work in Crowdin, where nobody is thinking about aapt2, so an Italian sentence
arrives holding `nell'app` and the export writes it that way - which is what put master's
Build App in the red, twice. The export rewrites each value from the translator's text every
time it runs, so escaping the value in the repository does not hold: the next download puts
the bare apostrophe back. The repair therefore belongs after the export, which is where the
`Translations` workflow runs this.

Two callers, one rule:

  * `.github/workflows/crowdin.yml` repairs the branch the download has just pushed, with
    `python3 ci/translation_escapes.py`, before the check and the merge that follow it.
  * `ci/check-translations.py` imports `bare_apostrophes` from here and refuses a pull
    request whose translations would not compile - a hand-made one, which no download step
    has repaired.

    translation_escapes.py [path ...]

With no paths it repairs every `manager/src/main/res/values-*/strings.xml`. It rewrites files
in place, prints one line per value it changes, and exits 0 whether or not it changed
anything: whether a change means anything - a commit, say - is the caller's business.
"""
import glob
import re
import sys

APOSTROPHE = "'"
BACKSLASH = "\\"
QUOTE = '"'

# The text of a `<string>` is a string value, and so is the text of the `<item>` a plural is
# made of. Comments are not matched, so the apostrophes in the notes around these elements
# are left alone.
VALUE = re.compile(r"<(string|item)\b[^>]*>(.*?)</\1>", re.DOTALL)

DEFAULT_PATHS = "manager/src/main/res/values-*/strings.xml"


def outside_quotes(value):
    r"""Yield the index of every unescaped apostrophe that no quoted region covers.

    Walks the value the way the resource compiler does: a backslash escapes the character
    after it, and a `"` toggles the quoted region - a region does not have to be closed, so
    `He said "don't and left.` is accepted as it stands, while the same apostrophe before the
    opening quote is not.
    """
    quoted = False
    escaped = False
    for index, char in enumerate(value):
        if escaped:
            escaped = False
        elif char == BACKSLASH:
            escaped = True
        elif char == QUOTE:
            quoted = not quoted
        elif char == APOSTROPHE and not quoted:
            yield index


def bare_apostrophes(value):
    """How many apostrophes in `value` the resource compiler would refuse."""
    return sum(1 for _ in outside_quotes(value))


def escape(value):
    """Return `value` with every apostrophe the compiler would refuse escaped."""
    refused = set(outside_quotes(value))
    escaped = []
    for index, char in enumerate(value):
        if index in refused:
            escaped.append(BACKSLASH)
        escaped.append(char)
    return "".join(escaped)


def name_of(opening_tag):
    """The name to report a value by: its `name`, a plural's `quantity`, or nothing."""
    for attribute in ("name", "quantity"):
        found = re.search(rf'{attribute}="([^"]*)"', opening_tag)
        if found:
            return found.group(1)
    return "?"


def repair(text):
    """Return `text` with every refused apostrophe escaped, and the values that changed."""
    changed = []

    def replace(match):
        body = match.group(2)
        if not bare_apostrophes(body):
            return match.group(0)
        opening = match.start(2) - match.start(0)
        closing = match.end(2) - match.start(0)
        changed.append((text.count("\n", 0, match.start(2)) + 1, name_of(match.group(0))))
        return match.group(0)[:opening] + escape(body) + match.group(0)[closing:]

    return VALUE.sub(replace, text), changed


def main():
    paths = sys.argv[1:] or sorted(glob.glob(DEFAULT_PATHS))
    if not paths:
        print(f"no files matched {DEFAULT_PATHS}")
        return 0

    files = 0
    values = 0
    for path in paths:
        with open(path, encoding="utf-8") as handle:
            original = handle.read()
        fixed, changed = repair(original)
        if not changed:
            continue
        files += 1
        values += len(changed)
        for line, name in changed:
            print(f"{path}:{line}: escaped the apostrophes in {name}")
        with open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(fixed)

    print(f"escaped {values} value(s) in {files} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
