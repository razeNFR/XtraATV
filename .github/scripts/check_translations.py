#!/usr/bin/env python3
"""
Xtra TV: every string of the TV ("tv_" keys) must be in every language of the app. A string missing in a language, or
a sentence (3 words or more) still in English there, fails the build. Short words that stay the same in many languages
("LIVE", "Auto", "Backup") are allowed.
"""
import glob
import os
import re
import sys

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "res")


def load(path):
    text = open(path, encoding="utf-8").read()
    return {
        m.group(1): m.group(3)
        for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', text, re.S)
        if 'translatable="false"' not in m.group(2)
    }


def is_sentence(value):
    words = re.findall(r"[A-Za-z]{2,}", re.sub(r"%\d\$[sd]|%[sd]", "", value))
    return len(words) >= 3


base = load(os.path.join(RES, "values", "strings.xml"))
keys = [k for k in base if k.startswith("tv_")]
problems = []
for folder in sorted(glob.glob(os.path.join(RES, "values-*"))):
    path = os.path.join(folder, "strings.xml")
    if not os.path.exists(path):
        continue
    locale = os.path.basename(folder)[len("values-"):]
    strings = load(path)
    for key in keys:
        if key not in strings:
            problems.append(f"{locale}: {key} is missing")
        elif strings[key] == base[key] and is_sentence(base[key]):
            problems.append(f"{locale}: {key} is still in English ({base[key]})")
if problems:
    print("Strings of the TV not translated:")
    print("\n".join(problems))
    sys.exit(1)
print(f"{len(keys)} TV strings in every language")
